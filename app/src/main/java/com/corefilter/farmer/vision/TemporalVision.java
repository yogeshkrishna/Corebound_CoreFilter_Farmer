package com.corefilter.farmer.vision;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Registers neutral terrain between screenshots. Actors, HUD, controls and coloured
 * attack light are excluded; intensity changes never stand in for displacement. */
public final class TemporalVision {
    private static final int W=120,H=56;
    private static final int[][] PATCH={{0,0},{-1,0},{1,0},{0,-1},{0,1},{-2,0},{2,0},{0,-2},{0,2}};
    private Sample anchor;
    private long anchorAt;
    private int epoch,failed;
    private double cameraX,cameraY;
    private double playerWorldX=-1,playerWorldY=-1;
    private long playerAt;

    public void reset() {
        anchor=null;failed=0;cameraX=cameraY=0;playerWorldX=playerWorldY=-1;playerAt=0;epoch++;
    }

    public void update(int[] pixels,int width,int height,PixelVision.Result result,long now) {
        if(pixels==null||width<1||height<1||(long)width*height>pixels.length)throw new IllegalArgumentException("Complete frame required");
        result.cameraDx=result.cameraDy=0;result.cameraConfidence=0;
        result.cameraX=cameraX;result.cameraY=cameraY;result.registrationEpoch=epoch;
        if(!result.gameplay){anchor=null;failed=0;playerAt=0;return;}
        Sample current=sample(pixels,width,height,result);
        if(anchor==null||anchor.width!=width||anchor.height!=height||now<anchorAt) {
            anchor=current;anchorAt=now;cameraX=cameraY=0;failed=0;epoch++;
            result.registrationReset=true;result.registrationEpoch=epoch;
            result.cameraX=result.cameraY=0;
            rememberPlayer(result,now);return;
        }
        if(anchor.foregroundCount<7&&current.foregroundCount>=7) {
            // Blank air / parallax background cannot anchor a terrain map. Begin
            // an explicit local anchor as soon as collision terrain re-enters view.
            anchor=current;anchorAt=now;failed=0;cameraX=cameraY=0;epoch++;
            result.registrationReset=true;result.registrationEpoch=epoch;
            result.cameraX=result.cameraY=0;rememberPlayer(result,now);return;
        }
        Match best=register(anchor,current);
        if(best.confidence>=.55&&now-anchorAt<=2500) {
            // A positive image shift means the world moved right on the screen,
            // which is a camera motion to the left. Offsets remain cumulative even
            // when the service skips a processed frame while a gesture finishes.
            result.cameraDx=-best.dx/(double)W;result.cameraDy=-best.dy/(double)H;
            cameraX+=result.cameraDx;cameraY+=result.cameraDy;
            result.cameraConfidence=best.confidence;failed=0;
            anchor=current;anchorAt=now;
        } else {
            failed++;
            // Keep the last reliable anchor through transient occlusion. A new
            // anchor is declared after an extended discontinuity; never integrate
            // an unconfident best guess into the accumulated world map.
            if(failed>=4&&(now-anchorAt>1800||best.error>32||(now-anchorAt>900&&current.foregroundCount>=7))) {
                anchor=current;anchorAt=now;failed=0;cameraX=cameraY=0;epoch++;
                result.registrationReset=true;result.sceneChanged=true;playerAt=0;
            }
        }
        result.cameraX=cameraX;result.cameraY=cameraY;result.registrationEpoch=epoch;
        validatePlayer(result,now);
        rememberPlayer(result,now);
    }

    private void validatePlayer(PixelVision.Result r,long now) {
        if(r.playerConfidence<.35||playerAt==0||now-playerAt>1000||r.cameraConfidence<.55)return;
        double wx=r.playerX+cameraX,wy=r.playerY+cameraY;
        double dt=Math.max(.08,(now-playerAt)/1000.);
        // A gold explosion with one blue pixel can resemble the hull. Reject only
        // an impossible world-space teleport, after correcting camera movement.
        if(Math.abs(wx-playerWorldX)>.12+dt*.65||Math.abs(wy-playerWorldY)>.15+dt*1.1) {
            r.playerConfidence=Math.min(.25,r.playerConfidence);r.grounded=r.ceilingReached=r.wallLeft=r.wallRight=false;
        }
    }

    private void rememberPlayer(PixelVision.Result r,long now) {
        if(r.playerConfidence>=.50){playerWorldX=r.playerX+cameraX;playerWorldY=r.playerY+cameraY;playerAt=now;}
    }

    private static final class Sample {
        final int[] light=new int[W*H];final boolean[] valid=new boolean[W*H];
        final int width,height;final List<int[]> features=new ArrayList<>();int foregroundCount;
        Sample(int width,int height){this.width=width;this.height=height;}
    }

    private static boolean masked(double x,double y,PixelVision.Result r) {
        if(y<.15||y>.86||x<.035||x>.96||(x<.4&&y>.69))return true;
        if(r.playerConfidence>.30&&x>r.playerLeft-.035&&x<r.playerRight+.035&&y>r.playerTop-.05&&y<r.playerBottom+.05)return true;
        for(double[] box:r.enemyBoxes)if(x>box[0]-.015&&x<box[2]+.015&&y>box[1]-.025&&y<box[3]+.025)return true;
        return false;
    }

    private static Sample sample(int[] p,int width,int height,PixelVision.Result r) {
        Sample s=new Sample(width,height);
        for(int y=0;y<H;y++)for(int x=0;x<W;x++) {
            double nx=(x+.5)/W,ny=(y+.5)/H;if(masked(nx,ny,r))continue;
            int px=Math.min(width-1,(int)(nx*width)),py=Math.min(height-1,(int)(ny*height));
            int red=0,g=0,b=0,n=0;
            // A small box average suppresses video compression and tile texture
            // aliasing when native screenshots are reduced to the registration grid.
            for(int yy=Math.max(0,py-2);yy<=Math.min(height-1,py+2);yy++)for(int xx=Math.max(0,px-2);xx<=Math.min(width-1,px+2);xx++) {
                int c=p[yy*width+xx];red+=(c>>>16)&255;g+=(c>>>8)&255;b+=c&255;n++;
            }
            red/=n;g/=n;b/=n;
            // Cool-neutral foreground/background texture is useful. Gold aura,
            // pink limbs, red barriers and bright touch markers are moving masks.
            if(red>165||g>175||b>190||Math.abs(red-g)>24||b-g>35||g-b>24)continue;
            s.light[y*W+x]=(red*3+g*6+b)/10;s.valid[y*W+x]=true;
        }
        List<int[]> ranked=new ArrayList<>();
        for(int y=3;y<H-3;y++)for(int x=3;x<W-3;x++) {
            int at=y*W+x;if(!s.valid[at]||!s.valid[at-1]||!s.valid[at+1]||!s.valid[at-W]||!s.valid[at+W])continue;
            int edge=Math.abs(s.light[at-1]-s.light[at+1])+Math.abs(s.light[at-W]-s.light[at+W]);
            int terrainX=Math.min(r.terrainCols-1,(int)((x+.5)/W*r.terrainCols));
            int terrainY=Math.min(r.terrainRows-1,(int)((y+.5)/H*r.terrainRows));
            boolean foreground=r.terrainCells.length==r.terrainCols*r.terrainRows&&r.terrainCells[terrainY*r.terrainCols+terrainX]==2;
            if(edge>=7&&s.light[at]>=15)ranked.add(new int[]{x,y,edge*(foreground?4:1),foreground?1:0});
        }
        ranked.sort(Comparator.comparingInt((int[] f)->f[2]).reversed());
        boolean[] taken=new boolean[W*H];
        for(int[] f:ranked) {
            int x=f[0],y=f[1];if(taken[y*W+x])continue;s.features.add(f);if(f[3]==1)s.foregroundCount++;
            for(int yy=y-2;yy<=y+2;yy++)for(int xx=x-2;xx<=x+2;xx++)taken[yy*W+xx]=true;
            if(s.features.size()>=100)break;
        }
        return s;
    }

    private static final class Match {int dx,dy;double error=255,confidence;}
    private static Match register(Sample a,Sample b) {
        Match result=new Match();if(a.features.size()<9||b.features.size()<9)return result;
        List<int[]> features=a.features;
        if(a.foregroundCount>=7) {features=new ArrayList<>();for(int[] f:a.features)if(f[3]==1)features.add(f);}
        int rangeX=28,rangeY=20;double[][] scores=new double[2*rangeY+1][2*rangeX+1];
        int[][] counts=new int[scores.length][scores[0].length];
        double best=Double.POSITIVE_INFINITY;
        for(int dy=-rangeY;dy<=rangeY;dy++)for(int dx=-rangeX;dx<=rangeX;dx++) {
            double sum=0;int points=0;
            for(int[] f:features) {
                int x=f[0]+dx,y=f[1]+dy;if(x<3||x>=W-3||y<3||y>=H-3)continue;
                double residual=0;int valid=0;
                int ac=a.light[f[1]*W+f[0]],bc=b.light[y*W+x];
                for(int[] off:PATCH) {
                    int ai=(f[1]+off[1])*W+f[0]+off[0],bi=(y+off[1])*W+x+off[0];
                    if(!a.valid[ai]||!b.valid[bi])continue;
                    // Compare patch shape as well as absolute intensity. Ember's
                    // changing light can brighten the same fixed wall between two
                    // frames; subtracting the centre removes that additive change.
                    residual+=Math.min(55,Math.abs((a.light[ai]-ac)-(b.light[bi]-bc)))*.75+
                        Math.min(55,Math.abs(a.light[ai]-b.light[bi]))*.25;valid++;
                }
                if(valid<5)continue;
                sum+=residual/valid;points++;
            }
            double coverage=points/(double)features.size();
            double score=points<6?255:sum/points+Math.max(0,.58-coverage)*32;
            scores[dy+rangeY][dx+rangeX]=score;counts[dy+rangeY][dx+rangeX]=points;
            if(score<best){best=score;result.dx=dx;result.dy=dy;}
        }
        double second=255;
        for(int dy=-rangeY;dy<=rangeY;dy++)for(int dx=-rangeX;dx<=rangeX;dx++) {
            if(Math.abs(dx-result.dx)<3&&Math.abs(dy-result.dy)<3)continue;
            second=Math.min(second,scores[dy+rangeY][dx+rangeX]);
        }
        int points=counts[result.dy+rangeY][result.dx+rangeX];
        double coverage=points/(double)features.size();
        double quality=Math.max(0,1-best/20),uniqueness=Math.min(1,Math.max(0,(second-best)/4));
        result.error=best;
        result.confidence=Math.min(.98,quality*Math.min(1,coverage/.62)*(.35+.65*uniqueness));
        if(points<7||Math.abs(result.dx)==rangeX||Math.abs(result.dy)==rangeY)result.confidence=0;
        return result;
    }
}
