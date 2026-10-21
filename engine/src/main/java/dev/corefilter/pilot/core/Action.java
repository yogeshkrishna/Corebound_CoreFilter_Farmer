package dev.corefilter.pilot.core;

import java.util.Objects;

/** One bounded input transaction. The platform must acknowledge completion. */
public record Action(
    Kind kind, int direction, boolean jump, int durationMs, Point target, String reason) {
  public enum Kind {
    WAIT,
    MOVE,
    TAP,
    PAUSE,
    STOP
  }

  public Action {
    Objects.requireNonNull(kind);
    Objects.requireNonNull(reason);
    if (direction < -1 || direction > 1 || durationMs < 0 || durationMs > 180000)
      throw new IllegalArgumentException("Invalid action");
    if ((kind == Kind.TAP) != (target != null))
      throw new IllegalArgumentException("Only a tap has a target");
    if (kind == Kind.MOVE && (durationMs < 60 || durationMs > 600 || (direction == 0 && !jump)))
      throw new IllegalArgumentException("Invalid movement pulse");
    if (kind != Kind.MOVE && (direction != 0 || jump))
      throw new IllegalArgumentException("Unexpected movement fields");
  }

  public static Action waitFor(int ms, String why) {
    return new Action(Kind.WAIT, 0, false, ms, null, why);
  }

  public static Action move(int dir, boolean jump, int ms, String why) {
    return new Action(Kind.MOVE, dir, jump, ms, null, why);
  }

  public static Action tap(Point p, String why) {
    return new Action(Kind.TAP, 0, false, 80, p, why);
  }

  public static Action pause(String why) {
    return new Action(Kind.PAUSE, 0, false, 0, null, why);
  }

  public static Action stop(String why) {
    return new Action(Kind.STOP, 0, false, 0, null, why);
  }
}
