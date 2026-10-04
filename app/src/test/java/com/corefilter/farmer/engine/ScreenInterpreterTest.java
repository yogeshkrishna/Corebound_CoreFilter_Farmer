package com.corefilter.farmer.engine;
import com.corefilter.farmer.vision.PixelVision;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class ScreenInterpreterTest {
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
}
