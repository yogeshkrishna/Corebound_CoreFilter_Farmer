package dev.corefilter.pilot;

import static org.junit.Assert.*;

import android.accessibilityservice.GestureDescription;
import dev.corefilter.pilot.core.Action;
import dev.corefilter.pilot.core.Calibration;
import dev.corefilter.pilot.core.PixelTemplate;
import dev.corefilter.pilot.core.Point;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 35)
public final class TouchPlanTest {
  private static Calibration controls() {
    int[] patch = new int[25];
    for (int i = 0; i < 25; i++) patch[i] = i % 2 == 0 ? 0xff202020 : 0xffc0c0c0;
    PixelTemplate t = new PixelTemplate(5, patch);
    return new Calibration(
        new Point(.15, .8), new Point(.25, .8), new Point(.8, .8), t, t, 2344, 1080);
  }

  @Test
  public void movementAndJumpAreSimultaneousFiniteTouches() {
    GestureDescription g =
        TouchPlan.build(Action.move(1, true, 160, "test"), controls(), 2344, 1080);
    assertEquals(2, g.getStrokeCount());
    for (int i = 0; i < 2; i++) {
      assertEquals(0, g.getStroke(i).getStartTime());
      assertEquals(160, g.getStroke(i).getDuration());
      assertFalse(g.getStroke(i).willContinue());
    }
  }

  @Test
  public void repeatedJumpPlansAreIndependentPressReleaseCycles() {
    GestureDescription a =
        TouchPlan.build(Action.move(0, true, 160, "one"), controls(), 2344, 1080);
    GestureDescription b =
        TouchPlan.build(Action.move(0, true, 160, "two"), controls(), 2344, 1080);
    assertEquals(1, a.getStrokeCount());
    assertEquals(1, b.getStrokeCount());
    assertNotSame(a.getStroke(0), b.getStroke(0));
    assertFalse(a.getStroke(0).willContinue());
    assertFalse(b.getStroke(0).willContinue());
  }

  @Test
  public void menuTapIsOneShortTouch() {
    GestureDescription g =
        TouchPlan.build(Action.tap(new Point(.7, .6), "Continue"), controls(), 2344, 1080);
    assertEquals(1, g.getStrokeCount());
    assertEquals(80, g.getStroke(0).getDuration());
  }

  @Test
  public void waitNeverBecomesARealTouch() {
    assertThrows(
        IllegalArgumentException.class,
        () -> TouchPlan.build(Action.waitFor(400, "wait"), controls(), 2344, 1080));
  }

  @Test
  public void portraitDisplayIsRejectedBeforeInput() {
    assertThrows(
        IllegalArgumentException.class,
        () -> TouchPlan.build(Action.move(1, true, 160, "test"), controls(), 1080, 2344));
  }
}
