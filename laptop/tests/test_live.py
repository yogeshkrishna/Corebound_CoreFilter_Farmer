import io
import json
from pathlib import Path
import socket
import struct
import sys
import tempfile
import threading
import time
import unittest
import urllib.request
import urllib.error
import uuid
import cv2
import numpy as np
from PIL import Image
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from mapper import Mapper, looks_like_gameplay
from studio import Studio, make_handler
from http.server import ThreadingHTTPServer


def world(seed=3):
    rng = np.random.default_rng(seed)
    gray = rng.integers(10, 115, (1400, 2600), dtype=np.uint8)
    gray = cv2.GaussianBlur(gray, (3,3), .6)
    for x in range(0, 2600, 150):
        cv2.line(gray, (x,0), (x,1400), 155, 3)
    for y in range(0,1400,210):
        cv2.line(gray, (0,y), (2600,y), 155, 3)
    return cv2.cvtColor(gray, cv2.COLOR_GRAY2BGR)


def view(terrain, x=0, y=0):
    image = terrain[y:y+540, x:x+960].copy()
    # Fixed game UI moves independently from world imagery.
    cv2.rectangle(image, (30,0), (320,65), (0,0,0), -1)
    for left in (96,212):
        cv2.rectangle(image, (left,390), (left+112,487), (0,0,0), -1)
        cv2.rectangle(image, (left,390), (left+112,487), (160,160,160), 4)
    cv2.circle(image,(480,270),35,(0,180,240),-1)
    return image


def metadata(image, session=None, sequence=1):
    h,w=image.shape[:2]
    return dict(schema=1,session=session or str(uuid.uuid4()),sequence=sequence,width=w,height=h,
                package='com.Overcurve.Corebound',format='png',resized=False,occlusions=[[30,0,320,65]])


class Reconstruction(unittest.TestCase):
    def test_native_map_tracks_reversal_drop_and_keeps_unknowns(self):
        with tempfile.TemporaryDirectory() as folder:
            mapper=Mapper(folder);terrain=world()
            origins=[(0,0),(100,0),(200,0),(200,100),(100,100),(0,100),(0,0)]
            for i,(x,y) in enumerate(origins):
                frame=view(terrain,x,y)
                self.assertTrue(looks_like_gameplay(frame))
                pose=mapper.ingest(frame,metadata(frame),i)
                self.assertTrue(pose['mapped'],pose)
                self.assertEqual(pose['component'],0)
                np.testing.assert_allclose(pose['origin'],[x,y],atol=2.)
            output=Path(folder)/'native.png'
            mapper.components[0]['atlas'].export(output)
            with Image.open(output) as result:
                self.assertEqual(result.width,1160)
                self.assertEqual(result.height,640)
                self.assertEqual(result.getpixel((50,10))[3],0)  # masked HUD stays unknown
                actual=np.array(result)
                expected=cv2.cvtColor(terrain[:640,:1160],cv2.COLOR_BGR2RGB)
                mapped=actual[:,:,3]!=0
                np.testing.assert_array_equal(actual[:,:,:3][mapped],expected[mapped])
            self.assertGreater(mapper.components[0]['borders'].__len__(),0)

    def test_disconnected_views_are_not_glued_to_guessed_origin(self):
        with tempfile.TemporaryDirectory() as folder:
            mapper=Mapper(folder)
            for i,seed in enumerate((7,33)):
                image=view(world(seed))
                pose=mapper.ingest(image,metadata(image),i)
                self.assertEqual(pose['component'],i)
            self.assertEqual(len(mapper.components),2)

    def test_menus_remain_original_evidence(self):
        with tempfile.TemporaryDirectory() as folder:
            mapper=Mapper(folder);image=world()[:540,:960]
            self.assertFalse(mapper.ingest(image,metadata(image),1)['mapped'])
            self.assertFalse(mapper.components)

    def test_registration_analysis_does_not_resize_output(self):
        with tempfile.TemporaryDirectory() as folder:
            mapper=Mapper(folder)
            image=cv2.resize(view(world()),(1920,1080),interpolation=cv2.INTER_NEAREST)
            mapper.ingest(image,metadata(image),1)
            result=Path(folder)/'native.png';mapper.components[0]['atlas'].export(result)
            with Image.open(result) as png:
                self.assertEqual(png.width,1920)


class Receiver(unittest.TestCase):
    def setUp(self):
        self.directory=tempfile.TemporaryDirectory()
        self.studio=Studio(self.directory.name,'A'*43,0)
        self.server=ThreadingHTTPServer(('127.0.0.1',0),make_handler(self.studio))
        self.base='http://127.0.0.1:'+str(self.server.server_port)
        threading.Thread(target=self.server.serve_forever,daemon=True).start()
        self.image=view(world());self.meta=metadata(self.image)
        _,encoded=cv2.imencode('.png',self.image);self.png=bytes(encoded)

    def tearDown(self):
        if self.server:
            self.server.shutdown();self.server.server_close()
        self.studio.close();self.directory.cleanup()

    def upload(self,meta=None,png=None,key='A'*43):
        header=json.dumps(meta or self.meta).encode()
        body=struct.pack('>I',len(header))+header+(png or self.png)
        request=urllib.request.Request(self.base+'/api/frame',data=body,headers={'Authorization':'Bearer '+key})
        return urllib.request.urlopen(request,timeout=10)

    def test_ack_preserves_native_bytes_metadata_and_idempotent_retry(self):
        with self.upload() as response:
            self.assertEqual(response.status,201);first=json.load(response)
        with self.upload() as response:
            self.assertEqual(response.status,200);self.assertEqual(json.load(response)['id'],first['id'])
        path=next(Path(self.directory.name).glob('*/frames/*.png'))
        self.assertEqual(path.read_bytes(),self.png)
        deadline=time.time()+8
        while self.studio.status()['pending'] and time.time()<deadline:
            time.sleep(.05)
        self.assertEqual(self.studio.status()['pending'],0)
        self.assertEqual(self.studio.status()['received'],1)
        self.assertEqual(self.studio.status()['components'][0]['frames'],1)
        self.assertEqual(self.studio.status()['error'],'')
        self.assertTrue(self.studio.status()['last']['mapped'])

    def test_unauthorized_or_inconsistent_dimensions_are_rejected(self):
        for meta,key,status in [(self.meta,'B'*43,401),(dict(self.meta,width=1000),'A'*43,400),
                                (dict(self.meta,resized=True),'A'*43,400)]:
            with self.assertRaises(urllib.error.HTTPError) as error:
                self.upload(meta,key=key)
            self.assertEqual(error.exception.code,status)
        self.assertEqual(self.studio.status()['received'],0)

    def test_key_and_new_map_are_stable_and_separate(self):
        with self.upload(): pass
        old=self.studio.active
        request=urllib.request.Request(self.base+'/connect/'+'A'*43+'/api/new',data=b'')
        with urllib.request.urlopen(request) as response:
            self.assertNotEqual(json.load(response)['map'],old)
        self.assertTrue((Path(self.directory.name)/old/'frames').exists())
        self.assertTrue(self.studio.status()['link'].endswith('/connect/'+'A'*43))

    def test_restart_rebuilds_only_new_system_evidence(self):
        with self.upload():pass
        active=self.studio.active
        self.server.shutdown();self.server.server_close();self.studio.close()
        self.server=None
        self.studio=Studio(self.directory.name,'A'*43,0)
        self.assertEqual(self.studio.active,active)
        self.assertEqual(self.studio.status()['received'],1)


if __name__=='__main__':
    unittest.main()
