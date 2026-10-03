from pathlib import Path
import cv2, json
from PIL import Image, ImageDraw

ROOT=Path(__file__).resolve().parents[1]
clips=[Path.home()/'Downloads'/'WhatsApp Video 2026-10-03 at 9.46.55 AM.mp4',Path.home()/'Downloads'/'WhatsApp Video 2026-10-03 at 9.59.32 AM.mp4']
out=ROOT/'analysis'; out.mkdir(exist_ok=True)
meta=[]
for n,path in enumerate(clips,1):
    cap=cv2.VideoCapture(str(path)); fps=cap.get(cv2.CAP_PROP_FPS); count=cap.get(cv2.CAP_PROP_FRAME_COUNT); dur=count/fps
    info=dict(clip=n,file=path.name,fps=fps,frames=count,duration=dur,width=cap.get(3),height=cap.get(4)); meta.append(info)
    times=list(range(0,int(dur),5))
    for page,start in enumerate(range(0,len(times),24)):
        sheet=Image.new('RGB',(4*480,6*246),'#151923'); draw=ImageDraw.Draw(sheet)
        for i,t in enumerate(times[start:start+24]):
            cap.set(cv2.CAP_PROP_POS_MSEC,t*1000); ok,frame=cap.read()
            if not ok: continue
            im=Image.fromarray(cv2.cvtColor(frame,cv2.COLOR_BGR2RGB)); im.thumbnail((480,220))
            x=(i%4)*480; y=(i//4)*246; sheet.paste(im,(x,y+24)); draw.text((x+8,y+5),f'Clip {n} | {t:.0f}s',fill='white')
        sheet.save(out/f'clip{n}_sheet{page}.jpg',quality=90)
    cap.release()
(out/'video_metadata.json').write_text(json.dumps(meta,indent=2))
print(json.dumps(meta,indent=2))
