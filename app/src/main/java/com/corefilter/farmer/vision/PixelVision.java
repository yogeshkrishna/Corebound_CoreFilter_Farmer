package com.corefilter.farmer.vision;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Small, offline colour/shape measurements calibrated against the supplied gameplay.
 * This is deliberately not a Spectrum classifier. OCR and the state machine must
 * establish context before acting on an observation. All coordinates are fractions.
 */
public final class PixelVision {
    private PixelVision() {}

    public static final class Result {
        public boolean gameplay;
        public boolean controlsDetected;
        public boolean gate;
        public double gateX = -1, gateY = -1;
        public double playerX = -1, playerY = -1, playerConfidence;
        /** Nearby horizontal terrain at the visible crawler's feet/head. Confirm over time. */
        public boolean grounded, ceilingReached;
        /** Pink enemy-body candidates [left,top,right,bottom,burningConfidence,dreadnoughtConfidence].
         * Appearance alone does not identify a Spectrum or prove a kill. */
        public double[][] enemyBoxes = new double[0][];
        /** Menu geometry only. OCR or an already verified selection must establish the target. */
        public boolean playButton, selectedPanel;
        public double playX = -1, playY = -1;
        public double leftX = .165, leftY = .81, rightX = .285, rightY = .81;
        /** A round filter-like icon specifically inside the reward-ad strip. */
        public boolean filterLoot;
        public boolean adFilterLoot;
        /** Yellow video-reward frame in the bottom-right end-screen position. */
        public boolean rewardButton;
        public boolean uncertainFilterOffer;
        public int lootFilterCount;
        /** Bounding boxes [left,top,right,bottom] of unoccluded filter candidates. */
        public double[][] filterBoxes = new double[0][];
        /** Coarse spatial brightness signature; not an optical-flow estimate. */
        public double sceneSignature;
        public double motionSignature;
    }

    public static Result analyse(int[] argb, int width, int height) {
        if (argb == null || width < 1 || height < 1 ||
                (long) width * height > argb.length) {
            throw new IllegalArgumentException("A complete positive-size ARGB frame is required");
        }
        Result result = new Result();
        // Bound allocation and per-frame work independently of phone resolution.
        int step = Math.max(1, (height + 269) / 270);
        int w = (width + step - 1) / step, h = (height + step - 1) / step;
        int[] p = new int[w * h];
        for (int y = 0; y < h; y++) {
            int row = Math.min(y * step, height - 1) * width;
            for (int x = 0; x < w; x++) p[y * w + x] = argb[row + Math.min(x * step, width - 1)];
        }
        result.sceneSignature = signature(p, w, h);
        result.motionSignature = result.sceneSignature;
        if (width <= height) return result; // The supplied game uses landscape.

        int goldHud = 0, hudPixels = 0;
        for (int y = 0; y < Math.max(1, h * .13); y++) {
            for (int x = (int)(w * .035); x < w * .32; x++) {
                hudPixels++;
                if (gold(p[y * w + x])) goldHud++;
            }
        }
        // Broad enough for both the blue shield and lime health-bar footage.
        result.gameplay = hudPixels > 0 && goldHud > hudPixels * .09;
        if (result.gameplay) {
            locateControls(p,w,h,result);
            locatePlayer(p, w, h, result);
            locateGate(p, w, h, result);
            locateTerrainContacts(p, w, h, result);
            locateEnemies(p, w, h, result);
        } else {
            locatePlayPanel(p, w, h, result);
            locateFilters(p, w, h, result);
            result.rewardButton = rewardButton(p, w, h);
            if(result.rewardButton&&!result.filterLoot){int pinkCount=0,area=0;for(int y=(int)(h*.82);y<h*.95;y++)for(int x=(int)(w*.585);x<w*.83;x++){area++;if(pink(p[y*w+x]))pinkCount++;}result.uncertainFilterOffer=pinkCount>area*.024;}
        }
        return result;
    }

    private static boolean gold(int c) {
        int r=(c>>>16)&255, g=(c>>>8)&255, b=c&255;
        return r > 140 && g > 135 && b < 115 && r-b > 60 && g-b > 60;
    }

    private static boolean bodyGold(int c) {
        int r=(c>>>16)&255,g=(c>>>8)&255,b=c&255;
        return r>110&&g>110&&b<140&&r-b>40&&g-b>40;
    }

    private static boolean grey(int c){int r=(c>>>16)&255,g=(c>>>8)&255,b=c&255;return r>42&&r<210&&Math.abs(r-g)<30&&Math.abs(g-b)<45;}
    private static void locateControls(int[] p,int w,int h,Result result){
        // Recognize the two adjoining outlined movement buttons, including their divider.
        for(int top=(int)(h*.66);top<h*.82;top++)for(int left=(int)(w*.02);left<w*.28;left++){
            if(!grey(p[top*w+left]))continue;int right=left;while(right<w*.47&&grey(p[top*w+right]))right++;
            int length=right-left;if(length<w*.17||length>w*.29){left=right;continue;}
            int mid=(left+right)/2;
            for(int bottom=top+(int)(h*.14);bottom<Math.min(h*.96,top+h*.23);bottom++){
                int matches=0;for(int x=left+3;x<right-3;x++)if(grey(p[bottom*w+x]))matches++;
                if(matches<length*.82)continue;int divider=0;
                for(int y=top+4;y<bottom-4;y++){boolean hit=false;for(int x=mid-3;x<=mid+3;x++)hit|=grey(p[y*w+x]);if(hit)divider++;}
                if(divider<(bottom-top-8)*.7)continue;
                result.leftX=(left+length*.25)/w;result.rightX=(left+length*.75)/w;result.leftY=result.rightY=(top+bottom)/(2.*h);result.controlsDetected=true;return;
            }left=right;
        }
    }

    private static boolean blueEye(int c) {
        int r=(c>>>16)&255, g=(c>>>8)&255, b=c&255;
        return b > 65 && b > r*1.17 && b > g*.88 && r < 150;
    }

    private static boolean pink(int c) {
        int r=(c>>>16)&255, g=(c>>>8)&255, b=c&255;
        return r > 95 && b > 95 && r > g*1.25 && b > g*1.25;
    }

    private static boolean core(int c, int kind) {
        int r=(c>>>16)&255, g=(c>>>8)&255, b=c&255;
        if (kind == 0) return r > 80 && b > 80 && g < 65 && r > g*1.65 && b > g*1.7;
        if (kind == 1) return g > 100 && r < g*.78 && b < g*.60;
        if(kind==2)return g > 100 && r > 100 && b < g*.35;
        if(kind==3)return g>100&&b>65&&r<g*.6&&r<b*.7;
        if(kind==4)return r>145&&g>45&&g<r*.82&&b<65;
        if(kind==5)return r>145&&b>125&&g>65&&g<r*.85&&g<b*.85;
        return r>140&&g>140&&b>140&&Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b))<30;
    }

    private static int[] integral(int[] p, int w, int h, boolean gold) {
        int[] s = new int[(w+1)*(h+1)];
        for (int y=0; y<h; y++) {
            int row=0;
            for (int x=0; x<w; x++) {
                if (gold ? bodyGold(p[y*w+x]) : blueEye(p[y*w+x])) row++;
                s[(y+1)*(w+1)+x+1] = s[y*(w+1)+x+1]+row;
            }
        }
        return s;
    }

    private static int sum(int[] s, int w, int h, int x0, int y0, int x1, int y1) {
        x0=Math.max(0,Math.min(w,x0)); x1=Math.max(x0,Math.min(w,x1));
        y0=Math.max(0,Math.min(h,y0)); y1=Math.max(y0,Math.min(h,y1));
        int stride=w+1;
        return s[y1*stride+x1]-s[y0*stride+x1]-s[y1*stride+x0]+s[y0*stride+x0];
    }

    private static void locatePlayer(int[] p, int w, int h, Result result) {
        int[] g=integral(p,w,h,true), b=integral(p,w,h,false);
        int rx=Math.max(4,(int)(h*.07)), ry=Math.max(4,(int)(h*.06));
        double best=0; int bx=-1,by=-1;
        for (int y=(int)(h*.18); y<h*.86; y+=2) {
            for (int x=(int)(w*.07); x<w*.94; x+=2) {
                // The crawler's blue eyes sit above its golden feet.
                int gc=sum(g,w,h,x-rx,y-ry,x+rx+1,y+ry+1);
                int bc=sum(b,w,h,x-rx+1,y-ry/2,x+rx,y+ry/3+1);
                int smallBlue=sum(b,w,h,x-rx/3,y-ry/2,x+rx/3+1,y+ry/3+1);
                double area=(2*rx+1.)*(2*ry+1.);
                if (gc < area*.17 || bc < area*.006 || bc > area*.26 || smallBlue < 1) continue;
                // Penalize diffuse golden attack glows and blue-only projectiles.
                double score=gc/area + Math.min(.13,bc/area)*1.25;
                if (score > best) { best=score; bx=x; by=y; }
            }
        }
        if (best >= .22) {
            result.playerX=bx/(double)w; result.playerY=by/(double)h;
            result.playerConfidence=Math.min(1,best/.5);
        }
    }

    private static void locateGate(int[] p, int w, int h, Result result) {
        int bestCount=0, bestX=-1, bestMeanY=0;
        for (int x=(int)(w*.04); x<w*.96; x++) {
            int count=0, sy=0, first=-1, last=-1;
            for (int y=(int)(h*.16); y<h*.85; y++) {
                int c=p[y*w+x],r=(c>>>16)&255,g=(c>>>8)&255,b=c&255;
                if (r > 105 && r > g*2.3 && r > b*1.8) {
                    count++; sy+=y; if(first<0) first=y; last=y;
                }
            }
            if (count > h*.23 && last-first > h*.31 && count > bestCount) {
                bestCount=count; bestX=x; bestMeanY=sy/count;
            }
        }
        if (bestX>=0) {
            result.gate=true; result.gateX=bestX/(double)w; result.gateY=bestMeanY/(double)h;
        }
    }

    private static void locateTerrainContacts(int[] p, int w, int h, Result result) {
        if (result.playerConfidence < .50) return;
        int cx=(int)(result.playerX*w), cy=(int)(result.playerY*h);
        int radius=Math.max(4,(int)(h*.09));
        int minX=w,maxX=-1,minY=h,maxY=-1,count=0;
        for(int y=Math.max(0,cy-radius);y<Math.min(h,cy+radius+1);y++) {
            for(int x=Math.max(0,cx-radius);x<Math.min(w,cx+radius+1);x++) {
                if(!bodyGold(p[y*w+x]))continue;
                minX=Math.min(minX,x);maxX=Math.max(maxX,x);
                minY=Math.min(minY,y);maxY=Math.max(maxY,y);count++;
            }
        }
        // Restrict the search to the crawler-sized region around its blue eyes.
        // Do not infer contact from the much larger Ember aura.
        if(count<h*h*.0015 || maxX-minX<h*.06 || maxY-minY<h*.06)return;
        int gap=Math.max(2,(int)(h*.018));
        // The crawler has two feet separated by an empty centre. Narrow centre-only
        // checks miss landings on the level's split/slotted platform tiles.
        result.grounded=horizontalTerrain(p,w,h,minX+1,maxX-1,maxY+1,maxY+gap+1,false);
        result.ceilingReached=horizontalTerrain(p,w,h,minX+1,maxX-1,minY-gap,minY,true);
    }

    private static boolean horizontalTerrain(int[] p,int w,int h,int left,int right,int top,int bottom,boolean ceiling) {
        left=Math.max(0,left);right=Math.min(w-1,right);
        if(right-left<4)return false;
        int adjacentRows=0;
        for(int y=Math.max(1,top);y<Math.min(h-1,bottom);y++) {
            int matched=0, longest=0, run=0;
            for(int x=left;x<=right;x++) {
                if(grey(p[y*w+x])) {matched++;longest=Math.max(longest,++run);} else run=0;
            }
            double coverage=ceiling?.72:.60, uninterrupted=ceiling?.5:.30;
            if(matched>=(right-left+1)*coverage && longest>=(right-left+1)*uninterrupted) {
                if(++adjacentRows>=2)return true;
            } else adjacentRows=0;
        }
        return false;
    }

    private static boolean enemyPink(int c) {
        int r=(c>>>16)&255,g=(c>>>8)&255,b=c&255;
        return r>60 && b>65 && r>g*1.35 && b>g*1.4 && b>r*.55;
    }

    private static boolean redCore(int c) {
        int r=(c>>>16)&255,g=(c>>>8)&255,b=c&255;
        return r>70 && r>g*1.8 && r>b*1.3 && g<110;
    }

    /** Compact connected coloured areas. The scan is capped by the existing 270px sampler. */
    private static List<int[]> components(int[] p,int w,int h,int kind,int left,int top,int right,int bottom) {
        boolean[] seen=new boolean[p.length];int[] queue=new int[p.length];
        List<int[]> boxes=new ArrayList<>();
        for(int y=top;y<bottom;y++)for(int x=left;x<right;x++) {
            int start=y*w+x;
            if(seen[start]||!componentColour(p[start],kind))continue;
            int head=0,tail=1;queue[0]=start;seen[start]=true;
            int minX=x,maxX=x,minY=y,maxY=y;
            while(head<tail) {
                int at=queue[head++],cx=at%w,cy=at/w;
                minX=Math.min(minX,cx);maxX=Math.max(maxX,cx);minY=Math.min(minY,cy);maxY=Math.max(maxY,cy);
                for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++) {
                    int nx=cx+dx,ny=cy+dy;
                    if(nx<left||ny<top||nx>=right||ny>=bottom)continue;
                    int next=ny*w+nx;
                    if(!seen[next]&&componentColour(p[next],kind)){seen[next]=true;queue[tail++]=next;}
                }
            }
            if(tail>=4)boxes.add(new int[]{minX,minY,maxX+1,maxY+1,tail});
        }
        return boxes;
    }

    private static boolean componentColour(int c,int kind) {
        if(kind==0)return enemyPink(c);
        int r=(c>>>16)&255,g=(c>>>8)&255,b=c&255;
        if(kind==1)return g>145 && r<g*.8 && b<g*.32;
        return r>125 && b>125 && g<90 && r>g*1.8 && b>g*1.8;
    }

    private static void locateEnemies(int[] p,int w,int h,Result result) {
        List<int[]> parts=components(p,w,h,0,(int)(w*.025),(int)(h*.15),(int)(w*.975),(int)(h*.87));
        int mergeGap=Math.max(2,(int)(h*.055));
        // Two pink limbs from one flying body can be separated by its grey armour.
        for(int i=0;i<parts.size();i++)for(int j=i+1;j<parts.size();j++) {
            int[] a=parts.get(i),b=parts.get(j);
            int gapX=Math.max(0,Math.max(a[0],b[0])-Math.min(a[2],b[2]));
            int gapY=Math.max(0,Math.max(a[1],b[1])-Math.min(a[3],b[3]));
            int mergedWidth=Math.max(a[2],b[2])-Math.min(a[0],b[0]);
            int mergedHeight=Math.max(a[3],b[3])-Math.min(a[1],b[1]);
            if(gapX<=mergeGap && gapY<=mergeGap && mergedWidth<h*.30 && mergedHeight<h*.30) {
                a[0]=Math.min(a[0],b[0]);a[1]=Math.min(a[1],b[1]);a[2]=Math.max(a[2],b[2]);a[3]=Math.max(a[3],b[3]);a[4]+=b[4];
                parts.remove(j--);
            }
        }
        List<double[]> enemies=new ArrayList<>();
        for(int[] box:parts) {
            int bw=box[2]-box[0],bh=box[3]-box[1];
            if(bw<h*.035||bh<h*.035||bw>h*.30||bh>h*.30||bw>bh*3||bh>bw*3||box[4]<h*h*.00045)continue;
            int margin=Math.max(3,(int)(h*.055));
            int left=Math.max(0,box[0]-margin),right=Math.min(w,box[2]+margin);
            int top=Math.max((int)(h*.14),box[1]-margin),bottom=Math.min(h,box[3]+margin);
            int coreCount=0,coreRows=0,armour=0;
            for(int y=top;y<bottom;y++) {
                int rowRed=0;
                for(int x=left;x<right;x++) {
                    int c=p[y*w+x],r=(c>>>16)&255,g=(c>>>8)&255,b=c&255;
                    if(redCore(c)){rowRed++;coreCount++;}
                    if(r>25&&g>28&&b>40&&b>g*.98&&g>r*.88&&r<155&&g<165&&b<190)armour++;
                }
                if(rowRed>=Math.max(2,(int)(h*.014)))coreRows++;
            }
            // The red eye/core and nearby cool armour reject purple direction arrows,
            // thin red lasers, blue allied orbs, and the red exit barrier.
            if(coreCount<h*h*.0004||coreRows<h*.016||armour<bw*bh*.12)continue;
            double centerX=(box[0]+box[2])/(2.*w),centerY=(box[1]+box[3])/(2.*h);
            if(result.playerConfidence>.5&&Math.abs(centerX-result.playerX)*w<h*.095&&Math.abs(centerY-result.playerY)*h<h*.095)continue;
            // A golden aura is not proof the target is burning, and body size does not
            // establish Dreadnought identity. Leave these confidences unknown (zero).
            enemies.add(new double[]{box[0]/(double)w,box[1]/(double)h,box[2]/(double)w,box[3]/(double)h,0,0});
        }
        result.enemyBoxes=enemies.toArray(new double[0][]);
    }

    private static boolean white(int c) {
        int r=(c>>>16)&255,g=(c>>>8)&255,b=c&255;
        return r>195&&g>195&&b>195&&Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b))<38;
    }

    private static void locatePlayPanel(int[] p,int w,int h,Result result) {
        List<int[]> greens=components(p,w,h,1,(int)(w*.52),(int)(h*.56),(int)(w*.93),(int)(h*.90));
        for(int[] box:greens) {
            int bw=box[2]-box[0],bh=box[3]-box[1],whiteCount=0;
            if(bw<w*.095||bw>w*.30||bh<h*.045||bh>h*.17||box[4]<bw*bh*.48)continue;
            for(int y=box[1];y<box[3];y++)for(int x=box[0];x<box[2];x++)if(white(p[y*w+x]))whiteCount++;
            if(whiteCount<bw*bh*.06)continue;
            result.playButton=true;result.playX=(box[0]+box[2])/(2.*w);result.playY=(box[1]+box[3])/(2.*h);break;
        }
        if(!result.playButton)return;
        boolean edit=false;
        for(int[] box:components(p,w,h,2,(int)(w*.52),(int)(h*.22),(int)(w*.90),(int)(h*.64))) {
            int bw=box[2]-box[0],bh=box[3]-box[1];
            if(bw>w*.065&&bw<w*.23&&bh>h*.04&&bh<h*.16&&box[4]>bw*bh*.45){edit=true;break;}
        }
        if(!edit)return;
        // A selected level is outlined on the left. This does not read its name/star tier.
        int outlinedRows=0;
        for(int y=(int)(h*.01);y<h*.78;y++) {
            int n=0;for(int x=(int)(w*.04);x<w*.39;x++)if(white(p[y*w+x]))n++;
            if(n>w*.22)outlinedRows++;
        }
        result.selectedPanel=outlinedRows>=Math.max(2,(int)(h*.010));
    }

    private static void locateFilters(int[] p, int w, int h, Result result) {
        List<double[]> boxes=new ArrayList<>();
        int[] queue=new int[w*h]; boolean[] seen=new boolean[w*h];
        for (int kind=0; kind<7; kind++) {
            Arrays.fill(seen,false);
            for (int y=(int)(h*.58); y<h*.97; y++) {
                for (int x=(int)(w*.17); x<w*.89; x++) {
                    int start=y*w+x;
                    if (seen[start] || !core(p[start],kind)) continue;
                    int head=0,tail=1;queue[0]=start;seen[start]=true;
                    int minX=x,maxX=x,minY=y,maxY=y;
                    while(head<tail) {
                        int at=queue[head++],cx=at%w,cy=at/w;
                        minX=Math.min(minX,cx);maxX=Math.max(maxX,cx);
                        minY=Math.min(minY,cy);maxY=Math.max(maxY,cy);
                        for(int dy=-1;dy<=1;dy++) for(int dx=-1;dx<=1;dx++) {
                            int nx=cx+dx,ny=cy+dy;
                            if(nx<0||ny<0||nx>=w||ny>=h)continue;
                            int next=ny*w+nx;
                            if(!seen[next]&&core(p[next],kind)) {
                                seen[next]=true;queue[tail++]=next;
                            }
                        }
                    }
                    int bw=maxX-minX+1,bh=maxY-minY+1;
                    double aspect=bw/(double)bh,fill=tail/(double)(bw*bh);
                    if (bw<h*.040 || bh<h*.040 || bw>h*.14 || bh>h*.14 ||
                            aspect<.78 || aspect>1.25 || fill<.60 || fill>.95) continue;
                    // Core filters are round coloured objects inside a pink rarity frame.
                    // This rejects the similarly yellow video/play button and green cards.
                    int margin=Math.max(2,(int)(Math.max(bw,bh)*.50)),rim=0,rimTotal=0;
                    for(int yy=Math.max(0,minY-margin);yy<=Math.min(h-1,maxY+margin);yy++) {
                        for(int xx=Math.max(0,minX-margin);xx<=Math.min(w-1,maxX+margin);xx++) {
                            if(xx>=minX&&xx<=maxX&&yy>=minY&&yy<=maxY)continue;
                            rimTotal++;if(pink(p[yy*w+xx]))rim++;
                        }
                    }
                    if(rimTotal==0 || rim<rimTotal*.10)continue;
                    double[] box={minX/(double)w,minY/(double)h,(maxX+1.)/w,(maxY+1.)/h};
                    boxes.add(box);
                    if((minX+maxX)/(2.*w)>.53 && (minY+maxY)/(2.*h)>.80) {
                        result.filterLoot=true;result.adFilterLoot=true;
                    } else result.lootFilterCount++;
                }
            }
        }
        result.filterBoxes=boxes.toArray(new double[0][]);
    }

    private static double signature(int[] p,int w,int h) {
        double sum=0,weights=0;
        for(int gy=0;gy<12;gy++)for(int gx=0;gx<24;gx++) {
            int x=Math.min(w-1,(int)((.06+gx*.037)*w));
            int y=Math.min(h-1,(int)((.15+gy*.050)*h));
            int c=p[y*w+x];
            double luminance=(((c>>>16)&255)*.2126+((c>>>8)&255)*.7152+(c&255)*.0722)/255.;
            double weight=1+(gx*7+gy*3)%11*.1;
            sum+=luminance*weight;weights+=weight;
        }
        return sum/weights;
    }

    private static boolean rewardButton(int[] p,int w,int h) {
        int longRows=0;
        for(int y=(int)(h*.80);y<h*.97;y++) {
            int n=0;
            for(int x=(int)(w*.515);x<w*.84;x++)if(gold(p[y*w+x]))n++;
            if(n>w*.20)longRows++;
        }
        int playGold=0,playArea=0;
        for(int y=(int)(h*.825);y<h*.945;y++)for(int x=(int)(w*.53);x<w*.575;x++) {
            playArea++;if(gold(p[y*w+x]))playGold++;
        }
        return longRows>=Math.max(2,(int)(h*.012)) && playGold>playArea*.24;
    }
}
