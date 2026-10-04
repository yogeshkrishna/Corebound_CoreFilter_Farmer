package com.corefilter.farmer.vision;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

/** Generated pixels only: no videos, screenshots, or external image fixtures. */
public class PixelVisionGeometryTest {
    private static final int W=600,H=270,ROCK=0xff5a5a66;
    private int[] scene(){int[] p=new int[W*H];Arrays.fill(p,0xff08080a);rect(p,25,8,150,18,0xffd2dc28);return p;}
    private void rect(int[] p,int x,int y,int width,int height,int color){
        for(int yy=Math.max(0,y);yy<Math.min(H,y+height);yy++)for(int xx=Math.max(0,x);xx<Math.min(W,x+width);xx++)p[yy*W+xx]=color;
    }
    private void hull(int[] p){rect(p,268,145,44,32,0xffd2dc28);rect(p,283,153,8,8,0xff2355af);}
    private void teeth(int[] p,int left,int right,int base,int depth){
        rect(p,left,base,right-left,3,ROCK);
        for(int x=left;x<right;x+=24)for(int y=3;y<depth;y++) {
            int inset=(y-3)*12/Math.max(1,depth-3);
            rect(p,x+inset,base+y,Math.max(0,24-2*inset),1,ROCK);
        }
    }
    private int solids(PixelVision.Result r){int n=0;for(byte c:r.terrainCells)if(c==2)n++;return n;}
    private boolean solidAt(PixelVision.Result r,double x,double y){return r.terrainCells[(int)(y*r.terrainRows)*r.terrainCols+(int)(x*r.terrainCols)]==2;}

    @Test public void aestheticTriangularStripIsNotCollisionTerrain(){
        int[] p=scene();teeth(p,84,516,95,31);
        PixelVision.Result r=PixelVision.analyse(p,W,H);
        assertTrue(r.gameplay);assertEquals("Spiky foreground is unknown, not solid",0,solids(r));
        for(boolean support:r.registrationForeground)assertFalse(support);
    }

    @Test public void rectangularSteppedRoofRetainsTreadsAndRisers(){
        int[] p=scene();rect(p,80,45,440,35,ROCK);rect(p,180,80,220,30,ROCK);rect(p,240,110,160,30,ROCK);
        PixelVision.Result r=PixelVision.analyse(p,W,H);
        assertTrue(solids(r)>70);
        assertTrue(solidAt(r,.5,.45));assertTrue(solidAt(r,.3,.25));
        assertNotEquals(2,r.terrainCells[15*r.terrainCols+30]);
    }

    @Test public void thinRealPlatformSurvivesRectangularEvidenceRequirement(){
        int[] p=scene();rect(p,220,140,220,3,ROCK);
        PixelVision.Result r=PixelVision.analyse(p,W,H);
        assertTrue("A three-pixel platform must remain visible to the planner",solids(r)>=15);
        assertTrue(solidAt(r,.55,.525));
    }

    @Test public void pictureBorderDoesNotBecomeAVerticalWall(){
        int[] p=scene();rect(p,0,30,18,220,ROCK);rect(p,W-18,30,18,220,ROCK);
        PixelVision.Result r=PixelVision.analyse(p,W,H);
        assertEquals(0,solids(r));assertFalse(r.wallLeft);assertFalse(r.wallRight);
    }

    @Test public void interiorRectangularWallIsRetained(){
        int[] p=scene();rect(p,405,65,22,158,ROCK);
        PixelVision.Result r=PixelVision.analyse(p,W,H);
        assertTrue(solids(r)>=20);assertTrue(solidAt(r,.69,.50));
    }

    @Test public void broadCeilingClippedByHudRetainsItsVisibleLowerFace(){
        int[] p=scene();rect(p,75,0,450,83,ROCK);
        // Restore independent gameplay HUD which is not terrain.
        rect(p,25,8,150,18,0xffd2dc28);
        PixelVision.Result r=PixelVision.analyse(p,W,H);
        assertTrue(solids(r)>40);assertTrue(solidAt(r,.6,.25));
        assertEquals(0,r.terrainCells[0]);
    }

    @Test public void floorAndCeilingContactsUseVerifiedGeometry(){
        int[] p=scene();hull(p);rect(p,240,178,100,4,ROCK);
        PixelVision.Result floor=PixelVision.analyse(p,W,H);
        assertTrue(floor.playerConfidence>.5);assertTrue(floor.grounded);assertFalse(floor.ceilingReached);
        p=scene();hull(p);rect(p,240,141,100,3,ROCK);
        PixelVision.Result roof=PixelVision.analyse(p,W,H);
        assertTrue(roof.ceilingReached);assertFalse(roof.grounded);
    }

    @Test public void spikyForegroundDoesNotRefillJumpBudgetThroughFalseContact(){
        int[] p=scene();hull(p);teeth(p,196,388,178,28);
        PixelVision.Result r=PixelVision.analyse(p,W,H);
        assertTrue(r.playerConfidence>.5);assertFalse(r.grounded);assertFalse(r.ceilingReached);
    }

    @Test public void aFrameBorderTouchDoesNotProveWallContact(){
        int[] p=scene();rect(p,0,35,15,198,ROCK);
        // Actor is deliberately far from any actual rectangular wall.
        hull(p);PixelVision.Result r=PixelVision.analyse(p,W,H);
        assertFalse(r.wallLeft);assertFalse(r.wallRight);
    }

    @Test public void tallTexturedWallSurvivesColouredLightAndPartialHudMask(){
        int[] p=scene();hull(p);
        rect(p,308,0,22,235,ROCK);
        // A coloured attack glow is outside the wall, not rock to paint into it.
        rect(p,250,60,58,80,0xff5b401c);
        for(int y=50;y<230;y++)for(int x=318;x<330;x++)
            if((x*7+y*11)%17<4)p[y*W+x]=0xff18181b;
        PixelVision.Result r=PixelVision.analyse(p,W,H);
        assertTrue("Retain the observed wall",solids(r)>18);assertTrue("Wall contact at body edge "+r.playerRight,r.wallRight);assertFalse(r.wallLeft);
        assertFalse(solidAt(r,.48,.3));
    }
}
