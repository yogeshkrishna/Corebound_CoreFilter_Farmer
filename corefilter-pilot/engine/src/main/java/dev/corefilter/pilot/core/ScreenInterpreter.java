package dev.corefilter.pilot.core;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** English UI, exact action labels, and explicit context. Unknown screens never become taps. */
public final class ScreenInterpreter {
  public enum Screen {
    GAME,
    RESULTS,
    AD_CONFIRM,
    AD,
    PAUSED,
    WORLD,
    LOADING,
    UNKNOWN,
    UNSAFE
  }

  public record Reading(Screen screen, TextToken action, boolean filterLabel, String identity) {}

  private static final Set<String> UNSAFE =
      Set.of(
          "purchase",
          "buy",
          "discard",
          "delete",
          "overwrite",
          "privacy",
          "consent",
          "terms of service",
          "not enough shards");

  public Reading read(Observation observation, boolean adExpected, boolean allowAds) {
    List<TextToken> tokens = observation.textFresh() ? observation.text() : List.of();
    String joined =
        String.join(" ", tokens.stream().map(TextToken::normalized).collect(Collectors.toList()));
    boolean filter = joined.contains("core filter");
    if (UNSAFE.stream().anyMatch(word -> (" " + joined + " ").contains(" " + word + " ")))
      return reading(Screen.UNSAFE, null, filter, "unsafe");
    if (joined.contains("watch ad") || joined.contains("watch a short video")) {
      TextToken action =
          allowAds
              ? exact(tokens, "watch", "yes", "watch ad")
              : exact(tokens, "no", "back", "cancel");
      // The title itself is not the confirmation button.
      if (action != null && action.normalized().equals("watch ad") && action.bounds().top() < .45)
        action = null;
      return reading(Screen.AD_CONFIRM, action, filter, "ad-confirm");
    }
    boolean end = exact(tokens, "destroyed", "complete", "world complete", "run ended") != null;
    if (end) {
      TextToken action = exact(tokens, "retry", "continue");
      return reading(Screen.RESULTS, action, filter, "results");
    }
    if (adExpected) {
      TextToken close = exact(tokens, "close", "close ad");
      boolean finished =
          joined.contains("reward granted")
              || joined.contains("reward earned")
              || joined.contains("video completed");
      return reading(
          Screen.AD, finished ? close : null, filter, finished ? "ad-finished" : "ad-wait");
    }
    if (joined.contains("paused") || exact(tokens, "resume") != null)
      return reading(Screen.PAUSED, null, filter, "game-paused");
    if (joined.contains("generating background")
        || joined.contains("generating world")
        || joined.contains("loading")) return reading(Screen.LOADING, null, filter, "loading");
    if (joined.contains("lost scrapyard") && !observation.vision().valid()) {
      // The resource's description proves selection; the name alone can be another world's list
      // entry.
      boolean selected = joined.contains("dormant cave") && joined.contains("top of the world");
      TextToken action = selected ? exact(tokens, "play") : exact(tokens, "lost scrapyard");
      if (exact(tokens, "unlock") != null)
        return reading(Screen.UNSAFE, null, filter, "world-locked");
      return reading(
          Screen.WORLD, action, filter, selected ? "scrapyard-selected" : "select-scrapyard");
    }
    if (observation.vision().valid() && observation.vision().confidence() >= .77)
      return reading(Screen.GAME, null, filter, "gameplay");
    return reading(Screen.UNKNOWN, null, filter, "unknown");
  }

  private static Reading reading(Screen screen, TextToken action, boolean filter, String identity) {
    return new Reading(
        screen, action, filter, identity + (action == null ? "" : ":" + action.normalized()));
  }

  static TextToken exact(List<TextToken> tokens, String... labels) {
    for (String label : labels)
      for (TextToken token : tokens) if (token.normalized().equals(label)) return token;
    return null;
  }
}
