package dev.corefilter.pilot.core;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class ValidationTest {
  @Test
  public void defaultConfigurationHasBoundedSeparatedInputPulses() {
    Config config = Config.defaults();
    assertTrue(config.gestureMs() < config.captureIntervalMs());
    assertFalse(config.watchRewardAds());
    assertEquals(0, config.maxAirJumps());
    assertTrue(config.maxRecoveryAttempts() > 0);
  }

  @Test
  public void invalidConfigurationLimitsAreRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Config(200, 160, 15000, 8000, 3, 120000, 60, false, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Config(400, 400, 15000, 8000, 3, 120000, 60, false, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Config(400, 160, 2000, 8000, 3, 120000, 60, false, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Config(400, 160, 15000, 1000, 3, 120000, 60, false, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Config(400, 160, 15000, 8000, 0, 120000, 60, false, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Config(400, 160, 15000, 8000, 3, 5000, 60, false, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Config(400, 160, 15000, 8000, 3, 120000, 0, false, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Config(400, 160, 15000, 8000, 3, 120000, 60, false, 13));
  }

  @Test
  public void normalizedCoordinatesAndBoxesRejectInvalidValues() {
    for (double invalid : new double[] {-0.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY}) {
      assertThrows(IllegalArgumentException.class, () -> new Point(invalid, .5));
      assertThrows(IllegalArgumentException.class, () -> new Point(.5, invalid));
    }
    assertThrows(IllegalArgumentException.class, () -> new Box(.5, .2, .4, .3));
    assertThrows(IllegalArgumentException.class, () -> new Box(.2, .3, .4, .3));
    assertThrows(IllegalArgumentException.class, () -> new Box(0, 0, 1.1, 1));
    assertEquals(new Point(.4, .5), new Box(.2, .3, .6, .7).center());
  }

  @Test
  public void calibrationRejectsOverlappingOrReversedControls() {
    PixelTemplate patch = Fixtures.patch(9, 42);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Calibration(
                new Point(.15, .8),
                new Point(.16, .8),
                new Point(.8, .8),
                patch,
                patch,
                2344,
                1080));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Calibration(
                new Point(.3, .8), new Point(.1, .8), new Point(.8, .8), patch, patch, 2344, 1080));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Calibration(
                new Point(.1, .8),
                new Point(.3, .8),
                new Point(.31, .8),
                patch,
                patch,
                2344,
                1080));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Calibration(
                new Point(.1, .8), new Point(.3, .8), new Point(.8, .8), patch, patch, 1080, 2344));
  }

  @Test
  public void templatesOwnTheirPixelData() {
    PixelTemplate original = Fixtures.patch(9, 42);
    int[] pixels = original.copyPixels();
    PixelTemplate stored = new PixelTemplate(9, pixels);
    pixels[0] ^= 0x00ffffff;
    assertEquals(original, stored);
    int[] copy = stored.copyPixels();
    copy[1] ^= 0x00ffffff;
    assertEquals(original, stored);
    assertEquals(original.hashCode(), stored.hashCode());
  }

  @Test
  public void lowInformationAndMalformedTemplatesAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> new PixelTemplate(9, new int[81]));
    assertThrows(IllegalArgumentException.class, () -> new PixelTemplate(8, new int[64]));
    assertThrows(IllegalArgumentException.class, () -> new PixelTemplate(35, new int[1225]));
    assertThrows(IllegalArgumentException.class, () -> new PixelTemplate(9, new int[80]));
  }

  @Test
  public void framesAndObservationTimestampsAreValidated() {
    assertThrows(IllegalArgumentException.class, () -> new Frame(16, 16, new int[255], 0));
    assertThrows(IllegalArgumentException.class, () -> new Frame(16, 16, new int[256], -1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Observation(
                100,
                Observation.GAME_PACKAGE,
                2344,
                1080,
                List.of(),
                101,
                Vision.Result.unavailable()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Vision.Result(true, null, .9, false, false, false, false, .9, 0, 0));
  }

  @Test
  public void observationsDefensivelyCopyTextList() {
    List<TextToken> text = new ArrayList<>();
    text.add(Fixtures.token("Retry"));
    Observation observation =
        new Observation(
            100, Observation.GAME_PACKAGE, 2344, 1080, text, 100, Vision.Result.unavailable());
    text.clear();
    assertEquals(1, observation.text().size());
    assertThrows(UnsupportedOperationException.class, () -> observation.text().clear());
  }

  @Test
  public void invalidActionsCannotEscapePlatformBounds() {
    assertThrows(IllegalArgumentException.class, () -> Action.move(2, false, 160, "invalid"));
    assertThrows(IllegalArgumentException.class, () -> Action.move(0, false, 160, "empty pulse"));
    assertThrows(IllegalArgumentException.class, () -> Action.move(1, true, 601, "too long"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Action(Action.Kind.TAP, 0, false, 80, null, "missing target"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Action(Action.Kind.WAIT, 1, false, 80, null, "unexpected move"));
  }

  @Test
  public void ocrNormalizationHandlesPunctuationAndCompatibilityCharacters() {
    assertEquals("core filters 5", TextToken.normalize("  CORE FILTERS ★５  "));
    assertEquals("retry", TextToken.normalize("Retry!"));
    assertEquals("watch ad", TextToken.normalize("Watch\nAD"));
  }
}
