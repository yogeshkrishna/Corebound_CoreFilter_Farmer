package com.corefilter.farmer.engine;
import com.corefilter.farmer.vision.PixelVision;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class ScreenInterpreterTest {
    @Test public void installCardOverridesFalseGameplayPixels(){
        PixelVision.Result v=new PixelVision.Result();v.gameplay=true;v.playerConfidence=.9;v.controlsDetected=true;
        FarmEngine.Frame f=ScreenInterpreter.interpret(1000,1000,"com.Overcurve.Corebound",Arrays.asList(new FarmEngine.Token("Install",.55,.4,.8,.5),new FarmEngine.Token("Google Play",.36,.88,.50,.95)),v);
        assertTrue(f.observedAd);assertFalse(f.gameplay);
        assertEquals(FarmEngine.State.INTERSTITIAL,stateAfter(f));
    }
    private FarmEngine.State stateAfter(FarmEngine.Frame f){FarmEngine e=new FarmEngine(new FarmEngine.Config());e.next(f);return e.state();}
    @Test public void registeredTerrainAndContactsReachPlannerUnchanged(){
        PixelVision.Result v=new PixelVision.Result();v.gameplay=true;v.playerConfidence=.9;
        v.playerLeft=.4;v.playerRight=.46;v.playerTop=.5;v.playerBottom=.6;v.wallRight=true;
        v.cameraX=1.2;v.cameraY=-.8;v.cameraConfidence=.85;v.registrationEpoch=4;
        v.terrainCells[700]=2;
        FarmEngine.Frame f=ScreenInterpreter.interpret(1200,1000,"com.Overcurve.Corebound",null,v);
        assertSame(v.terrainCells,f.terrainCells);assertEquals(2,f.terrainCells[700]);assertTrue(f.wallRight);
        assertEquals(1.2,f.cameraX,0);assertEquals(-.8,f.cameraY,0);assertEquals(4,f.registrationEpoch);
        assertEquals(.6,f.playerBottom,0);assertEquals(1000,f.capturedAt);
    }
    @Test public void syntheticWatchTargetActuallyReachesEngine(){
        PixelVision.Result v=new PixelVision.Result();v.rewardButton=true;v.filterLoot=true;
        FarmEngine.Frame f=ScreenInterpreter.interpret(1000,1000,"com.Overcurve.Corebound",Arrays.asList(new FarmEngine.Token("Complete!",.4,.03,.6,.15),new FarmEngine.Token("Continue",.22,.81,.46,.95)),v);
        FarmEngine.Action a=new FarmEngine(new FarmEngine.Config()).next(f);
        assertEquals(FarmEngine.Kind.TAP,a.kind);assertEquals(.6695,a.x,.001);assertTrue(f.filterOffer);
    }
    @Test public void inventoryCannotBecomeRewardOffer(){
        PixelVision.Result v=new PixelVision.Result();v.rewardButton=true;v.filterLoot=true;
        FarmEngine.Frame f=ScreenInterpreter.interpret(1000,1000,"com.Overcurve.Corebound",Arrays.asList(new FarmEngine.Token("Loadouts",.4,.03,.6,.15)),v);
        assertFalse(f.filterOffer);assertEquals(1,f.tokens.size());
    }
    @Test public void sectorClearIsGameplayEvidenceAndNotLevelCompletion(){
        PixelVision.Result v=new PixelVision.Result();v.gameplay=true;
        java.util.List<FarmEngine.Token> tokens=Arrays.asList(new FarmEngine.Token("Sector 3/4",.4,.17,.6,.22),new FarmEngine.Token("COMPLETED",.4,.23,.6,.28));
        FarmEngine.Frame f=ScreenInterpreter.interpret(1000,1000,"com.Overcurve.Corebound",tokens,v);
        assertEquals(3,f.completedSector);assertFalse(f.endScreen);
        v.gameplay=false;assertEquals(0,ScreenInterpreter.interpret(1000,1000,"com.Overcurve.Corebound",tokens,v).completedSector);
    }
    @Test public void unrecognizedRareRewardPausesRatherThanDiscards(){
        PixelVision.Result v=new PixelVision.Result();v.rewardButton=true;v.uncertainFilterOffer=true;
        FarmEngine.Frame f=ScreenInterpreter.interpret(1000,1000,"com.Overcurve.Corebound",Arrays.asList(new FarmEngine.Token("Complete!",.4,.03,.6,.15),new FarmEngine.Token("Continue",.22,.81,.46,.95)),v);
        assertEquals(FarmEngine.Kind.PAUSE,new FarmEngine(new FarmEngine.Config()).next(f).kind);
    }
    @Test public void labelledHudCountIsFreshAndZeroIsNeverCarriedForward(){
        PixelVision.Result v=new PixelVision.Result();v.gameplay=true;
        FarmEngine.Frame first=ScreenInterpreter.interpret(1000,1000,"com.Overcurve.Corebound",Arrays.asList(
            new FarmEngine.Token("Enemies remaining: 0",.65,.05,.95,.12)),v);
        assertEquals(0,first.remainingEnemies);assertTrue(first.remainingEnemiesConfidence>.9);
        FarmEngine.Frame skipped=ScreenInterpreter.interpret(1500,1500,"com.Overcurve.Corebound",null,v);
        assertEquals(-1,skipped.remainingEnemies);assertEquals(0,skipped.remainingEnemiesConfidence,0);
        FarmEngine.Frame newSector=ScreenInterpreter.interpret(2000,2000,"com.Overcurve.Corebound",Arrays.asList(
            new FarmEngine.Token("Sector 2/4",.4,.16,.6,.22)),v);
        assertEquals(-1,newSector.remainingEnemies);assertEquals(0,newSector.completedSector);
    }
    @Test public void splitHudLabelNeedsOneAdjacentNumberOnTheSameLine(){
        PixelVision.Result v=new PixelVision.Result();v.gameplay=true;
        FarmEngine.Frame f=ScreenInterpreter.interpret(1000,1000,"com.Overcurve.Corebound",Arrays.asList(
            new FarmEngine.Token("Remaining enemies",.6,.05,.84,.12),new FarmEngine.Token("12",.86,.05,.91,.12)),v);
        assertEquals(12,f.remainingEnemies);assertEquals(.85,f.remainingEnemiesConfidence,0);
        f=ScreenInterpreter.interpret(1000,1000,"com.Overcurve.Corebound",Arrays.asList(
            new FarmEngine.Token("Remaining enemies",.6,.05,.84,.12),new FarmEngine.Token("12",.86,.16,.91,.23)),v);
        assertEquals(-1,f.remainingEnemies);
    }
    @Test public void arbitraryNumbersAndOffHudAdTextCannotBecomeAnEnemyCount(){
        PixelVision.Result v=new PixelVision.Result();v.gameplay=true;
        FarmEngine.Frame f=ScreenInterpreter.interpret(1000,1000,"com.Overcurve.Corebound",Arrays.asList(
            new FarmEngine.Token("0",.86,.05,.91,.12),new FarmEngine.Token("Enemies left: 0",.5,.5,.9,.6)),v);
        assertEquals(-1,f.remainingEnemies);
        v.gameplay=false;
        f=ScreenInterpreter.interpret(1000,1000,"com.Overcurve.Corebound",Arrays.asList(
            new FarmEngine.Token("Enemies left: 0",.5,.05,.9,.12)),v);
        assertEquals(-1,f.remainingEnemies);
    }
    @Test public void conflictingHudReadingsRemainUnknown(){
        PixelVision.Result v=new PixelVision.Result();v.gameplay=true;
        FarmEngine.Frame f=ScreenInterpreter.interpret(1000,1000,"com.Overcurve.Corebound",Arrays.asList(
            new FarmEngine.Token("Enemies: 4",.6,.03,.85,.09),new FarmEngine.Token("Enemies: 0",.6,.15,.85,.21)),v);
        assertEquals(-1,f.remainingEnemies);assertEquals(0,f.remainingEnemiesConfidence,0);
    }
    @Test public void countFirstAndBotLabelsAreAcceptedOnlyWithTheirWords(){
        PixelVision.Result v=new PixelVision.Result();v.gameplay=true;
        for(String label:new String[]{"Enemies left:5","5 enemies left","Bots remaining:5"}) {
            FarmEngine.Frame f=ScreenInterpreter.interpret(1000,1000,"com.Overcurve.Corebound",Arrays.asList(
                new FarmEngine.Token(label,.6,.05,.95,.12)),v);
            assertEquals(label,5,f.remainingEnemies);
        }
        FarmEngine.Frame unlabelled=ScreenInterpreter.interpret(1000,1000,"com.Overcurve.Corebound",Arrays.asList(
            new FarmEngine.Token("Remaining:5",.6,.05,.95,.12)),v);
        assertEquals(-1,unlabelled.remainingEnemies);
    }
    @Test public void remainingNumberNeedsItsAdjacentEnemyLabel(){
        PixelVision.Result v=new PixelVision.Result();v.gameplay=true;
        FarmEngine.Frame f=ScreenInterpreter.interpret(1000,1000,"com.Overcurve.Corebound",Arrays.asList(
            new FarmEngine.Token("Enemies",.55,.05,.71,.12),new FarmEngine.Token("Remaining:5",.73,.05,.96,.12)),v);
        assertEquals(5,f.remainingEnemies);
    }
    @Test public void zeroOrdinaryEnemiesDoesNotInventSectorOrSpectrumCompletion(){
        PixelVision.Result v=new PixelVision.Result();v.gameplay=true;
        FarmEngine.Frame f=ScreenInterpreter.interpret(1000,1000,"com.Overcurve.Corebound",Arrays.asList(
            new FarmEngine.Token("Enemies left: 0",.6,.05,.95,.12)),v);
        assertEquals(0,f.remainingEnemies);assertEquals(0,f.completedSector);assertFalse(f.endScreen);
        assertFalse(f.filterLoot);assertFalse(f.filterOffer);
    }
}
