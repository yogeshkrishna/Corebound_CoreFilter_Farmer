package dev.corefilter.pilot.core;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;

public record TextToken(String text, Box bounds) {
  public TextToken {
    Objects.requireNonNull(text);
    Objects.requireNonNull(bounds);
  }

  public String normalized() {
    return normalize(text);
  }

  public static String normalize(String text) {
    return Normalizer.normalize(text, Normalizer.Form.NFKD)
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9]+", " ")
        .trim()
        .replaceAll(" +", " ");
  }
}
