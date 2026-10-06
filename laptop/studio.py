"""Ceiling Scout Studio: authenticated LAN capture, original evidence, live reconstruction."""
import argparse
from collections import deque
import hashlib
import io
import json
import os
from pathlib import Path
import re
import secrets
import socket
import sqlite3
import struct
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse
import cv2
import numpy as np
from mapper import Mapper

ROOT = Path(__file__).resolve().parent
LIMIT = 24 * 1024 * 1024


def lan_address():
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(('192.168.68.1', 80))
            return s.getsockname()[0]
    except OSError:
        return '127.0.0.1'


class Studio:
    def __init__(self, data, token, port):
        self.data = Path(data)
        self.data.mkdir(parents=True, exist_ok=True)
        self.token, self.port = token, port
        self.db = sqlite3.connect(self.data / 'frames.sqlite', check_same_thread=False)
        self.db.execute('CREATE TABLE IF NOT EXISTS frames (id INTEGER PRIMARY KEY, map TEXT, session TEXT, seq INTEGER, image TEXT, metadata TEXT, sha TEXT, pose TEXT, UNIQUE(map,session,seq))')
        self.db.execute('CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT)')
        self.db.commit()
        self.lock = threading.RLock()
        self.maplock = threading.RLock()
        self.wake = threading.Event()
        self.closed = False
        self.active = None
        self.mapper = None
        self.received = self.bytes = 0
        self.arrivals = deque(maxlen=100)
        self.started = time.time()
        self.error = ''
        self.latest = None
        self.snapshot = {}
        # Only new-system data is resumed; no old archive import exists.
        row = self.db.execute("SELECT value FROM settings WHERE key='active-map'").fetchone()
        if not row:
            row = self.db.execute('SELECT map FROM frames ORDER BY id DESC LIMIT 1').fetchone()
        self.select(row[0] if row else str(uuid.uuid4()))
        self.worker = threading.Thread(target=self.process, daemon=True, name='native-map-builder')
        self.worker.start()

    def select(self, map_id):
        with self.maplock, self.lock:
            self.active = map_id
            self.mapper = Mapper(self.data / map_id / 'atlas')
            # Rebuild from native evidence on restart, never rely on half-written atlas state.
            self.db.execute('UPDATE frames SET pose=NULL WHERE map=?', (map_id,))
            self.db.execute("INSERT OR REPLACE INTO settings(key,value) VALUES('active-map',?)", (map_id,))
            self.db.commit()
            self.latest = None
            self.error = ''
            self.snapshot = self.mapper.summary()
            self.wake.set()

    def receive(self, metadata, png):
        if metadata.get('schema') != 1 or metadata.get('format') != 'png' or metadata.get('resized') is not False:
            raise ValueError('Expected native schema-1 PNG frame')
        session = str(uuid.UUID(metadata['session']))
        seq = metadata['sequence']
        if not isinstance(seq, int) or isinstance(seq, bool) or not 1 <= seq <= 1_000_000_000:
            raise ValueError('Invalid sequence')
        if len(png) < 24 or png[:8] != b'\x89PNG\r\n\x1a\n':
            raise ValueError('Invalid PNG')
        w, h = struct.unpack('>II', png[16:24])
        if not (320 <= w <= 8192 and 160 <= h <= 4096 and w > h and w*h <= 20_000_000):
            raise ValueError('Expected native landscape frame')
        if metadata.get('width') != w or metadata.get('height') != h or metadata.get('package') != 'com.Overcurve.Corebound':
            raise ValueError('Frame dimensions/package do not match metadata')
        masks = metadata.get('occlusions', [])
        if not isinstance(masks, list) or len(masks) > 12:
            raise ValueError('Invalid occlusion masks')
        for rect in masks:
            if not isinstance(rect, list) or len(rect) != 4 or not all(isinstance(v, int) for v in rect):
                raise ValueError('Invalid occlusion rectangle')
        image = cv2.imdecode(np.frombuffer(png, np.uint8), cv2.IMREAD_COLOR)
        if image is None or image.shape[:2] != (h, w):
            raise ValueError('PNG decode failed')
        digest = hashlib.sha256(png).hexdigest()
        metadata = dict(metadata, receivedWallMs=round(time.time()*1000), pngBytes=len(png))
        with self.lock:
            map_id = self.active
            existing = self.db.execute('SELECT id,sha FROM frames WHERE map=? AND session=? AND seq=?', (map_id, session, seq)).fetchone()
            if existing:
                if existing[1] != digest:
                    raise ValueError('Sequence reused with different pixels')
                return dict(stored=True, duplicate=True, id=existing[0], sha256=digest)
            directory = self.data / map_id / 'frames'
            directory.mkdir(parents=True, exist_ok=True)
            path = directory / f'{session}_{seq:09d}.png'
            temporary = path.with_suffix('.tmp')
            with temporary.open('wb') as out:
                out.write(png)
                out.flush()
                os.fsync(out.fileno())
            temporary.replace(path)
            cursor = self.db.execute('INSERT INTO frames(map,session,seq,image,metadata,sha) VALUES(?,?,?,?,?,?)',
                                     (map_id, session, seq, str(path.relative_to(self.data)), json.dumps(metadata), digest))
            self.db.commit()
            self.latest = str(path)
            self.received += 1
            self.bytes += len(png)
            self.arrivals.append(time.time())
        self.wake.set()
        return dict(stored=True, id=cursor.lastrowid, sha256=digest)

    def process(self):
        last_flush = 0
        while not self.closed:
            with self.maplock:
                with self.lock:
                    row = self.db.execute('SELECT id,image,metadata FROM frames WHERE map=? AND pose IS NULL ORDER BY id LIMIT 1', (self.active,)).fetchone()
                if row:
                    try:
                        image = cv2.imread(str(self.data / row[1]))
                        if image is None:
                            raise ValueError('Stored image unavailable')
                        pose = self.mapper.ingest(image, json.loads(row[2]), row[0])
                    except Exception as exc:
                        self.error = str(exc)
                        pose = dict(mapped=False, error=str(exc))
                    if time.time()-last_flush > 2:
                        self.mapper.flush()
                        last_flush = time.time()
                    self.snapshot = self.mapper.summary()
                    with self.lock:
                        self.db.execute('UPDATE frames SET pose=? WHERE id=?', (json.dumps(pose), row[0]))
                        self.db.commit()
                    continue
                self.mapper.flush()
                self.snapshot = self.mapper.summary()
            self.wake.wait(.5)
            self.wake.clear()

    def status(self):
        with self.lock:
            count, pending = self.db.execute('SELECT COUNT(*), SUM(pose IS NULL) FROM frames WHERE map=?', (self.active,)).fetchone()
            recent = [t for t in self.arrivals if time.time()-t < 5]
            fps = (len(recent)-1)/max(.1,recent[-1]-recent[0]) if len(recent)>1 else 0
        # Mapping operations have one owner; snapshot publication is short.
        summary = self.snapshot
        return dict(map=self.active, received=count, pending=pending or 0, fps=round(fps, 1),
                    megabytes=round(self.bytes/1e6, 1), error=self.error, directory=str(self.data / self.active),
                    link=f'http://{lan_address()}:{self.port}/connect/{self.token}', **summary)

    def close(self):
        self.closed = True
        self.wake.set()
        self.worker.join(10)
        with self.maplock:
            self.mapper.flush()
        self.db.close()


def make_handler(studio):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass  # Never log the secret pairing URL.

        def response(self, data, mime='application/json', code=200):
            if isinstance(data, (dict, list)):
                data = json.dumps(data).encode()
            if isinstance(data, str):
                data = data.encode()
            self.send_response(code)
            self.send_header('Content-Type', mime)
            self.send_header('Content-Length', str(len(data)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('Referrer-Policy', 'no-referrer')
            self.end_headers()
            self.wfile.write(data)

        def authorized(self):
            prefix = f'/connect/{studio.token}'
            path = urlparse(self.path).path
            url_auth = path == prefix or path.startswith(prefix+'/')
            header = self.headers.get('Authorization', '')
            return url_auth or secrets.compare_digest(header, 'Bearer '+studio.token)

        def route(self):
            path = urlparse(self.path).path
            return path.removeprefix(f'/connect/{studio.token}') or '/'

        def do_GET(self):
            if not self.authorized():
                return self.response(dict(error='Pairing key required'), code=401)
            route = self.route()
            if route in ('/', ''):
                return self.response((ROOT / 'index.html').read_bytes(), 'text/html; charset=utf-8')
            if route == '/api/status':
                return self.response(studio.status())
            if route == '/api/latest':
                if studio.latest and Path(studio.latest).exists():
                    return self.response(Path(studio.latest).read_bytes(), 'image/png')
                return self.response(dict(error='Waiting for phone'), code=404)
            if route == '/api/qr':
                import qrcode
                qr = qrcode.make('ceilingscout://connect?link='+studio.status()['link'])
                output = io.BytesIO()
                qr.save(output, format='PNG')
                return self.response(output.getvalue(), 'image/png')
            match = re.fullmatch(r'/api/tile/(\d+)/(-?\d+)/(-?\d+)', route)
            if match:
                component, tx, ty = map(int, match.groups())
                path = studio.mapper.directory / f'component-{component}' / f'{tx}_{ty}.png'
                if path.exists():
                    return self.response(path.read_bytes(), 'image/png')
                return self.response(dict(error='Unknown tile'), code=404)
            match = re.fullmatch(r'/api/borders/(\d+)', route)
            if match:
                path = studio.mapper.directory / f'component-{int(match[1])}' / 'borders.json'
                return self.response(path.read_bytes() if path.exists() else b'[]')
            if route in ('/api/export.png', '/api/export.json'):
                component = int(dict(part.split('=',1) for part in urlparse(self.path).query.split('&') if '=' in part).get('component','0'))
                try:
                    with studio.maplock:
                        if component < 0 or component >= len(studio.mapper.components):
                            raise ValueError('Select a mapped component first')
                        if route.endswith('.json'):
                            studio.mapper.flush()
                            with studio.lock:
                                rows = studio.db.execute('SELECT id,image,metadata,sha,pose FROM frames WHERE map=? ORDER BY id',(studio.active,)).fetchall()
                            evidence = [dict(id=row[0], image=row[1], capture=json.loads(row[2]), sha256=row[3], registration=json.loads(row[4]) if row[4] else None) for row in rows]
                            return self.response(dict(schema=1, nativePixelScale=1, map=studio.active, selectedComponent=component,
                                reconstruction=studio.mapper.summary(), borders=list(studio.mapper.components[component]['borders'].values()), frames=evidence))
                        destination = studio.data / studio.active / f'map-{component}-native.png'
                        studio.mapper.components[component]['atlas'].export(destination)
                    return self.response(destination.read_bytes(), 'image/png')
                except ValueError as exc:
                    return self.response(dict(error=str(exc)), code=409)
            return self.response(dict(error='Not found'), code=404)

        def do_POST(self):
            if not self.authorized():
                return self.response(dict(error='Pairing key required'), code=401)
            try:
                self.connection.settimeout(10)
                length = int(self.headers.get('Content-Length', '0'))
                route = self.route()
                if route == '/api/new' and length == 0:
                    studio.select(str(uuid.uuid4()))
                    return self.response(dict(map=studio.active))
                if route != '/api/frame' or not 5 <= length <= LIMIT:
                    return self.response(dict(error='Invalid upload length/route'), code=413)
                raw = self.rfile.read(length)
                if len(raw) != length:
                    raise ValueError('Truncated frame')
                size = struct.unpack('>I', raw[:4])[0]
                if not 2 <= size <= 16384 or 4+size >= length:
                    raise ValueError('Invalid metadata length')
                metadata = json.loads(raw[4:4+size])
                if not isinstance(metadata, dict):
                    raise ValueError('Invalid metadata')
                result = studio.receive(metadata, raw[4+size:])
                return self.response(result, code=200 if result.get('duplicate') else 201)
            except (ValueError, KeyError, TypeError, struct.error) as exc:
                return self.response(dict(error=str(exc)), code=400)
            except OSError:
                return self.response(dict(error='Storage/connection failed; frame not acknowledged'), code=503)
    return Handler


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, default=8767)
    parser.add_argument('--data', type=Path, default=Path.home() / 'Documents' / 'Ceiling Scout Live')
    parser.add_argument('--open', action='store_true')
    args = parser.parse_args()
    config_dir = Path(os.environ.get('LOCALAPPDATA', Path.home() / '.config')) / 'Ceiling Scout Studio'
    config_dir.mkdir(parents=True, exist_ok=True)
    config = config_dir / 'connection.json'
    if config.exists():
        token = json.loads(config.read_text())['token']
    else:
        token = secrets.token_urlsafe(32)
        config.write_text(json.dumps(dict(token=token)))
    local = f'http://127.0.0.1:{args.port}/connect/{token}/'
    try:
        server = ThreadingHTTPServer(('0.0.0.0', args.port), BaseHTTPRequestHandler)
    except OSError:
        # Existing Studio uses the persisted key and fixed port.
        if args.open:
            import webbrowser
            webbrowser.open(local)
        return
    studio = Studio(args.data, token, args.port)
    server.RequestHandlerClass = make_handler(studio)
    if args.open:
        import webbrowser
        webbrowser.open(local)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        studio.close()


if __name__ == '__main__':
    main()
