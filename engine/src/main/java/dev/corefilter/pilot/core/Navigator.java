package dev.corefilter.pilot.core;

/**
 * Reactive patrol of visible space. It does not assume a seed, seven jumps, or full map coverage.
 */
final class Navigator {
  private final Config config;
  private int direction = 1, recoveries, airJumps, groundFrames;
  private long progressAt, lastJump = -10000, uncertainAt = -1;
  private Point previous;

  Navigator(Config config) {
    this.config = config;
  }

  void reset(long now) {
    direction = 1;
    recoveries = 0;
    airJumps = 0;
    groundFrames = 0;
    previous = null;
    progressAt = now;
    lastJump = now - 10000;
    uncertainAt = -1;
  }

  Action next(Vision.Result view, long now) {
    if (view.terrainConfidence() < .65) {
      if (uncertainAt < 0) uncertainAt = now;
      return now - uncertainAt > 4000
          ? Action.pause(
              "Terrain cannot be distinguished from scenery. Recalibrate on the main floor.")
          : Action.waitFor(
              config.captureIntervalMs(), "Waiting for a reliable terrain observation");
    }
    uncertainAt = -1;
    if (view.sceneChange() > .012 || (previous != null && previous.distance(view.player()) > .012))
      progressAt = now;
    previous = view.player();
    if (view.groundBelow() && Math.abs(view.verticalDelta()) < .015) groundFrames++;
    else groundFrames = 0;
    if (groundFrames >= 2) airJumps = 0;
    boolean wall = direction > 0 ? view.wallRight() : view.wallLeft();
    if (now - progressAt >= config.stuckTimeoutMs()) {
      recoveries++;
      progressAt = now;
      if (recoveries > config.maxRecoveryAttempts())
        return Action.pause("No observed progress after bounded recovery attempts");
      if (recoveries > 1) direction = -direction;
      if (!view.ceilingAbove() && (view.groundBelow() || airJumps < config.maxAirJumps())) {
        if (!view.groundBelow()) airJumps++;
        lastJump = now;
        return Action.move(
            direction, true, config.gestureMs(), "Recovering from observed lack of movement");
      }
      return Action.move(
          direction, false, config.gestureMs(), "Turning away from a blocked corridor");
    }
    boolean groundedJump = groundFrames >= 2 && now - lastJump >= 1000;
    boolean airJump =
        !view.groundBelow()
            && view.verticalDelta() > .003
            && now - lastJump >= 850
            && airJumps < config.maxAirJumps();
    boolean jump = !view.ceilingAbove() && (groundedJump || airJump);
    if (jump) {
      if (airJump) airJumps++;
      lastJump = now;
    }
    if (wall && view.ceilingAbove()) {
      // Observe the obstruction before changing direction; a gate can open on its own.
      return Action.waitFor(
          config.captureIntervalMs(), "Wall and ceiling ahead; waiting before bounded recovery");
    }
    return Action.move(
        direction,
        jump,
        config.gestureMs(),
        jump
            ? "Jump patrol; touch will release before the next jump"
            : "Following visible corridor");
  }
}
