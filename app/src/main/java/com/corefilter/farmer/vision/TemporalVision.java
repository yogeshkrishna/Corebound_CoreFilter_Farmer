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
    private Sample recoveryAnchor;
    private int recoveryMatches;
    private long anchorAt;
    private int epoch,failed;
    private double cameraX,cameraY;
    private double playerWorldX=-1,playerWorldY=-1;
    private long playerAt;
    private double supportWorldY=Double.NaN;
    private long supportAt;
    private int stableSupport;

    public void reset() {
        anchor=recoveryAnchor=null;failed=recoveryMatches=0;cameraX=cameraY=0;playerWorldX=playerWorldY=-1;playerAt=0;epoch++;
        supportWorldY=Double.NaN;supportAt=0;stableSupport=0;
    }

    public void update(int[] pixels,int width,int height,PixelVision.Result result,long now) {
        if(pixels==null||width<1||height<1||(long)width*height>pixels.length)throw new IllegalArgumentException("Complete frame required");
        result.cameraDx=result.cameraDy=0;result.cameraConfidence=0;result.registrationReset=result.registrationLost=result.sceneChanged=false;
        result.cameraX=cameraX;result.cameraY=cameraY;result.registrationEpoch=epoch;
        // A HUD occlusion, airborne blank view, or OCR/menu miss is not a new room.
        // Only reset() or an actual capture-dimension change starts a new epoch.
        if(!result.gameplay)return;
        Sample current=sample(pixels,width,height,result);
        if(anchor==null||anchor.width!=width||anchor.height!=height) {
            anchor=current;anchorAt=now;cameraX=cameraY=0;failed=0;epoch++;
            result.registrationReset=true;result.registrationEpoch=epoch;
            result.cameraX=result.cameraY=0;
            rememberPlayer(result,now);return;
        }
        if(now<=anchorAt)return;
        if(anchor.foregroundCount<6&&current.foregroundCount>=6) {
            // Initial blank air did not supply an origin. Attach the first usable
            // foreground at the same local origin, without claiming a new room.
            anchor=current;anchorAt=now;failed=0;rememberPlayer(result,now);return;
        }
        Match best=register(anchor,current);
        if(best.confidence>=.55) {
            // A positive image shift means the world moved right on the screen,
            // which is a camera motion to the left. Offsets remain cumulative even
            // when the service skips a processed frame while a gesture finishes.
            result.cameraDx=-best.dx/(double)W;result.cameraDy=-best.dy/(double)H;
            cameraX+=result.cameraDx;cameraY+=result.cameraDy;
            result.cameraConfidence=best.confidence;failed=recoveryMatches=0;recoveryAnchor=null;
            anchor=current;anchorAt=now;
        } else {
            failed++;
            // Preserve the old anchor through a transient occlusion. If it no
            // longer overlaps after a large drop, require three mutually coherent
            // foreground views before exposing a new *local* coordinate origin.
            // This declares lost registration, never a new room or sector.
            if(current.foregroundCount>=8) {
                if(recoveryAnchor!=null&&register(recoveryAnchor,current).confidence>=.55)recoveryMatches++;
                else recoveryMatches=0;
                recoveryAnchor=current;
                if(now-anchorAt>3500&&recoveryMatches>=2) {
                    anchor=current;anchorAt=now;cameraX=cameraY=0;failed=recoveryMatches=0;recoveryAnchor=null;
                    epoch++;result.registrationReset=result.registrationLost=true;playerAt=0;
                }
            } else {recoveryAnchor=null;recoveryMatches=0;}
        }
        result.cameraX=cameraX;result.cameraY=cameraY;result.registrationEpoch=epoch;
        validatePlayer(result,now);
        confirmSupport(result,now);
        rememberPlayer(result,now);
    }

    private void confirmSupport(PixelVision.Result r,long now) {
        // Obscured/slotted feet alone do not prove a landing. Require coherent
        // foreground registration and a stationary world-space foot plane.
        if(r.playerConfidence<.5||!r.groundContactCandidate||r.cameraConfidence<.55||r.registrationLost){stableSupport=0;supportAt=0;return;}
        double y=r.playerBottom+cameraY;
        if(supportAt>0&&now>supportAt&&now-supportAt<=1000&&Math.abs(y-supportWorldY)<.012)stableSupport++;
        else stableSupport=1;
        supportWorldY=y;supportAt=now;
        if(stableSupport>=2)r.grounded=true;
    }

    private void validatePlayer(PixelVision.Result r,long now) {
        if(r.playerConfidence<.35||playerAt==0||now-playerAt>1000||r.cameraConfidence<.55)return;
        double wx=r.playerX+cameraX,wy=r.playerY+cameraY;
        double dt=Math.max(.08,(now-playerAt)/1000.);
        // A gold explosion with one blue pixel can resemble the hull. Reject only
        // an impossible world-space teleport, after correcting camera movement.
        if(Math.abs(wx-playerWorldX)>.12+dt*.65||Math.abs(wy-playerWorldY)>.15+dt*1.1) {
            r.playerConfidence=Math.min(.25,r.playerConfidence);r.groundContactCandidate=r.grounded=r.ceilingReached=r.wallLeft=r.wallRight=false;
        }
    }

    private void rememberPlayer(PixelVision.Result r,long now) {
        if(r.playerConfidence>=.50){playerWorldX=r.playerX+cameraX;playerWorldY=r.playerY+cameraY;playerAt=now;}
    }

    private static final class Sample {
        final int[] light=new int[W*H];final boolean[] valid=new boolean[W*H],foreground=new boolean[W*H];
        final int width,height;final List<int[]> features=new ArrayList<>();int foregroundCount;
        Sample(int width,int height){this.width=width;this.height=height;}
    }

    private static boolean masked(double x,double y,PixelVision.Result r) {
        if(PixelVision.occluded(x,y,r))return true;
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
            s.foreground[y*W+x]=r.registrationForeground[y*W+x];
        }
        List<int[]> ranked=new ArrayList<>();
        for(int y=3;y<H-3;y++)for(int x=3;x<W-3;x++) {
            int at=y*W+x;if(!s.valid[at]||!s.valid[at-1]||!s.valid[at+1]||!s.valid[at-W]||!s.valid[at+W])continue;
            int edge=Math.abs(s.light[at-1]-s.light[at+1])+Math.abs(s.light[at-W]-s.light[at+W]);
            // Background parallax and aesthetic teeth are not registration
            // anchors, even if they are the highest-contrast objects on screen.
            if(edge>=4&&s.light[at]>=15&&s.foreground[at])ranked.add(new int[]{x,y,edge,1});
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
        Match result=new Match();if(a.features.size()<6||b.features.size()<6)return result;
        List<int[]> features=a.features;
        // Both signs and a substantial vertical range are searched independently;
        // reversing or dropping does not assume a direction or reset coordinates.
        int rangeX=36,rangeY=24;double[][] scores=new double[2*rangeY+1][2*rangeX+1];
        int[][] counts=new int[scores.length][scores[0].length];
        double[] individualBest=new double[features.size()];java.util.Arrays.fill(individualBest,255);
        double best=Double.POSITIVE_INFINITY;
        for(int dy=-rangeY;dy<=rangeY;dy++)for(int dx=-rangeX;dx<=rangeX;dx++) {
            double sum=0;int points=0;
            for(int fi=0;fi<features.size();fi++) {
                int[] f=features.get(fi);
                int x=f[0]+dx,y=f[1]+dy;if(x<3||x>=W-3||y<3||y>=H-3)continue;
                double residual=patchError(a,b,f[0],f[1],x,y);
                if(residual<0)continue;
                individualBest[fi]=Math.min(individualBest[fi],residual);
                sum+=residual;points++;
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
        int inliers=0,considered=0;boolean[] zones=new boolean[12];int[] halfConsidered=new int[2],halfInliers=new int[2];
        for(int fi=0;fi<features.size();fi++) {
            int[] f=features.get(fi);int x=f[0]+result.dx,y=f[1]+result.dy;
            if(x<3||x>=W-3||y<3||y>=H-3)continue;
            if(!b.valid[y*W+x])continue;
            considered++;halfConsidered[f[0]<W/2?0:1]++;
            // A projected foreground landmark becoming visible dark/free space
            // is a disagreement, not an excuse to omit that landmark from the
            // consensus denominator. Otherwise a deforming half-frame can win.
            double residual=patchError(a,b,f[0],f[1],x,y);if(residual<0)continue;
            if(residual<=10&&residual<=individualBest[fi]+3) {
                inliers++;halfInliers[f[0]<W/2?0:1]++;zones[Math.min(2,f[1]*3/H)*4+Math.min(3,f[0]*4/W)]=true;
            }
        }
        int spread=0;for(boolean used:zones)if(used)spread++;
        // A local flash, repeated tile, or two independently deforming regions
        // cannot drag the world map using one locally convenient match.
        if(points<6||inliers<6||inliers<considered*.68||spread<2||Math.abs(result.dx)==rangeX||Math.abs(result.dy)==rangeY)result.confidence=0;
        for(int half=0;half<2;half++)if(halfConsidered[half]>=8&&halfInliers[half]<halfConsidered[half]*.65)result.confidence=0;
        return result;
    }

    private static double patchError(Sample a,Sample b,int ax,int ay,int bx,int by) {
        if(!b.valid[by*W+bx]||!b.foreground[by*W+bx])return -1;
        double residual=0;int valid=0;
        int ac=a.light[ay*W+ax],bc=b.light[by*W+bx];
        for(int[] off:PATCH) {
            int ai=(ay+off[1])*W+ax+off[0],bi=(by+off[1])*W+bx+off[0];
            if(!a.valid[ai]||!b.valid[bi])continue;
            residual+=Math.min(55,Math.abs((a.light[ai]-ac)-(b.light[bi]-bc)))*.75+
                Math.min(55,Math.abs(a.light[ai]-b.light[bi]))*.25;valid++;
        }
        return valid<5?-1:residual/valid;
    }
}
