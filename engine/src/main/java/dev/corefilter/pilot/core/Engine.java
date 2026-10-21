package dev.corefilter.pilot.core;

/** Deterministic observation/action state machine. No platform I/O or timers are hidden here. */
public final class Engine {
  public enum State {
    STOPPED,
    PAUSED,
    OBSERVING,
    PLAYING,
    RESULTS,
    AD_WAITING
  }

  public record Stats(
      long observations,
      long movements,
      long taps,
      long endedRuns,
      long filterLabelScreens,
      long inputFailures,
      long elapsedMs) {}

  private final Config config;
  private final Calibration calibration;
  private final ScreenInterpreter interpreter = new ScreenInterpreter();
  private final Navigator navigator;
  private State state = State.STOPPED;
  private String status = "Stopped", stableKey = "", tapKey = "";
  private long startedAt,
      lastTime = -1,
      unknownAt = -1,
      stableTextTime = -1,
      lastTapAt = -10000,
      adAt = -1;
  private int stableCount, tapAttempts, consecutiveInputFailures, menuTaps;
  private boolean pending, runCounted, filterCounted, adExpected;
  private long observations, movements, taps, completedRuns, filterLabels, inputFailures;

  public Engine(Config config, Calibration calibration) {
    this.config = config;
    this.calibration = calibration;
    navigator = new Navigator(config);
  }

  public synchronized void start(long now) {
    if (now < 0) throw new IllegalArgumentException("Invalid clock");
    state = State.OBSERVING;
    startedAt = now;
    lastTime = -1;
    unknownAt = -1;
    stableTextTime = -1;
    observations = movements = taps = completedRuns = filterLabels = inputFailures = 0;
    pending = false;
    runCounted = false;
    filterCounted = false;
    adExpected = false;
    adAt = -1;
    stableKey = "";
    tapKey = "";
    stableCount = tapAttempts = consecutiveInputFailures = 0;
    menuTaps = 0;
    navigator.reset(now);
    status = "Observing; start a Lost Scrapyard run in Corebound";
  }

  public synchronized void pause(String why) {
    state = State.PAUSED;
    status = why;
    pending = false;
  }

  public synchronized void resume(long now) {
    if (state != State.PAUSED) return;
    state = State.OBSERVING;
    unknownAt = -1;
    stableCount = 0;
    stableKey = "";
    stableTextTime = -1;
    pending = false;
    consecutiveInputFailures = 0;
    adExpected = false;
    adAt = -1;
    menuTaps = 0;
    navigator.reset(now);
    status = "Resumed; verifying the current screen";
  }

  public synchronized void stop(String why) {
    state = State.STOPPED;
    status = why;
    pending = false;
  }

  public synchronized State state() {
    return state;
  }

  public synchronized String status() {
    return status;
  }

  public synchronized Stats stats() {
    return new Stats(
        observations,
        movements,
        taps,
        completedRuns,
        filterLabels,
        inputFailures,
        Math.max(0, lastTime - startedAt));
  }

  public synchronized Action observe(Observation observation) {
    long now = observation.timeMs();
    if (state == State.STOPPED) return Action.stop(status);
    if (state == State.PAUSED) return Action.pause(status);
    if (now <= lastTime)
      return Action.waitFor(config.captureIntervalMs(), "Waiting for a new frame");
    lastTime = now;
    observations++;
    if (now - startedAt >= config.maxSessionMinutes() * 60000L)
      return stopAction("Session time limit reached");
    if (!Observation.GAME_PACKAGE.equals(observation.foregroundPackage()))
      return pauseAction("Corebound is no longer in the foreground");
    if (observation.width() <= observation.height()
        || !calibration.matchesDisplay(observation.width(), observation.height()))
      return pauseAction("Display layout changed. Calibrate in the current landscape orientation.");
    if (pending) return Action.waitFor(config.captureIntervalMs(), "Waiting for input completion");
    ScreenInterpreter.Reading reading =
        interpreter.read(observation, adExpected, config.watchRewardAds());
    if (reading.screen() == ScreenInterpreter.Screen.UNSAFE)
      return pauseAction("An unrelated confirmation or purchase screen needs your attention");
    if (!reading.identity().equals(stableKey)) {
      stableKey = reading.identity();
      stableCount = 0;
      stableTextTime = -1;
      tapAttempts = 0;
      tapKey = "";
    }
    if (observation.textFresh() && observation.textTimeMs() != stableTextTime) {
      stableTextTime = observation.textTimeMs();
      stableCount++;
    }
    if (reading.filterLabel() && !filterCounted) {
      filterLabels++;
      filterCounted = true;
    }
    if (!reading.filterLabel()) filterCounted = false;
    switch (reading.screen()) {
      case GAME -> {
        state = State.PLAYING;
        unknownAt = -1;
        adExpected = false;
        adAt = -1;
        menuTaps = 0;
        if (runCounted) {
          runCounted = false;
          navigator.reset(now);
        }
        Action action = navigator.next(observation.vision(), now);
        return issue(action);
      }
      case RESULTS -> {
        state = State.RESULTS;
        adExpected = false;
        adAt = -1;
        if (stableCount < 2) return waitAction("Confirming the result screen");
        if (!runCounted) {
          completedRuns++;
          runCounted = true;
        }
        if (reading.action() == null)
          return uncertain(now, "Result screen has no recognized Continue or Retry button");
        unknownAt = -1;
        return tap(reading.action(), now, reading.identity());
      }
      case AD_CONFIRM -> {
        if (stableCount < 2) return waitAction("Confirming the reward-video dialog");
        if (reading.action() == null)
          return pauseAction(
              "Reward-video dialog needs manual confirmation; no verified free button was found");
        Action action = tap(reading.action(), now, reading.identity());
        if (action.kind() == Action.Kind.TAP && config.watchRewardAds()) {
          adExpected = true;
          adAt = now;
          state = State.AD_WAITING;
        }
        return action;
      }
      case AD -> {
        state = State.AD_WAITING;
        if (adAt < 0) adAt = now;
        if (now - adAt > config.adTimeoutMs())
          return pauseAction(
              "Reward video exceeded its time limit. Close it manually, then resume.");
        if (reading.action() != null && stableCount >= 2) {
          Action close = tap(reading.action(), now, reading.identity());
          if (close.kind() == Action.Kind.TAP) {
            adExpected = false;
            adAt = -1;
          }
          return close;
        }
        return waitAction("Waiting for a positively identified video completion and close button");
      }
      case PAUSED -> {
        return pauseAction("Corebound is paused. Resume it, then resume Pilot.");
      }
      case WORLD -> {
        if (stableCount < 2) return waitAction("Confirming Lost Scrapyard selection");
        if (reading.action() == null)
          return uncertain(now, "Select Lost Scrapyard and your chosen boost, then enter the run");
        unknownAt = -1;
        return tap(reading.action(), now, reading.identity());
      }
      case LOADING -> {
        if (unknownAt < 0) unknownAt = now;
        return now - unknownAt > 60000
            ? pauseAction("World loading exceeded one minute")
            : waitAction("Waiting for world generation");
      }
      case UNKNOWN -> {
        return uncertain(now, "Screen not recognized; no input issued");
      }
      default -> throw new IllegalStateException("Unhandled screen");
    }
  }

  public synchronized void acknowledge(boolean success, long now) {
    if (!pending) return;
    pending = false;
    if (success) {
      consecutiveInputFailures = 0;
      return;
    }
    inputFailures++;
    consecutiveInputFailures++;
    if (consecutiveInputFailures >= config.maxRecoveryAttempts())
      pause("Android repeatedly cancelled input. Check accessibility and other touch activity.");
    if (now > lastTime) lastTime = now;
  }

  private Action tap(TextToken token, long now, String identity) {
    String key =
        identity
            + ":"
            + Math.round(token.bounds().center().x() * 40)
            + ":"
            + Math.round(token.bounds().center().y() * 40);
    if (!tapKey.equals(key)) {
      tapKey = key;
      tapAttempts = 0;
      lastTapAt = -10000;
    }
    if (now - lastTapAt < 2500) return waitAction("Waiting for the tapped screen to change");
    if (tapAttempts >= config.maxRecoveryAttempts())
      return pauseAction("The recognized button did not change the screen after bounded retries");
    if (menuTaps >= config.maxRecoveryAttempts() * 3)
      return pauseAction("Too many menu actions without entering a verified run");
    tapAttempts++;
    menuTaps++;
    lastTapAt = now;
    return issue(Action.tap(token.bounds().center(), "Observed button: " + token.text()));
  }

  private Action uncertain(long now, String why) {
    if (unknownAt < 0) unknownAt = now;
    if (now - unknownAt >= config.unknownTimeoutMs())
      return pauseAction(why + ". Resume after resolving it.");
    return waitAction(why);
  }

  private Action waitAction(String why) {
    status = why;
    return Action.waitFor(config.captureIntervalMs(), why);
  }

  private Action pauseAction(String why) {
    pause(why);
    return Action.pause(why);
  }

  private Action stopAction(String why) {
    stop(why);
    return Action.stop(why);
  }

  private Action issue(Action action) {
    status = action.reason();
    if (action.kind() == Action.Kind.PAUSE) {
      pause(action.reason());
      return action;
    }
    if (action.kind() == Action.Kind.MOVE) {
      movements++;
      pending = true;
    }
    if (action.kind() == Action.Kind.TAP) {
      taps++;
      pending = true;
    }
    return action;
  }
}
