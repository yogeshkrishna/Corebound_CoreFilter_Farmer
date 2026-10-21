package dev.corefilter.pilot.core;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public final class EngineTest {
  private static Engine started() {
    Engine engine = new Engine(Config.defaults(), Fixtures.calibration());
    engine.start(0);
    return engine;
  }

  @Test
  public void stoppedEngineNeverIssuesInput() {
    Engine engine = new Engine(Config.defaults(), Fixtures.calibration());
    assertEquals(Action.Kind.STOP, engine.observe(Fixtures.gameplay(100)).kind());
    assertEquals(0, engine.stats().observations());
  }

  @Test
  public void resultsRequireTwoDifferentOcrSamples() {
    Engine engine = started();
    TextToken done = Fixtures.token("Run ended"), retry = Fixtures.token("Retry");
    assertEquals(Action.Kind.WAIT, engine.observe(Fixtures.menu(1000, done, retry)).kind());
    assertEquals(
        Action.Kind.WAIT,
        engine
            .observe(Fixtures.observation(1400, 1000, Vision.Result.unavailable(), done, retry))
            .kind());
    assertEquals(Action.Kind.TAP, engine.observe(Fixtures.menu(1800, done, retry)).kind());
    assertEquals(1, engine.stats().endedRuns());
    assertEquals(1, engine.stats().taps());
  }

  @Test
  public void duplicateFramesCannotCompleteMenuConfirmation() {
    Engine engine = started();
    Observation observation =
        Fixtures.menu(1000, Fixtures.token("Destroyed"), Fixtures.token("Continue"));
    engine.observe(observation);
    assertEquals(Action.Kind.WAIT, engine.observe(observation).kind());
    assertEquals(1, engine.stats().observations());
    assertEquals(0, engine.stats().taps());
  }

  @Test
  public void staleTextCannotAuthorizeMenuInput() {
    Engine engine = started();
    TextToken done = Fixtures.token("Run ended"), retry = Fixtures.token("Retry");
    engine.observe(Fixtures.observation(5000, 1000, Vision.Result.unavailable(), done, retry));
    assertEquals(
        Action.Kind.WAIT,
        engine
            .observe(Fixtures.observation(5400, 1000, Vision.Result.unavailable(), done, retry))
            .kind());
    assertEquals(0, engine.stats().taps());
  }

  @Test
  public void pendingMovementIsNeverDuplicatedBeforeAcknowledgement() {
    Engine engine = started();
    assertEquals(Action.Kind.MOVE, engine.observe(Fixtures.gameplay(1000)).kind());
    assertEquals(Action.Kind.WAIT, engine.observe(Fixtures.gameplay(1400)).kind());
    assertEquals(1, engine.stats().movements());
    engine.acknowledge(true, 1500);
    assertEquals(Action.Kind.MOVE, engine.observe(Fixtures.gameplay(1800)).kind());
    assertEquals(2, engine.stats().movements());
  }

  @Test
  public void retriesAreSpacedAndBounded() {
    Engine engine = started();
    TextToken done = Fixtures.token("Destroyed"), retry = Fixtures.token("Retry");
    engine.observe(Fixtures.menu(1000, done, retry));
    assertEquals(Action.Kind.TAP, engine.observe(Fixtures.menu(1400, done, retry)).kind());
    engine.acknowledge(true, 1500);
    assertEquals(Action.Kind.WAIT, engine.observe(Fixtures.menu(1800, done, retry)).kind());
    assertEquals(Action.Kind.TAP, engine.observe(Fixtures.menu(4000, done, retry)).kind());
    engine.acknowledge(true, 4100);
    assertEquals(Action.Kind.TAP, engine.observe(Fixtures.menu(6600, done, retry)).kind());
    engine.acknowledge(true, 6700);
    assertEquals(Action.Kind.PAUSE, engine.observe(Fixtures.menu(9200, done, retry)).kind());
    assertEquals(3, engine.stats().taps());
    assertEquals(1, engine.stats().endedRuns());
  }

  @Test
  public void inputFailuresPauseAfterConfiguredBound() {
    Engine engine = started();
    for (int i = 0; i < 3; i++) {
      long now = 1000 + i * 400L;
      assertEquals(Action.Kind.MOVE, engine.observe(Fixtures.gameplay(now)).kind());
      engine.acknowledge(false, now + 50);
    }
    assertEquals(Engine.State.PAUSED, engine.state());
    assertEquals(3, engine.stats().inputFailures());
  }

  @Test
  public void successfulInputResetsConsecutiveFailureCount() {
    Engine engine = started();
    for (int i = 0; i < 5; i++) {
      long now = 1000 + i * 400L;
      engine.observe(Fixtures.gameplay(now));
      engine.acknowledge(i == 2, now + 20);
    }
    assertEquals(Engine.State.PLAYING, engine.state());
    assertEquals(4, engine.stats().inputFailures());
  }

  @Test
  public void acknowledgementsWithoutPendingInputHaveNoEffect() {
    Engine engine = started();
    engine.acknowledge(false, 1000);
    assertEquals(0, engine.stats().inputFailures());
    assertEquals(Engine.State.OBSERVING, engine.state());
  }

  @Test
  public void pauseResumeAndStopRequireNewScreenConfirmation() {
    Engine engine = started();
    engine.observe(Fixtures.menu(1000, Fixtures.token("Destroyed"), Fixtures.token("Retry")));
    engine.pause("User pause");
    assertEquals(Action.Kind.PAUSE, engine.observe(Fixtures.gameplay(1400)).kind());
    engine.resume(1800);
    assertEquals(
        Action.Kind.WAIT,
        engine
            .observe(Fixtures.menu(1800, Fixtures.token("Destroyed"), Fixtures.token("Retry")))
            .kind());
    engine.stop("User stop");
    assertEquals(Action.Kind.STOP, engine.observe(Fixtures.gameplay(2200)).kind());
    engine.resume(2400);
    assertEquals(Engine.State.STOPPED, engine.state());
  }

  @Test
  public void leavingGamePausesEvenWhileInputIsPending() {
    Engine engine = started();
    engine.observe(Fixtures.gameplay(1000));
    Observation other =
        new Observation(1400, "com.android.settings", 2344, 1080, List.of(), -1, Fixtures.game());
    assertEquals(Action.Kind.PAUSE, engine.observe(other).kind());
    assertEquals(1, engine.stats().movements());
  }

  @Test
  public void changedDisplayAndPortraitPauseBeforeAnyInput() {
    for (int[] dimensions : new int[][] {{1080, 2344}, {1600, 1000}}) {
      Engine engine = started();
      Observation changed =
          new Observation(
              1000,
              Observation.GAME_PACKAGE,
              dimensions[0],
              dimensions[1],
              List.of(),
              -1,
              Fixtures.game());
      assertEquals(Action.Kind.PAUSE, engine.observe(changed).kind());
      assertEquals(0, engine.stats().movements());
    }
  }

  @Test
  public void sameAspectResolutionIsAccepted() {
    Engine engine = started();
    Observation half =
        new Observation(1000, Observation.GAME_PACKAGE, 1172, 540, List.of(), -1, Fixtures.game());
    assertEquals(Action.Kind.MOVE, engine.observe(half).kind());
  }

  @Test
  public void unsafeScreenOverridesOtherwiseValidGameplay() {
    Engine engine = started();
    Observation purchase =
        Fixtures.observation(
            1000, 1000, Fixtures.game(), Fixtures.token("Purchase"), Fixtures.token("Buy"));
    assertEquals(Action.Kind.PAUSE, engine.observe(purchase).kind());
    assertEquals(0, engine.stats().movements());
  }

  @Test
  public void unknownScreenEventuallyPausesWithoutInput() {
    Engine engine = started();
    assertEquals(Action.Kind.WAIT, engine.observe(Fixtures.menu(1000)).kind());
    assertEquals(Action.Kind.PAUSE, engine.observe(Fixtures.menu(16000)).kind());
    assertEquals(0, engine.stats().taps());
  }

  @Test
  public void loadingHasFiniteDeadline() {
    Engine engine = started();
    assertEquals(
        Action.Kind.WAIT,
        engine.observe(Fixtures.menu(1000, Fixtures.token("Generating world"))).kind());
    assertEquals(
        Action.Kind.PAUSE,
        engine.observe(Fixtures.menu(61400, Fixtures.token("Generating world"))).kind());
  }

  @Test
  public void sessionDeadlineStopsFurtherInput() {
    Config defaults = Config.defaults();
    Config oneMinute =
        new Config(
            defaults.captureIntervalMs(),
            defaults.gestureMs(),
            defaults.unknownTimeoutMs(),
            defaults.stuckTimeoutMs(),
            defaults.maxRecoveryAttempts(),
            defaults.adTimeoutMs(),
            1,
            false,
            0);
    Engine engine = new Engine(oneMinute, Fixtures.calibration());
    engine.start(0);
    assertEquals(Action.Kind.STOP, engine.observe(Fixtures.gameplay(60000)).kind());
    assertEquals(0, engine.stats().movements());
  }

  @Test
  public void endedRunAndFilterLabelsAreNotCountedPerFrame() {
    Engine engine = started();
    TextToken done = Fixtures.token("Run ended"), filter = Fixtures.token("Core Filters");
    engine.observe(Fixtures.menu(1000, done, filter));
    engine.observe(Fixtures.menu(1400, done, filter));
    engine.observe(Fixtures.menu(1800, done, filter));
    assertEquals(1, engine.stats().endedRuns());
    assertEquals(1, engine.stats().filterLabelScreens());
    engine.observe(Fixtures.gameplay(2200));
    engine.acknowledge(true, 2250);
    engine.observe(Fixtures.menu(2600, done, filter));
    engine.observe(Fixtures.menu(3000, done, filter));
    assertEquals(2, engine.stats().endedRuns());
    assertEquals(2, engine.stats().filterLabelScreens());
  }

  @Test
  public void adsAreDeclinedByDefaultOnlyThroughObservedCancel() {
    Engine engine = started();
    TextToken title = Fixtures.token("Watch ad"), cancel = Fixtures.token("Cancel");
    engine.observe(Fixtures.menu(1000, title, cancel));
    Action action = engine.observe(Fixtures.menu(1400, title, cancel));
    assertEquals(Action.Kind.TAP, action.kind());
    assertEquals(cancel.bounds().center(), action.target());
    assertNotEquals(Engine.State.AD_WAITING, engine.state());
  }

  @Test
  public void expectedAdRequiresPositiveCompletionBeforeClose() {
    Engine engine = new Engine(Fixtures.config(true, 0), Fixtures.calibration());
    engine.start(0);
    TextToken title = Fixtures.token("Watch ad"), yes = Fixtures.token("Yes");
    engine.observe(Fixtures.menu(1000, title, yes));
    assertEquals(Action.Kind.TAP, engine.observe(Fixtures.menu(1400, title, yes)).kind());
    engine.acknowledge(true, 1450);
    assertEquals(
        Action.Kind.WAIT, engine.observe(Fixtures.menu(1800, Fixtures.token("Close"))).kind());
    assertEquals(
        Action.Kind.WAIT, engine.observe(Fixtures.menu(2200, Fixtures.token("Close"))).kind());
    engine.observe(Fixtures.menu(3000, Fixtures.token("Reward earned"), Fixtures.token("Close")));
    assertEquals(
        Action.Kind.TAP,
        engine
            .observe(Fixtures.menu(3400, Fixtures.token("Reward earned"), Fixtures.token("Close")))
            .kind());
  }

  @Test
  public void adWaitHasBoundedDeadline() {
    Engine engine = new Engine(Fixtures.config(true, 0), Fixtures.calibration());
    engine.start(0);
    engine.observe(Fixtures.menu(1000, Fixtures.token("Watch ad"), Fixtures.token("Yes")));
    engine.observe(Fixtures.menu(1400, Fixtures.token("Watch ad"), Fixtures.token("Yes")));
    engine.acknowledge(true, 1450);
    assertEquals(Action.Kind.PAUSE, engine.observe(Fixtures.menu(121800)).kind());
    assertEquals(1, engine.stats().taps());
  }

  @Test
  public void resumeAfterManuallyResolvedAdDiscardsExpiredAdContext() {
    Engine engine = new Engine(Fixtures.config(true, 0), Fixtures.calibration());
    engine.start(0);
    engine.observe(Fixtures.menu(1000, Fixtures.token("Watch ad"), Fixtures.token("Yes")));
    assertEquals(
        Action.Kind.TAP,
        engine
            .observe(Fixtures.menu(1400, Fixtures.token("Watch ad"), Fixtures.token("Yes")))
            .kind());
    engine.acknowledge(true, 1450);
    assertEquals(Action.Kind.PAUSE, engine.observe(Fixtures.menu(121800)).kind());
    assertEquals(Engine.State.PAUSED, engine.state());

    engine.resume(122000);
    assertEquals(Engine.State.OBSERVING, engine.state());
    assertEquals(Action.Kind.MOVE, engine.observe(Fixtures.gameplay(122400)).kind());
    assertEquals(Engine.State.PLAYING, engine.state());
    assertEquals(1, engine.stats().taps());
    assertEquals(1, engine.stats().movements());
  }

  @Test
  public void confirmedLostScrapyardSelectionTapsOnlyObservedPlay() {
    Engine engine = started();
    TextToken name = Fixtures.token("Lost Scrapyard");
    TextToken description = Fixtures.token("A dormant cave at the top of the world");
    TextToken play = Fixtures.token("Play");
    engine.observe(Fixtures.menu(1000, name, description, play));
    Action action = engine.observe(Fixtures.menu(1400, name, description, play));
    assertEquals(Action.Kind.TAP, action.kind());
    assertEquals(play.bounds().center(), action.target());
  }

  @Test
  public void freshStartClearsPriorSessionCounters() {
    Engine engine = started();
    engine.observe(Fixtures.gameplay(1000));
    engine.acknowledge(false, 1050);
    engine.start(2000);
    assertEquals(0, engine.stats().observations());
    assertEquals(0, engine.stats().inputFailures());
    assertEquals(Engine.State.OBSERVING, engine.state());
  }
}
