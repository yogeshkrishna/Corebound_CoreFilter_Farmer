package com.corefilter.farmer.engine;
import com.corefilter.farmer.vision.PixelVision;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class ScreenInterpreterTest {
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
    @Test public void unrecognizedRareRewardPausesRatherThanDiscards(){
        PixelVision.Result v=new PixelVision.Result();v.rewardButton=true;v.uncertainFilterOffer=true;
        FarmEngine.Frame f=ScreenInterpreter.interpret(1000,1000,"com.Overcurve.Corebound",Arrays.asList(new FarmEngine.Token("Complete!",.4,.03,.6,.15),new FarmEngine.Token("Continue",.22,.81,.46,.95)),v);
        assertEquals(FarmEngine.Kind.PAUSE,new FarmEngine(new FarmEngine.Config()).next(f).kind);
    }
}
