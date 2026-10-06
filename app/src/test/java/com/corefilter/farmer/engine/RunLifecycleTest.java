package com.corefilter.farmer.engine;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;
import static com.corefilter.farmer.engine.FarmEngine.*;

/** Archive removal must not break replay, reward ads, counters or camera resets. */
public class RunLifecycleTest {
    private Frame gameplay(long now){Frame f=new Frame(now,"com.Overcurve.Corebound","",null);f.gameplay=true;f.playerX=.5;f.playerY=.6;f.playerConfidence=.9;f.grounded=true;f.cameraConfidence=.9;return f;}
    private Frame result(long now,boolean death,boolean reward){Frame f=new Frame(now,"com.Overcurve.Corebound",death?"Defeated":"Complete!",Arrays.asList(new Token(reward?"Watch reward":"Continue",.65,.8,.85,.9)));f.endScreen=true;f.filterOffer=reward;return f;}
    @Test public void completionIsCountedOnce(){FarmEngine e=new FarmEngine(new Config());e.next(gameplay(0));e.next(result(1000,false,false));e.next(result(2000,false,false));assertEquals(1,e.completedRuns());assertEquals(0,e.deaths());}
    @Test public void defeatDoesNotInventSuccess(){FarmEngine e=new FarmEngine(new Config());e.next(gameplay(0));e.next(result(1000,true,false));assertEquals(0,e.completedRuns());assertEquals(1,e.deaths());}
    @Test public void filterRewardStillStartsAd(){FarmEngine e=new FarmEngine(new Config());e.next(gameplay(0));e.next(result(1000,false,true));assertEquals(State.REWARD_AD,e.state());assertEquals(1,e.completedRuns());}
    @Test public void onlyRunStartRequestsCameraReset(){FarmEngine e=new FarmEngine(new Config());e.next(gameplay(0));assertFalse(e.takeRunStartRequest());Frame drop=gameplay(1000);drop.grounded=false;drop.playerY=.8;drop.completedSector=1;e.next(drop);assertFalse(e.takeRunStartRequest());e.next(result(2000,false,false));assertFalse(e.takeRunStartRequest());Frame select=new Frame(3000,"com.Overcurve.Corebound","",Arrays.asList(new Token("Play",.65,.8,.85,.9)));select.targetSelected=true;assertEquals(Kind.TAP,e.next(select).kind);assertTrue(e.takeRunStartRequest());assertFalse(e.takeRunStartRequest());}
    @Test public void stoppingIsNotCompletion(){FarmEngine e=new FarmEngine(new Config());e.next(gameplay(0));e.stop();e.stop();assertEquals(0,e.completedRuns());assertEquals(0,e.deaths());}
}
