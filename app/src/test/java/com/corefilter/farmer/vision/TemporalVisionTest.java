package com.corefilter.farmer.vision;

import org.junit.Test;
import java.util.Random;
import static org.junit.Assert.*;

/** Only generated rectangular geometry and synthetic textures are used here. */
public class TemporalVisionTest {
    private static final int W=600,H=280;
    private int[] terrain(int shiftX,int shiftY,int actorX) {return terrain(shiftX,shiftY,actorX,67);}
    private int[] terrain(int shiftX,int shiftY,int actorX,long seed) {
        int[] p=new int[W*H];java.util.Arrays.fill(p,0xff080809);
        Random random=new Random(seed);
        for(int k=0;k<55;k++) {
            int x=40+random.nextInt(500)+shiftX,y=48+random.nextInt(180)+shiftY;
            int width=8+random.nextInt(38),height=6+random.nextInt(28),shade=45+random.nextInt(55);
            for(int yy=Math.max(0,y);yy<Math.min(H,y+height);yy++)for(int xx=Math.max(0,x);xx<Math.min(W,x+width);xx++) {
                int value=shade+((xx-x)*3+(yy-y)*5)%13;p[yy*W+xx]=0xff000000|(value<<16)|(value<<8)|(value+5);
            }
        }
        for(int y=105;y<130;y++)for(int x=actorX;x<actorX+35;x++)if(x>=0&&x<W)p[y*W+x]=0xffffcd20;
        for(int y=8;y<26;y++)for(int x=25;x<175;x++)p[y*W+x]=0xffd2dc28;
        return p;
    }
    private PixelVision.Result result(int[] pixels) {
        PixelVision.Result r=PixelVision.analyse(pixels,W,H);r.gameplay=true;return r;
    }
    private PixelVision.Result update(TemporalVision tracker,int[] pixels,long now) {
        PixelVision.Result r=result(pixels);tracker.update(pixels,W,H,r,now);return r;
    }

    @Test public void translationUsesVerifiedTerrainAndAccumulatesAcrossFrames() {
        TemporalVision tracker=new TemporalVision();PixelVision.Result first=update(tracker,terrain(0,0,250),1000);
        assertTrue(first.registrationReset);
        PixelVision.Result second=update(tracker,terrain(-40,15,400),1350);
        assertTrue("Terrain registration confidence "+second.cameraConfidence,second.cameraConfidence>.70);
        assertEquals(40./W,second.cameraDx,.009);assertEquals(-15./H,second.cameraDy,.019);
        PixelVision.Result third=update(tracker,terrain(-60,25,150),1700);
        assertTrue(third.cameraConfidence>.70);assertEquals(60./W,third.cameraX,.011);
        assertEquals(-25./H,third.cameraY,.022);assertEquals(first.registrationEpoch,third.registrationEpoch);
    }

    @Test public void independentlyMovingActorDoesNotCauseCameraMotion() {
        TemporalVision tracker=new TemporalVision();update(tracker,terrain(0,0,120),1000);
        PixelVision.Result r=update(tracker,terrain(0,0,420),1350);
        assertTrue(r.cameraConfidence>.75);assertEquals(0,r.cameraDx,0);assertEquals(0,r.cameraDy,0);
    }

    @Test public void longAmbiguousBlankCannotEraseAnchorOrInventRoom() {
        TemporalVision tracker=new TemporalVision();update(tracker,terrain(0,0,120),1000);
        PixelVision.Result moving=update(tracker,terrain(-20,0,420),1350);assertTrue(moving.cameraConfidence>.55);
        for(long now=1700;now<6000;now+=400) {
            PixelVision.Result blank=update(tracker,new int[W*H],now);
            assertEquals(0,blank.cameraConfidence,0);assertEquals(moving.cameraX,blank.cameraX,0);
            assertFalse(blank.sceneChanged);assertFalse(blank.registrationReset);assertFalse(blank.registrationLost);
        }
        PixelVision.Result recovered=update(tracker,terrain(-30,0,420),6500);
        assertTrue(recovered.cameraConfidence>.55);assertEquals(30./W,recovered.cameraX,.01);
        assertEquals(moving.registrationEpoch,recovered.registrationEpoch);
    }

    @Test public void confidenceRequiresUniqueForegroundNotRepeatedBlankBrightness() {
        TemporalVision tracker=new TemporalVision();int[] solid=new int[W*H];java.util.Arrays.fill(solid,0xff353538);
        update(tracker,solid,1000);PixelVision.Result r=update(tracker,solid,1350);
        assertEquals(0,r.cameraConfidence,0);assertEquals(0,r.cameraX,0);
    }

    @Test public void explicitResetBeginsANewRegistrationEpoch() {
        TemporalVision tracker=new TemporalVision();PixelVision.Result first=update(tracker,terrain(0,0,100),1000);
        tracker.reset();PixelVision.Result next=update(tracker,terrain(0,0,100),1400);
        assertTrue(next.registrationReset);assertTrue(next.registrationEpoch>first.registrationEpoch);assertEquals(0,next.cameraX,0);
    }

    @Test public void impossiblePlayerTeleportIsRejectedAfterCameraCorrection() {
        TemporalVision tracker=new TemporalVision();int[] pixels=terrain(0,0,260);PixelVision.Result first=result(pixels);
        first.playerX=.20;first.playerY=.50;first.playerConfidence=.9;
        first.playerLeft=.18;first.playerRight=.22;first.playerTop=.46;first.playerBottom=.54;
        tracker.update(pixels,W,H,first,1000);
        PixelVision.Result flare=result(pixels);flare.playerX=.95;flare.playerY=.50;flare.playerConfidence=.9;
        flare.playerLeft=.93;flare.playerRight=.97;flare.playerTop=.46;flare.playerBottom=.54;flare.grounded=true;
        tracker.update(pixels,W,H,flare,1100);
        assertTrue(flare.cameraConfidence>.55);assertTrue(flare.playerConfidence<.35);assertFalse(flare.grounded);
    }

    @Test public void directionReversalAndVerticalDropKeepSignedCoordinates(){
        TemporalVision tracker=new TemporalVision();PixelVision.Result first=update(tracker,terrain(0,0,250),1000);
        PixelVision.Result right=update(tracker,terrain(-35,0,250),1350);assertTrue(right.cameraConfidence>.55);
        PixelVision.Result left=update(tracker,terrain(10,0,250),1700);
        assertTrue(left.cameraConfidence>.55);assertTrue(left.cameraDx<0);assertEquals(-10./W,left.cameraX,.015);
        PixelVision.Result drop=update(tracker,terrain(10,-35,250),2050);
        assertTrue(drop.cameraConfidence>.55);assertTrue(drop.cameraDy>0);assertEquals(35./H,drop.cameraY,.025);
        assertEquals(first.registrationEpoch,drop.registrationEpoch);assertFalse(drop.sceneChanged);
    }

    @Test public void nonGameplayBlipDoesNotReanchor(){
        TemporalVision tracker=new TemporalVision();PixelVision.Result first=update(tracker,terrain(0,0,250),1000);
        PixelVision.Result blank=new PixelVision.Result();tracker.update(new int[W*H],W,H,blank,1350);
        PixelVision.Result second=update(tracker,terrain(-20,0,250),1700);
        assertTrue(second.cameraConfidence>.55);assertEquals(first.registrationEpoch,second.registrationEpoch);
    }

    @Test public void unverifiedNeutralBackgroundCannotMoveMap(){
        TemporalVision tracker=new TemporalVision();int[] first=terrain(0,0,250),second=terrain(-35,0,250);
        PixelVision.Result r=new PixelVision.Result();r.gameplay=true;tracker.update(first,W,H,r,1000);
        r=new PixelVision.Result();r.gameplay=true;tracker.update(second,W,H,r,1350);
        assertEquals(0,r.cameraConfidence,0);assertEquals(0,r.cameraX,0);
    }

    @Test public void independentDeformationIsNotCameraTranslation(){
        TemporalVision tracker=new TemporalVision();int[] first=terrain(0,0,250);
        update(tracker,first,1000);
        int[] leftShift=terrain(-35,0,250),rightShift=terrain(35,0,250),deformed=new int[W*H];
        for(int y=0;y<H;y++)for(int x=0;x<W;x++)deformed[y*W+x]=(x<W/2?leftShift:rightShift)[y*W+x];
        PixelVision.Result r=update(tracker,deformed,1350);
        assertTrue("Independent foreground deformation must remain unregistered",r.cameraConfidence<.55);
        assertEquals(0,r.cameraX,0);assertFalse(r.sceneChanged);
    }

    @Test public void sustainedNewForegroundCanReanchorWithoutClaimingANewRoom(){
        TemporalVision tracker=new TemporalVision();PixelVision.Result first=update(tracker,terrain(0,0,250),1000);
        int[] newScene=terrain(0,0,250,9991);
        PixelVision.Result firstMissing=update(tracker,newScene,5000);assertFalse(firstMissing.registrationReset);
        PixelVision.Result secondMissing=update(tracker,newScene,5400);assertFalse(secondMissing.registrationReset);
        PixelVision.Result recovered=update(tracker,newScene,5800);
        assertTrue(recovered.registrationReset);assertTrue(recovered.registrationLost);assertFalse(recovered.sceneChanged);
        assertTrue(recovered.registrationEpoch>first.registrationEpoch);assertEquals(0,recovered.cameraX,0);
    }

    private PixelVision.Result weakFeet(TemporalVision tracker,int[] pixels,long now,double y) {
        PixelVision.Result r=result(pixels);r.playerConfidence=.9;r.playerX=.45;r.playerY=y;
        r.playerLeft=.42;r.playerRight=.48;r.playerTop=y-.04;r.playerBottom=y+.04;
        r.grounded=false;r.groundContactCandidate=true;tracker.update(pixels,W,H,r,now);return r;
    }

    @Test public void weakFootPixelsNeedTwoRegisteredStationaryViews(){
        TemporalVision tracker=new TemporalVision();int[] p=terrain(0,0,260);
        assertFalse(weakFeet(tracker,p,1000,.5).grounded);
        assertFalse(weakFeet(tracker,p,1350,.5).grounded);
        PixelVision.Result confirmed=weakFeet(tracker,p,1700,.5);
        assertTrue(confirmed.cameraConfidence>.55);assertTrue(confirmed.grounded);
    }

    @Test public void fallingPastDecorativeFootPixelsCannotRechargeJumps(){
        TemporalVision tracker=new TemporalVision();int[] p=terrain(0,0,260);
        for(int i=0;i<4;i++)assertFalse(weakFeet(tracker,p,1000+i*350,.40+i*.035).grounded);
    }

    @Test public void blankCameraCannotConfirmWeakFeet(){
        TemporalVision tracker=new TemporalVision();int[] p=new int[W*H];
        for(int i=0;i<4;i++)assertFalse(weakFeet(tracker,p,1000+i*350,.5).grounded);
    }
}
