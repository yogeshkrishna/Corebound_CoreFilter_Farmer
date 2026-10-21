package dev.corefilter.pilot.core;

/** All bounded-loop policies live here. Movement is observed, not timed as a route. */
public record Config(
    int captureIntervalMs,
    int gestureMs,
    int unknownTimeoutMs,
    int stuckTimeoutMs,
    int maxRecoveryAttempts,
    int adTimeoutMs,
    int maxSessionMinutes,
    boolean watchRewardAds,
    int maxAirJumps) {
  public Config {
    range("Capture interval", captureIntervalMs, 300, 2000);
    range("Gesture duration", gestureMs, 60, 600);
    if (gestureMs >= captureIntervalMs)
      throw new IllegalArgumentException("Gesture must finish before the next capture");
    range("Unknown-state timeout", unknownTimeoutMs, 3000, 60000);
    range("Stuck timeout", stuckTimeoutMs, 2000, 60000);
    range("Recovery attempts", maxRecoveryAttempts, 1, 8);
    range("Ad timeout", adTimeoutMs, 10000, 180000);
    range("Session minutes", maxSessionMinutes, 1, 720);
    range("Extra jumps", maxAirJumps, 0, 12);
  }

  private static void range(String label, int value, int low, int high) {
    if (value < low || value > high)
      throw new IllegalArgumentException(label + " must be " + low + "–" + high);
  }

  public static Config defaults() {
    return new Config(400, 160, 15000, 8000, 3, 120000, 60, false, 0);
  }
}
