package com.corefilter.farmer.engine;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;
import static com.corefilter.farmer.engine.FarmEngine.*;

/** End-screen and replay integration: do not lose or duplicate a completed map. */
public class RunMapTest {
    @Test public void manualViewsWithoutPlayerOrCameraDoNotRepaintTheFirstOrigin(){
        Config c=new Config();c.manualMapping=true;FarmEngine e=new FarmEngine(c);
        Frame first=gameplay(0);first.playerConfidence=first.cameraConfidence=0;e.next(first);
        double[][] original=e.navigationSnapshot().cells;
        Frame moved=gameplay(1000);moved.playerConfidence=moved.cameraConfidence=0;Arrays.fill(moved.terrainCells,(byte)2);e.next(moved);
        double[][] after=e.navigationSnapshot().cells;assertEquals(original.length,after.length);
        for(int i=0;i<original.length;i++)assertArrayEquals(original[i],after[i],0);
        assertEquals(2,e.navigationSnapshot().screenTerrain.length);
        assertTrue(Double.isNaN(e.navigationSnapshot().screenPoses[1][3]));
    }
    @Test public void humanMappingRecordsAndExportsWithoutAnyTouchesOrNavigationTrace(){
        FarmEngine.Config c=new FarmEngine.Config();c.manualMapping=true;FarmEngine e=new FarmEngine(c);
        for(int i=0;i<5;i++){
            Frame f=gameplay(i*700);f.cameraX=i*.10;f.playerY=i==0?.6:.45;f.grounded=i==0;
            assertEquals(Kind.WAIT,e.next(f).kind);assertFalse(e.takeRunStartRequest());
        }
        assertEquals("MANUAL_MAPPING",e.navigationPhase());assertEquals(0,e.navigationSnapshot().controlTrace.length);
        assertEquals(7,e.navigationSnapshot().remainingJumps);assertTrue(e.navigationSnapshot().borders.length>0);
        String id=e.recordingId();assertNotNull(id);
        assertEquals(Kind.WAIT,e.next(result(4000,false,true)).kind);
        RunMap report=e.takeFinishedMap();assertNotNull(report);assertEquals("manual-cleared",report.outcome);assertEquals(id,report.recordingId);
        Frame ad=new Frame(5000,"com.android.vending","Install",null);ad.observedAd=true;assertEquals(Kind.WAIT,e.next(ad).kind);
        assertNull(e.takeFinishedMap());assertEquals(Kind.WAIT,e.next(gameplay(6000)).kind);assertNotEquals(id,e.recordingId());
        e.stop();report=e.takeFinishedMap();assertEquals("manual-partial",report.outcome);assertEquals(0,report.snapshot.controlTrace.length);
    }
    private Frame gameplay(long now) {
        Frame f = new Frame(now,"com.Overcurve.Corebound","",null);
        f.gameplay=true;f.playerX=.5;f.playerY=.6;f.playerConfidence=.9;
        f.grounded=true;f.cameraX=f.cameraY=0;f.cameraConfidence=.9;
        f.viewportAspectRatio=2.17;f.terrainCols=48;f.terrainRows=24;
        f.terrainCells=new byte[48*24];
        for(int y=4;y<20;y++)for(int x=1;x<47;x++)f.terrainCells[y*48+x]=1;
        for(int x=1;x<47;x++)f.terrainCells[20*48+x]=2;
        return f;
    }
    private Frame result(long now, boolean death, boolean reward) {
        Frame f = new Frame(now,"com.Overcurve.Corebound",death?"Defeated":"Complete!",
                Arrays.asList(new Token(reward?"Watch reward":"Continue",.65,.80,.85,.90)));
        f.endScreen=true;f.filterOffer=reward;return f;
    }
    @Test public void mapIsCapturedOnceBeforeReplayResetsTheAtlas() {
        FarmEngine e=new FarmEngine(new Config());e.next(gameplay(0));
        int count=e.navigationSnapshot().cells.length;assertTrue(count>0);
        e.next(result(1000,false,false));
        RunMap report=e.takeFinishedMap();assertNotNull(report);
        assertEquals("cleared",report.outcome);assertEquals(1,report.runNumber);
        assertEquals(count,report.snapshot.cells.length);
        e.next(result(2000,false,false));assertNull(e.takeFinishedMap());
        Frame select=new Frame(3000,"com.Overcurve.Corebound","",Arrays.asList(new Token("Play",.65,.8,.85,.9)));
        select.targetSelected=true;e.next(select);e.next(gameplay(4000));
        assertEquals(count,report.snapshot.cells.length);
        assertEquals(1,e.completedRuns());
    }
    @Test public void defeatExportsItsOwnOutcomeWithoutInventingASuccess() {
        FarmEngine e=new FarmEngine(new Config());e.next(gameplay(0));e.next(result(1000,true,false));
        RunMap report=e.takeFinishedMap();assertNotNull(report);
        assertEquals("defeat",report.outcome);assertEquals(0,e.completedRuns());assertEquals(1,e.deaths());
    }
    @Test public void startingTheFilterAdDoesNotDelayOrDuplicateMapCapture() {
        FarmEngine e=new FarmEngine(new Config());e.next(gameplay(0));
        assertEquals(State.REWARD_AD,after(e,result(1000,false,true)));
        RunMap report=e.takeFinishedMap();assertNotNull(report);assertEquals("cleared",report.outcome);
        assertNull(e.takeFinishedMap());
    }
    @Test public void crateCloseIsNotAnExtraCompletedLevelMap() {
        FarmEngine e=new FarmEngine(new Config());e.next(gameplay(0));
        Frame crate=new Frame(1000,"com.Overcurve.Corebound","Crate contents",Arrays.asList(new Token("Close",.4,.8,.6,.9)));
        crate.crateScreen=true;e.next(crate);assertNull(e.takeFinishedMap());
    }
    @Test public void onlyAuthorizedRunStartTapsRequestACameraReset() {
        FarmEngine e=new FarmEngine(new Config());e.next(gameplay(0));
        assertFalse(e.takeRunStartRequest());
        Frame dropped=gameplay(1000);dropped.playerY=.8;dropped.grounded=false;
        dropped.completedSector=1;e.next(dropped);assertFalse(e.takeRunStartRequest());
        e.next(result(2000,false,false));assertFalse(e.takeRunStartRequest());
        Frame select=new Frame(3000,"com.Overcurve.Corebound","",Arrays.asList(new Token("Play",.65,.8,.85,.9)));
        select.targetSelected=true;assertEquals(Kind.TAP,e.next(select).kind);
        assertTrue(e.takeRunStartRequest());assertFalse(e.takeRunStartRequest());
    }
    @Test public void stoppingAnActiveRunRetainsPartialMapAndDecisionTraceOnce() {
        FarmEngine e=new FarmEngine(new Config());e.next(gameplay(0));e.next(gameplay(500));e.stop();
        RunMap report=e.takeFinishedMap();assertNotNull(report);assertEquals("interrupted",report.outcome);
        assertTrue(report.snapshot.controlTrace.length>0);assertTrue(report.snapshot.cells.length>0);
        assertEquals(0,e.completedRuns());assertEquals(0,e.deaths());
        e.stop();assertNull(e.takeFinishedMap());
    }
    private State after(FarmEngine e,Frame f){e.next(f);return e.state();}
}
