package com.corefilter.farmer.vision;

import org.junit.Test;
import java.util.Random;
import static org.junit.Assert.*;

public class TemporalVisionTest {
    private static final int W=600,H=280;
    private int[] terrain(int shiftX,int shiftY,int actorX) {
        int[] p=new int[W*H];java.util.Arrays.fill(p,0xff080809);
        Random random=new Random(67);
        for(int k=0;k<55;k++) {
            int x=40+random.nextInt(500)+shiftX,y=48+random.nextInt(180)+shiftY;
            int width=8+random.nextInt(38),height=6+random.nextInt(28),shade=35+random.nextInt(65);
            for(int yy=Math.max(0,y);yy<Math.min(H,y+height);yy++)for(int xx=Math.max(0,x);xx<Math.min(W,x+width);xx++) {
                int value=shade+((xx-x)*3+(yy-y)*5)%13;p[yy*W+xx]=0xff000000|(value<<16)|(value<<8)|(value+5);
            }
        }
        // Actors move independently of world terrain and must not pull the camera.
        for(int y=105;y<130;y++)for(int x=actorX;x<actorX+35;x++)p[y*W+x]=0xffffcd20;
        return p;
    }
    private PixelVision.Result result() {PixelVision.Result r=new PixelVision.Result();r.gameplay=true;return r;}

    @Test public void translationUsesTerrainAndAccumulatesAcrossFrames() {
        TemporalVision tracker=new TemporalVision();PixelVision.Result first=result();
        tracker.update(terrain(0,0,250),W,H,first,1000);assertTrue(first.registrationReset);
        PixelVision.Result second=result();tracker.update(terrain(-40,15,400),W,H,second,1350);
        assertTrue("Terrain registration confidence "+second.cameraConfidence,second.cameraConfidence>.70);
        assertEquals(40./W,second.cameraDx,.009);assertEquals(-15./H,second.cameraDy,.019);
        PixelVision.Result third=result();tracker.update(terrain(-60,25,150),W,H,third,1700);
        assertTrue(third.cameraConfidence>.70);assertEquals(60./W,third.cameraX,.011);
        assertEquals(-25./H,third.cameraY,.022);assertEquals(first.registrationEpoch,third.registrationEpoch);
    }

    @Test public void independentlyMovingActorDoesNotCauseCameraMotion() {
        TemporalVision tracker=new TemporalVision();tracker.update(terrain(0,0,120),W,H,result(),1000);
        PixelVision.Result r=result();tracker.update(terrain(0,0,420),W,H,r,1350);
        assertTrue(r.cameraConfidence>.75);assertEquals(0,r.cameraDx,0);assertEquals(0,r.cameraDy,0);
    }

    @Test public void ambiguousBlankFrameCannotMoveOrEraseCameraAnchor() {
        TemporalVision tracker=new TemporalVision();tracker.update(terrain(0,0,120),W,H,result(),1000);
        PixelVision.Result moving=result();tracker.update(terrain(-20,0,420),W,H,moving,1350);
        assertTrue(moving.cameraConfidence>.55);
        PixelVision.Result blank=result();tracker.update(new int[W*H],W,H,blank,1700);
        assertEquals(0,blank.cameraConfidence,0);assertEquals(moving.cameraX,blank.cameraX,0);assertFalse(blank.sceneChanged);
        PixelVision.Result recovered=result();tracker.update(terrain(-30,0,420),W,H,recovered,2050);
        assertTrue(recovered.cameraConfidence>.55);assertEquals(30./W,recovered.cameraX,.01);
    }

    @Test public void confidenceRequiresUniqueTextureNotRepeatedBlankBrightness() {
        TemporalVision tracker=new TemporalVision();int[] solid=new int[W*H];java.util.Arrays.fill(solid,0xff353538);
        tracker.update(solid,W,H,result(),1000);PixelVision.Result r=result();
        tracker.update(solid,W,H,r,1350);assertEquals(0,r.cameraConfidence,0);assertEquals(0,r.cameraX,0);
    }

    @Test public void explicitResetBeginsANewRegistrationEpoch() {
        TemporalVision tracker=new TemporalVision();PixelVision.Result first=result();tracker.update(terrain(0,0,100),W,H,first,1000);
        tracker.reset();PixelVision.Result next=result();tracker.update(terrain(0,0,100),W,H,next,1400);
        assertTrue(next.registrationReset);assertTrue(next.registrationEpoch>first.registrationEpoch);assertEquals(0,next.cameraX,0);
    }
    @Test public void impossiblePlayerTeleportIsRejectedAfterCameraCorrection() {
        TemporalVision tracker=new TemporalVision();PixelVision.Result first=result();
        first.playerX=.20;first.playerY=.50;first.playerConfidence=.9;
        first.playerLeft=.18;first.playerRight=.22;first.playerTop=.46;first.playerBottom=.54;
        tracker.update(terrain(0,0,260),W,H,first,1000);
        PixelVision.Result flare=result();flare.playerX=.95;flare.playerY=.50;flare.playerConfidence=.9;
        flare.playerLeft=.93;flare.playerRight=.97;flare.playerTop=.46;flare.playerBottom=.54;flare.grounded=true;
        tracker.update(terrain(0,0,260),W,H,flare,1100);
        assertTrue(flare.cameraConfidence>.55);assertTrue(flare.playerConfidence<.35);assertFalse(flare.grounded);
    }

}
