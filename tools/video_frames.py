from pathlib import Path
import cv2
from PIL import Image, ImageDraw
root=Path(__file__).resolve().parents[1]; out=root/'analysis'
paths=[Path.home()/'Downloads'/'WhatsApp Video 2026-10-03 at 9.46.55 AM.mp4',Path.home()/'Downloads'/'WhatsApp Video 2026-10-03 at 9.59.32 AM.mp4']
for n,times,label in [(1,[5,8,11,15,20,25,30,35,40,45,50,55,60,65,70,75],'movement'),(2,[1,2,3,4,6,8,12,14,16,18,21,24,26,29,31,34,37,39,42,44,46,49,51,53],'movement'),(2,[55,55.5,56,56.5,57,57.5,58,58.5,59,59.5,60,60.5],'end'),(1,[80,80.5,81,81.5,82,82.5,83,83.5],'end')]:
 cap=cv2.VideoCapture(str(paths[n-1])); rows=(len(times)+3)//4; sheet=Image.new('RGB',(4*521,rows*260),'#171923'); draw=ImageDraw.Draw(sheet)
 for i,t in enumerate(times):
  cap.set(cv2.CAP_PROP_POS_MSEC,t*1000); ok,f=cap.read()
  if not ok: continue
  im=Image.fromarray(cv2.cvtColor(f,cv2.COLOR_BGR2RGB)); im.thumbnail((521,240)); x=(i%4)*521;y=(i//4)*260;sheet.paste(im,(x,y+20));draw.text((x+8,y+3),f'Clip {n} | {t:.1f}s',fill='white')
  cv2.imwrite(str(out/f'clip{n}_{t:.1f}.png'),f)
 sheet.save(out/f'video_{n}_{label}.jpg',quality=94)
 cap.release()
