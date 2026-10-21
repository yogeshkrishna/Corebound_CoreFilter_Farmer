package dev.corefilter.pilot.core;

import java.util.List;
import java.util.Objects;

public record Observation(
    long timeMs,
    String foregroundPackage,
    int width,
    int height,
    List<TextToken> text,
    long textTimeMs,
    Vision.Result vision) {
  public static final String GAME_PACKAGE = "com.Overcurve.Corebound";

  public Observation {
    Objects.requireNonNull(foregroundPackage);
    Objects.requireNonNull(vision);
    text = List.copyOf(text);
    if (timeMs < 0 || textTimeMs > timeMs || width < 1 || height < 1)
      throw new IllegalArgumentException("Invalid observation");
  }

  public boolean textFresh() {
    return textTimeMs >= 0 && timeMs - textTimeMs <= 1800;
  }
}
