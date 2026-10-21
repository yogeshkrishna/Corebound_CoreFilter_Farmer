package dev.corefilter.pilot.core;

import static org.junit.Assert.*;

import org.junit.Test;

public final class NavigatorTest {
  @Test
  public void groundedJumpPulsesHaveAReleaseInterval() {
    Navigator navigator = new Navigator(Config.defaults());
    navigator.reset(0);
    Action first = navigator.next(Fixtures.game(), 0);
    Action next = navigator.next(Fixtures.game(), 400);
    assertFalse("Ground contact is confirmed across two observations", first.jump());
    assertTrue(next.jump());
    assertTrue(next.durationMs() < Config.defaults().captureIntervalMs());
    assertFalse(navigator.next(Fixtures.game(), 800).jump());
    assertTrue(navigator.next(Fixtures.game(), 1600).jump());
  }

  @Test
  public void noAirJumpsAreAssumedByDefault() {
    Navigator navigator = new Navigator(Config.defaults());
    navigator.reset(0);
    for (long time : new long[] {0, 1000, 2000, 3000}) {
      Action action = navigator.next(Fixtures.view(false, false, false, false, .01, .03), time);
      assertEquals(Action.Kind.MOVE, action.kind());
      assertFalse(action.jump());
    }
  }

  @Test
  public void configuredAirJumpBudgetIsNotExceeded() {
    Navigator navigator = new Navigator(Fixtures.config(false, 2));
    navigator.reset(0);
    Vision.Result air = Fixtures.view(false, false, false, false, .01, .03);
    assertTrue(navigator.next(air, 0).jump());
    assertFalse(navigator.next(air, 400).jump());
    assertTrue(navigator.next(air, 900).jump());
    assertFalse(navigator.next(air, 1800).jump());
    assertFalse(navigator.next(air, 3000).jump());
  }

  @Test
  public void recoveryCannotBypassZeroAirJumpBudget() {
    Navigator navigator = new Navigator(Config.defaults());
    navigator.reset(0);
    Vision.Result suspended = Fixtures.view(false, false, false, false, .01, 0);
    navigator.next(suspended, 0);
    Action recovery = navigator.next(suspended, 8000);
    assertFalse("Recovery must respect the configured zero extra-jump budget", recovery.jump());
  }

  @Test
  public void recoveryCannotBypassExhaustedAirJumpBudget() {
    Navigator navigator = new Navigator(Fixtures.config(false, 1));
    navigator.reset(0);
    Vision.Result suspended = Fixtures.view(false, false, false, false, .01, 0);
    assertTrue(navigator.next(suspended, 0).jump());
    Action recovery = navigator.next(suspended, 8000);
    assertFalse("Stuck recovery must not invent another equipment jump", recovery.jump());
  }

  @Test
  public void twoStableGroundObservationsRestoreAirJumpBudget() {
    Navigator navigator = new Navigator(Fixtures.config(false, 1));
    navigator.reset(0);
    Vision.Result air = Fixtures.view(false, false, false, false, .01, .03);
    assertTrue(navigator.next(air, 0).jump());
    navigator.next(Fixtures.game(), 1000);
    navigator.next(Fixtures.game(), 1400);
    assertTrue(navigator.next(air, 2400).jump());
  }

  @Test
  public void ceilingBlocksOrdinaryJumpRequests() {
    Navigator navigator = new Navigator(Fixtures.config(false, 2));
    navigator.reset(0);
    Action action = navigator.next(Fixtures.view(true, true, false, false, 0, .03), 0);
    assertEquals(Action.Kind.MOVE, action.kind());
    assertFalse(action.jump());
  }

  @Test
  public void blockedWallWaitsThenRecoveryIsBounded() {
    Navigator navigator = new Navigator(Config.defaults());
    navigator.reset(0);
    Vision.Result blocked = Fixtures.view(true, true, true, true, 0, 0);
    assertEquals(Action.Kind.WAIT, navigator.next(blocked, 0).kind());
    assertEquals(Action.Kind.MOVE, navigator.next(blocked, 8000).kind());
    assertEquals(-1, navigator.next(blocked, 16000).direction());
    assertEquals(Action.Kind.MOVE, navigator.next(blocked, 24000).kind());
    assertEquals(Action.Kind.PAUSE, navigator.next(blocked, 32000).kind());
  }

  @Test
  public void unreliableTerrainCannotDriveAnUnboundedPatrol() {
    Navigator navigator = new Navigator(Config.defaults());
    navigator.reset(0);
    Vision.Result bad =
        new Vision.Result(true, new Point(.5, .5), .95, false, false, false, false, .2, 0, 0);
    assertEquals(Action.Kind.WAIT, navigator.next(bad, 0).kind());
    assertEquals(Action.Kind.PAUSE, navigator.next(bad, 4001).kind());
  }

  @Test
  public void visibleProgressDefersStuckRecovery() {
    Navigator navigator = new Navigator(Config.defaults());
    navigator.reset(0);
    navigator.next(Fixtures.game(), 0);
    Action action = navigator.next(Fixtures.game(), 8000);
    assertEquals(Action.Kind.MOVE, action.kind());
    assertTrue(action.reason().contains("patrol"));
  }
}
