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
        public double playerLeft=-1, playerTop=-1, playerRight=-1, playerBottom=-1;
        /** Nearby horizontal terrain at the visible crawler's feet/head. Confirm over time. */
        public boolean grounded, ceilingReached;
        /** Weak foot-colour evidence under effects. TemporalVision must confirm
         * a stable registered support before this can replenish any jumps. */
        public boolean groundContactCandidate;
        public boolean wallLeft, wallRight;
        /** Conservative foreground occupancy. Unknown observations never erase the map. */
        public int terrainCols=48, terrainRows=24;
        public byte[] terrainCells=new byte[48*24];
        /** Internal registration support sampled from verified rectangular foreground only.
         * It is deliberately separate from free/dark pixels and from map occupancy. */
        final boolean[] registrationForeground=new boolean[120*56];
        /** Camera translation belongs to TemporalVision, not brightness signatures. */
        public double cameraDx, cameraDy, cameraX, cameraY, cameraConfidence;
        public int registrationEpoch;
        public boolean registrationReset, registrationLost, sceneChanged;
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
        // The floating toolbar can cover the health bar. The two adjoining movement
        // outlines are independent evidence of gameplay and must be checked first.
        locateControls(p,w,h,result);
        result.gameplay = (hudPixels > 0 && goldHud > hudPixels * .09) || result.controlsDetected;
        if (result.gameplay) {
            locatePlayer(p, w, h, result);
            locateGate(p, w, h, result);
            refinePlayerBounds(p, w, h, result);
            locateEnemies(p, w, h, result);
            TerrainEvidence terrain=locateTerrain(p,w,h,result);
            locateTerrainContacts(terrain,w,h,result);
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
        // Texture can join a button's border, or compression can break its top
        // line. Match all three vertical edges and both horizontal edges together
        // rather than requiring one uninterrupted scanline.
        for(int top=(int)(h*.68);top<h*.78;top++)for(int mid=(int)(w*.20);mid<w*.30;mid++) {
            if(!grey(p[top*w+mid]))continue;
            for(int half=(int)(w*.10);half<w*.14;half+=2)for(int bottom=top+(int)(h*.16);bottom<Math.min(h*.95,top+h*.22);bottom+=2) {
                int left=mid-half,right=mid+half;
                int edgeHeight=bottom-top,divider=0,outer=0,horizontal=0;
                for(int y=top+3;y<bottom-3;y++) {
                    if(grey(p[y*w+mid])||grey(p[y*w+mid+1]))divider++;
                    if(grey(p[y*w+left])||grey(p[y*w+left+1]))outer++;
                    if(grey(p[y*w+right])||grey(p[y*w+right-1]))outer++;
                }
                if(divider<edgeHeight*.67||outer<edgeHeight*1.15)continue;
                for(int x=left+4;x<right-4;x++){if(grey(p[top*w+x]))horizontal++;if(grey(p[bottom*w+x]))horizontal++;}
                if(horizontal<(right-left-8)*1.35)continue;
                result.leftX=(left+mid)/(2.*w);result.rightX=(mid+right)/(2.*w);
                result.leftY=result.rightY=(top+bottom)/(2.*h);result.controlsDetected=true;return;
            }
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
            result.playerLeft=Math.max(0,(bx-rx)/(double)w);
            result.playerRight=Math.min(1,(bx+rx+1)/(double)w);
            result.playerTop=Math.max(0,(by-ry*.70)/h);
            result.playerBottom=Math.min(1,(by+ry*1.05)/h);
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

    private static void refinePlayerBounds(int[] p, int w, int h, Result result) {
        if (result.playerConfidence < .50) return;
        int cx=(int)(result.playerX*w), cy=(int)(result.playerY*h);
        int radius=Math.max(4,(int)(h*.075));
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
        result.playerLeft=minX/(double)w;result.playerRight=(maxX+1.)/w;
        result.playerTop=minY/(double)h;result.playerBottom=(maxY+1.)/h;
    }

    private static boolean terrainGrey(int c) {
        int r=(c>>>16)&255,g=(c>>>8)&255,b=c&255;
        return r>36&&r<150&&g>35&&g<160&&b>39&&b<180&&Math.abs(r-g)<18&&b-g<35&&g-b<16;
    }

    private static boolean masked(double x,double y,Result result) {
        return y<.14||y>.87||(x<.40&&y>.69)||
            (result.playerConfidence>.35&&x>result.playerLeft-.014&&x<result.playerRight+.014&&
                y>result.playerTop-.018&&y<result.playerBottom+.018);
    }

    private static final class TerrainEvidence {
        final boolean[] solid,contactCandidate;
        TerrainEvidence(int size){solid=new boolean[size];contactCandidate=new boolean[size];}
    }

    private static boolean darkFree(int c) {
        int r=(c>>>16)&255,g=(c>>>8)&255,b=c&255;
        return Math.max(r,Math.max(g,b))<37&&Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b))<25;
    }

    private static boolean boundaryFree(int c) {
        // Ember and other effects illuminate the cavity around a tile. Dim
        // coloured light is free boundary evidence; neutral rock is not.
        int r=(c>>>16)&255,g=(c>>>8)&255,b=c&255;
        return darkFree(c)||(!terrainGrey(c)&&Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b))>16);
    }

    private static boolean geometryMasked(double x,double y,Result r) {
        // A picture edge has no observed free side and must never become a wall.
        if(x<.018||x>.982||y<.14||y>.87||(x<.40&&y>.69))return true;
        if(r.playerConfidence>.35&&x>r.playerLeft&&x<r.playerRight&&y>r.playerTop&&y<r.playerBottom)return true;
        for(double[] b:r.enemyBoxes)if(x>b[0]&&x<b[2]&&y>b[1]&&y<b[3])return true;
        return false;
    }

    /** Grey colour is merely a candidate. A component must present long, flat
     * axis-aligned edges with a dark free side, and most of its exposed silhouette
     * must consist of those runs. Jagged/triangular decorations fail that ratio.
     * Two-pixel platforms can pass because their two long faces remain rectangles.
     */
    private static TerrainEvidence locateTerrain(int[] p,int w,int h,Result result) {
        TerrainEvidence evidence=new TerrainEvidence(p.length);
        boolean[] candidate=new boolean[p.length];int[] ids=new int[p.length],queue=new int[p.length];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)
            candidate[y*w+x]=!geometryMasked((x+.5)/w,(y+.5)/h,result)&&terrainGrey(p[y*w+x]);
        // Tile texture can contain tiny dark holes. Close only holes supported
        // on both opposite axes; do not flatten a jagged external silhouette.
        boolean[] original=candidate.clone();
        for(int y=2;y<h-2;y++)for(int x=2;x<w-2;x++)if(!original[y*w+x]&&!geometryMasked((x+.5)/w,(y+.5)/h,result)) {
            boolean lr=(original[y*w+x-1]||original[y*w+x-2])&&(original[y*w+x+1]||original[y*w+x+2]);
            boolean ud=(original[(y-1)*w+x]||original[(y-2)*w+x])&&(original[(y+1)*w+x]||original[(y+2)*w+x]);
            if(lr&&ud)candidate[y*w+x]=true;
        }
        System.arraycopy(candidate,0,evidence.contactCandidate,0,candidate.length);
        int id=0,minRun=Math.max(7,(int)Math.ceil(h*.035));
        for(int sy=1;sy<h-1;sy++)for(int sx=1;sx<w-1;sx++) {
            int start=sy*w+sx;if(!candidate[start]||ids[start]!=0)continue;
            id++;int head=0,tail=1,minX=sx,maxX=sx,minY=sy,maxY=sy;queue[0]=start;ids[start]=id;
            while(head<tail) {
                int at=queue[head++],x=at%w,y=at/w;
                minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);
                for(int k=0;k<4;k++) {
                    int d=k==0?-1:k==1?1:k==2?-w:w;
                    int n=at+d;if(n<0||n>=p.length||Math.abs(n%w-x)>1||!candidate[n]||ids[n]!=0)continue;
                    ids[n]=id;queue[tail++]=n;
                }
            }
            if(tail<minRun*2||Math.max(maxX-minX+1,maxY-minY+1)<minRun)continue;
            boolean[] exterior=exterior(ids,id,w,h,minX,maxX,minY,maxY);
            int exposed=0,verified=0,horizontal=0,vertical=0,longestVertical=0,faceSupport=0,faceX=0,faceSide=0;
            int[][] verticalSupport=new int[2][maxX-minX+1];
            // Exposed edges adjoining dark pixels include diagonal tooth steps, so
            // many tiny orthogonal raster segments cannot masquerade as a rectangle.
            for(int i=0;i<tail;i++) {
                int at=queue[i],x=at%w,y=at/w;
                if(x>0&&exterior[at-1]&&boundaryFree(p[at-1]))exposed++;
                if(x<w-1&&exterior[at+1]&&boundaryFree(p[at+1]))exposed++;
                if(y>0&&exterior[at-w]&&boundaryFree(p[at-w]))exposed++;
                if(y<h-1&&exterior[at+w]&&boundaryFree(p[at+w]))exposed++;
            }
            for(int side:new int[]{-1,1}) {
                for(int y=minY;y<=maxY;y++) {
                    int run=0;
                    for(int x=minX;x<=maxX+1;x++) {
                        boolean edge=x<=maxX&&y+side>=0&&y+side<h&&exterior[(y+side)*w+x]&&flatEdge(p,ids,id,w,h,x,y,0,side);
                        if(edge)run++;
                        else {if(run>=minRun){verified+=run;horizontal+=run;}run=0;}
                    }
                }
                for(int x=minX;x<=maxX;x++) {
                    int run=0,support=0,longest=0;
                    for(int y=minY;y<=maxY+1;y++) {
                        boolean edge=y<=maxY&&x+side>=0&&x+side<w&&exterior[y*w+x+side]&&flatEdge(p,ids,id,w,h,x,y,side,0);
                        if(edge)run++;
                        else {if(run>=minRun){verified+=run;vertical+=run;support+=run;longest=Math.max(longest,run);}run=0;}
                    }
                    verticalSupport[(side+1)/2][x-minX]=support;
                    if(support>faceSupport){faceSupport=support;longestVertical=longest;faceX=x;faceSide=side;}
                }
            }
            int bw=maxX-minX+1,bh=maxY-minY+1;
            // A tall wall clipped by the HUD can expose only one straight face.
            // Texture/shadow on its far side need not form a second silhouette.
            // Keep only the rock behind that measured face, never the coloured
            // halo in front. Short/jagged decorative strips cannot use this path.
            int nearbyFaceSupport=faceSupport;
            for(int dx:new int[]{-1,1})if(faceX+dx>=minX&&faceX+dx<=maxX)
                nearbyFaceSupport+=verticalSupport[(faceSide+1)/2][faceX+dx-minX];
            boolean tallFace=bh>=h*.4&&bw>=Math.max(h*.04,w*.025)&&bw<bh*.25&&longestVertical>=minRun*2&&faceSupport>=bh*.25&&nearbyFaceSupport>=bh*.45&&
                    (minY<=h*.14+1||maxY>=h*.87-1)&&vertical>horizontal*2;
            boolean orthogonalFaces=(horizontal>=minRun*3&&vertical>=minRun||vertical>=minRun*3&&horizontal>=minRun)&&verified>=exposed*.24;
            if(exposed<minRun*2||verified<minRun*2||(verified<exposed*.69&&!tallFace&&!orthogonalFaces))continue;
            // A one-sided jagged strip with only a flat base is decoration. A thin
            // real platform needs both long faces; a staircase needs both axes.
            boolean clippedHorizontal=horizontal>=minRun&&bh>=h*.04&&(minY<=h*.14+1||maxY>=h*.87-1);
            boolean clippedVertical=vertical>=minRun&&bw>=Math.max(h*.04,w*.035)&&(minX<=w*.018+1||maxX>=w*.982-1);
            if((horizontal<minRun||vertical<minRun)&&verified<2*Math.max(bw,bh)*.72&&!clippedHorizontal&&!clippedVertical&&!tallFace)continue;
            for(int i=0;i<tail;i++)if(!tallFace||(queue[i]%w-faceX)*faceSide<=0)evidence.solid[queue[i]]=true;
        }
        for(int gy=0;gy<result.terrainRows;gy++)for(int gx=0;gx<result.terrainCols;gx++) {
            double nx=(gx+.5)/result.terrainCols,ny=(gy+.5)/result.terrainRows;
            if(masked(nx,ny,result))continue;
            int x0=gx*w/result.terrainCols,x1=(gx+1)*w/result.terrainCols;
            int y0=gy*h/result.terrainRows,y1=(gy+1)*h/result.terrainRows;
            int solid=0,free=0,total=0,longestRow=0,longestColumn=0;
            for(int y=y0;y<y1;y++) {
                int run=0;
                for(int x=x0;x<x1;x++) {
                    total++;
                    if(evidence.solid[y*w+x]){solid++;longestRow=Math.max(longestRow,++run);}else run=0;
                    if(darkFree(p[y*w+x]))free++;
                }
            }
            for(int x=x0;x<x1;x++){int run=0;for(int y=y0;y<y1;y++){if(evidence.solid[y*w+x])longestColumn=Math.max(longestColumn,++run);else run=0;}}
            if(solid>=Math.max(2,total*.15)&&(longestRow>=(x1-x0)*.55||longestColumn>=(y1-y0)*.55))result.terrainCells[gy*result.terrainCols+gx]=2;
            else if(free>total*.94)result.terrainCells[gy*result.terrainCols+gx]=1;
        }
        for(int y=0;y<56;y++)for(int x=0;x<120;x++) {
            int px=Math.min(w-1,(int)((x+.5)*w/120)),py=Math.min(h-1,(int)((y+.5)*h/56));
            result.registrationForeground[y*120+x]=evidence.solid[py*w+px];
        }
        return evidence;
    }
    private static boolean[] exterior(int[] ids,int id,int w,int h,int minX,int maxX,int minY,int maxY) {
        int l=Math.max(0,minX-1),r=Math.min(w-1,maxX+1),t=Math.max(0,minY-1),b=Math.min(h-1,maxY+1);
        boolean[] outside=new boolean[ids.length];int[] q=new int[(r-l+1)*(b-t+1)];int head=0,tail=0;
        for(int yy=t;yy<=b;yy++)for(int xx=l;xx<=r;xx++)if((yy==t||yy==b||xx==l||xx==r)&&ids[yy*w+xx]!=id){outside[yy*w+xx]=true;q[tail++]=yy*w+xx;}
        while(head<tail){int at=q[head++],xx=at%w,yy=at/w;for(int k=0;k<4;k++){
            int nx=xx+(k==0?-1:k==1?1:0),ny=yy+(k==2?-1:k==3?1:0);if(nx<l||nx>r||ny<t||ny>b)continue;
            int n=ny*w+nx;if(!outside[n]&&ids[n]!=id){outside[n]=true;q[tail++]=n;}
        }}return outside;
    }

    private static boolean flatEdge(int[] p,int[] ids,int id,int w,int h,int x,int y,int dx,int dy) {
        if(ids[y*w+x]!=id)return false;
        int outsideX=x+dx*2,outsideY=y+dy*2,insideX=x-dx,insideY=y-dy;
        if(outsideX<1||outsideX>=w-1||outsideY<1||outsideY>=h-1||insideX<0||insideX>=w||insideY<0||insideY>=h)return false;
        return ids[insideY*w+insideX]==id&&boundaryFree(p[(y+dy)*w+x+dx])&&boundaryFree(p[outsideY*w+outsideX]);
    }

    private static void locateTerrainContacts(TerrainEvidence e,int w,int h,Result r) {
        if(r.playerConfidence<.50)return;
        int left=Math.max(1,(int)(r.playerLeft*w)),right=Math.min(w-2,(int)(r.playerRight*w)-1);
        int top=Math.max(1,(int)(r.playerTop*h)),bottom=Math.min(h-2,(int)(r.playerBottom*h)-1);
        int gap=Math.max(2,(int)(h*.018));
        r.grounded=horizontalContact(e.solid,w,h,left+1,right-1,bottom+1,bottom+gap+1,.50);
        r.groundContactCandidate=r.grounded||horizontalContact(e.contactCandidate,w,h,left+1,right-1,bottom+1,bottom+gap+1,.50);
        r.ceilingReached=horizontalContact(e.solid,w,h,left+1,right-1,top-gap,top,.65);
        r.wallLeft=verticalContact(e.solid,w,h,left-gap,left,top+2,bottom-2);
        r.wallRight=verticalContact(e.solid,w,h,right+1,right+gap+1,top+2,bottom-2);
    }

    private static boolean horizontalContact(boolean[] solid,int w,int h,int left,int right,int top,int bottom,double coverage) {
        if(right-left<4)return false;
        for(int y=Math.max(1,top);y<Math.min(h-1,bottom);y++) {
            int count=0;for(int x=Math.max(1,left);x<=Math.min(w-2,right);x++)if(solid[y*w+x])count++;
            if(count>=(right-left+1)*coverage)return true;
        }
        return false;
    }
    private static boolean verticalContact(boolean[] solid,int w,int h,int left,int right,int top,int bottom) {
        if(bottom-top<5)return false;
        for(int x=Math.max(2,left);x<Math.min(w-2,right);x++) {
            int count=0;for(int y=Math.max(1,top);y<Math.min(h-1,bottom);y++)if(solid[y*w+x])count++;
            if(count>=(bottom-top)*.65)return true;
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
        if(kind==3)return redCore(c);
        int r=(c>>>16)&255,g=(c>>>8)&255,b=c&255;
        if(kind==4)return r>30&&g>45&&b>60&&b<215&&g<200&&b-r>23&&g>r*.95&&b>g*1.035;
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
        // Ground bots can have a blue/grey body and a red core, without any pink
        // limbs. Find the compact core first, then require armour around it. A red
        // gate, a thin laser, and loose Ember sparks do not meet this geometry.
        for(int[] core:components(p,w,h,3,(int)(w*.025),(int)(h*.15),(int)(w*.975),(int)(h*.87))) {
            int cw=core[2]-core[0],ch=core[3]-core[1];
            if(cw<h*.016||ch<h*.016||cw>h*.09||ch>h*.09||cw>ch*2.8||ch>cw*2.8||core[4]<h*h*.00030)continue;
            int cx=(core[0]+core[2])/2,cy=(core[1]+core[3])/2,margin=Math.max(4,(int)(h*.048));
            int left=Math.max(0,cx-margin),right=Math.min(w,cx+margin+1);
            int top=Math.max((int)(h*.14),cy-margin),bottom=Math.min((int)(h*.87),cy+margin+1);
            int armour=0,minX=right,maxX=left,minY=bottom,maxY=top;
            for(int y=top;y<bottom;y++)for(int x=left;x<right;x++) {
                int c=p[y*w+x],r=(c>>>16)&255,g=(c>>>8)&255,b=c&255;
                if(r>35&&g>48&&b>65&&b>r*1.12&&g>r*.92&&r<170&&g<195&&b<225) {
                    armour++;minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);
                }
            }
            if(armour<Math.max(core[4]*1.5,h*h*.00075)||maxX-minX<h*.045||maxY-minY<h*.045)continue;
            double nx=cx/(double)w,ny=cy/(double)h;
            if(masked(nx,ny,result))continue;
            boolean duplicate=false;for(double[] e:enemies)if(nx>e[0]-.025&&nx<e[2]+.025&&ny>e[1]-.035&&ny<e[3]+.035){duplicate=true;break;}
            if(!duplicate)enemies.add(new double[]{Math.min(minX,core[0])/(double)w,Math.min(minY,core[1])/(double)h,
                (Math.max(maxX,core[2])+1.)/w,(Math.max(maxY,core[3])+1.)/h,0,0});
        }
        // Wide cyan crawler armour can obscure its red core during an attack.
        // Restrict these candidates to compact, wide bodies supported by a floor;
        // a round allied orb/drop or a long blue shot cannot satisfy this shape.
        List<int[]> cyan=components(p,w,h,4,(int)(w*.025),(int)(h*.18),(int)(w*.975),(int)(h*.86));
        for(int i=0;i<cyan.size();i++)for(int j=i+1;j<cyan.size();j++) {
            int[] a=cyan.get(i),b=cyan.get(j);
            int gapX=Math.max(0,Math.max(a[0],b[0])-Math.min(a[2],b[2]));
            int gapY=Math.max(0,Math.max(a[1],b[1])-Math.min(a[3],b[3]));
            if(gapX<h*.027&&gapY<h*.025&&Math.max(a[2],b[2])-Math.min(a[0],b[0])<h*.22&&Math.max(a[3],b[3])-Math.min(a[1],b[1])<h*.15) {
                a[0]=Math.min(a[0],b[0]);a[1]=Math.min(a[1],b[1]);a[2]=Math.max(a[2],b[2]);a[3]=Math.max(a[3],b[3]);a[4]+=b[4];cyan.remove(j--);
            }
        }
        for(int[] body:cyan) {
            int bw=body[2]-body[0],bh=body[3]-body[1];
            if(bw<h*.055||bh<h*.024||bw>h*.23||bh>h*.15||bw<bh*1.35||bw>bh*4.0||body[4]<bw*bh*.16)continue;
            double nx=(body[0]+body[2])/(2.*w),ny=(body[1]+body[3])/(2.*h);
            if(masked(nx,ny,result)||!darkFloorSupport(p,w,h,body[0],body[2],body[3],body[3]+(int)(h*.065)))continue;
            boolean duplicate=false;for(double[] e:enemies)if(nx>e[0]-.025&&nx<e[2]+.025&&ny>e[1]-.035&&ny<e[3]+.035){duplicate=true;break;}
            if(!duplicate)enemies.add(new double[]{body[0]/(double)w,body[1]/(double)h,body[2]/(double)w,body[3]/(double)h,0,0});
        }
        result.enemyBoxes=enemies.toArray(new double[0][]);
    }

    private static boolean darkFloorSupport(int[] p,int w,int h,int left,int right,int top,int bottom) {
        int adjacent=0;
        for(int y=Math.max(1,top);y<Math.min(h-1,bottom);y++) {
            int count=0;for(int x=Math.max(0,left);x<Math.min(w,right);x++) {
                int c=p[y*w+x],r=(c>>>16)&255,g=(c>>>8)&255,b=c&255;
                if(r>24&&g>24&&b>28&&r<160&&Math.abs(r-g)<22&&Math.abs(g-b)<30)count++;
            }
            if(count>(right-left)*.65){if(++adjacent>=2)return true;}else adjacent=0;
        }
        return false;
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
