import cv2
from pathlib import Path
p=Path(__file__).resolve().parents[1]/'analysis'
for name in ['clip2_56.5.png','clip1_81.5.png','clip2_49.0.png','clip2_14.0.png']:
 a=cv2.imread(str(p/name)); h,w=a.shape[:2]; b,g,r=cv2.split(a)
 mask=((r>140)&(g>135)&(b<110)&((r.astype(float)-b)>60)&((g.astype(float)-b)>60)).astype('uint8')
 mask[:int(.15*h)]=0
 num,lab,stats,cents=cv2.connectedComponentsWithStats(mask,8)
 print(name)
 for x,y,wi,he,area in stats[1:]:
  if area>12:print((x,y,wi,he,area))
cv2.imwrite(str(p/'reward-filter.png'),cv2.imread(str(p/'clip2_56.5.png'))[400:450,610:660])
