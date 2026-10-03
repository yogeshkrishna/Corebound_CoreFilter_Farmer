import cv2
from pathlib import Path
from PIL import Image,ImageDraw
root=Path(__file__).resolve().parents[1];paths=[Path.home()/'Downloads'/'WhatsApp Video 2026-10-03 at 9.46.55 AM.mp4',Path.home()/'Downloads'/'WhatsApp Video 2026-10-03 at 9.59.32 AM.mp4']
for n,times in [(1,[18,19,20,21,22,23,24,25]),(2,[5,6,7,8,9,10,11,12,28,29,30,31,32,33,34,35,40,41,42,43,44,45,46,47])]:
 cap=cv2.VideoCapture(str(paths[n-1]));sheet=Image.new('RGB',(4*521,((len(times)+3)//4)*260),'#171923');draw=ImageDraw.Draw(sheet)
 for i,t in enumerate(times):
  cap.set(cv2.CAP_PROP_POS_MSEC,t*1000);ok,f=cap.read()
  if not ok:continue
  im=Image.fromarray(cv2.cvtColor(f,cv2.COLOR_BGR2RGB));im.thumbnail((521,240));x=i%4*521;y=i//4*260;sheet.paste(im,(x,y+20));draw.text((x+8,y+3),f'{n} | {t}s',fill='white')
 sheet.save(root/'analysis'/f'video_{n}_encounters.jpg',quality=94)
