package com.corefilter.farmer;
import android.accessibilityservice.GestureDescription;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class TouchPlanTest {
    @Test public void hookshotsHaveSeparatePressAndReleaseWhileMoving(){
        GestureDescription g=TouchPlan.build(2408,1080,.28,.81,true,.84,.55,3,190,70,450);
        assertEquals(4,g.getStrokeCount());assertEquals(450,g.getStroke(0).getDuration());
        for(int i=1;i<4;i++){assertEquals((i-1)*190,g.getStroke(i).getStartTime());assertEquals(70,g.getStroke(i).getDuration());}
        assertTrue(g.getStroke(1).getStartTime()+g.getStroke(1).getDuration()<g.getStroke(2).getStartTime());
    }
    @Test public void verticalAscentDoesNotAddUnwantedHorizontalTouch(){assertEquals(2,TouchPlan.build(2408,1080,.28,.81,false,.84,.55,2,190,70,420).getStrokeCount());}
    @Test public void longRequestedMovementStillReleasesWithinSevenHundredMs(){GestureDescription g=TouchPlan.build(2408,1080,.28,.81,true,.84,.55,0,190,70,9999);assertEquals(700,g.getStroke(0).getDuration());}
    @Test(expected=IllegalArgumentException.class) public void offscreenTargetIsRejected(){TouchPlan.tap(2408,1080,1.2,.5);}
}
