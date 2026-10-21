package dev.corefilter.pilot.core;

import static org.junit.Assert.*;

import org.junit.Test;

/** Scripted target integration exercises a full repeat transaction, not actual game rewards. */
public final class FarmingLoopTest {
  private static TextToken token(String text, double y) {
    return new TextToken(text, new Box(.3, y, .6, y + .07));
  }

  private static Observation menu(long time, String... labels) {
    return new Observation(
        time,
        Observation.GAME_PACKAGE,
        2344,
        1080,
        java.util.Arrays.stream(labels)
            .map(s -> token(s, s.equals("Continue") || s.equals("PLAY") ? .75 : .3))
            .collect(java.util.stream.Collectors.toList()),
        time,
        Vision.Result.unavailable());
  }

  @Test
  public void resultContinueSelectWorldPlayAndObservedRunRepeatWithoutInventedRewards() {
    Engine e = new Engine(Config.defaults(), Fixtures.calibration());
    e.start(0);
    assertEquals(Action.Kind.MOVE, e.observe(Fixtures.gameplay(0)).kind());
    e.acknowledge(true, 160);
    assertEquals(
        Action.Kind.WAIT, e.observe(menu(2000, "Destroyed!", "Continue", "Bonus Items")).kind());
    assertEquals(
        Action.Kind.TAP, e.observe(menu(3000, "Destroyed!", "Continue", "Bonus Items")).kind());
    e.acknowledge(true, 3080);
    assertEquals(Action.Kind.WAIT, e.observe(menu(4000, "Lost Scrapyard", "Other World")).kind());
    Action select = e.observe(menu(5000, "Lost Scrapyard", "Other World"));
    assertEquals(Action.Kind.TAP, select.kind());
    assertTrue(select.reason().contains("Lost Scrapyard"));
    e.acknowledge(true, 5080);
    String description = "Filled with scraps, this dormant cave lies at the top of the world";
    assertEquals(
        Action.Kind.WAIT, e.observe(menu(6000, "Lost Scrapyard", description, "PLAY")).kind());
    Action play = e.observe(menu(7000, "Lost Scrapyard", description, "PLAY"));
    assertEquals(Action.Kind.TAP, play.kind());
    assertTrue(play.reason().contains("PLAY"));
    e.acknowledge(true, 7080);
    assertEquals(Action.Kind.WAIT, e.observe(menu(8000, "Generating Background...")).kind());
    assertEquals(Action.Kind.MOVE, e.observe(Fixtures.gameplay(10000)).kind());
    e.acknowledge(true, 10160);
    e.observe(menu(12000, "Complete!", "Continue"));
    assertEquals(Action.Kind.TAP, e.observe(menu(13000, "Complete!", "Continue")).kind());
    assertEquals(2, e.stats().endedRuns());
    assertEquals(0, e.stats().filterLabelScreens());
  }
}
