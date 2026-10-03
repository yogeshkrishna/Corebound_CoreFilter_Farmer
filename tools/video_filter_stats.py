import cv2,numpy as np
from pathlib import Path
p=Path(__file__).resolve().parents[1]/'analysis'
for name in ['clip2_56.5.png','clip1_81.5.png','clip2_58.png']:
 a=cv2.imread(str(p/name));h,w=a.shape[:2];b,g,r=cv2.split(a)
 masks=[((r>80)&(b>80)&(g<65)&(r.astype(float)>g*1.65)&(b.astype(float)>g*1.7)),((g>100)&(r.astype(float)<g*.78)&(b.astype(float)<g*.6)),((g>100)&(r>100)&(b.astype(float)<g*.35))]
 print(name)
 for mi,ma in enumerate(masks):
  ma[:int(.58*h)]=0;ma[:,int(.86*w):]=0;ma[:,:int(.18*w)]=0
  nu,la,ss,cc=cv2.connectedComponentsWithStats(ma.astype('uint8'),8)
  for x,y,wi,he,area in ss[1:]:
   if area>20:print(mi,(x,y,wi,he,area,round(area/(wi*he),2)))
