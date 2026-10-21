package dev.corefilter.pilot.core;

import static org.junit.Assert.*;

import org.junit.Test;

public final class VisionTest {
  @Test
  public void cameraScaleMatchesRecoveredAspectCorrection() {
    assertEquals(2637.0 / 22, Vision.pixelsPerUnit(2344, 1080), 1e-10);
    assertEquals(1080.0 / 11, Vision.pixelsPerUnit(1920, 1080), 1e-10);
    assertEquals(1080.0 / 11, Vision.pixelsPerUnit(1600, 1080), 1e-10);
  }

  @Test
  public void templateExtractionPreservesExactPixelCenterAndOrientation() {
    int[] pixels = Fixtures.image(64, 40, 12);
    PixelTemplate patch = Fixtures.patch(9, 500);
    Fixtures.stamp(pixels, 64, 32, 20, patch, false);
    Frame frame = new Frame(64, 40, pixels, 10);
    PixelTemplate extracted = Vision.extract(frame, new Point(32.0 / 63, 20.0 / 39), 9);
    assertEquals(patch, extracted);
  }

  @Test
  public void patchAtScreenBoundaryIsRejected() {
    Frame frame = new Frame(64, 40, Fixtures.image(64, 40, 12), 10);
    assertThrows(IllegalArgumentException.class, () -> Vision.extract(frame, new Point(0, .5), 9));
    assertThrows(IllegalArgumentException.class, () -> Vision.extract(frame, new Point(1, .5), 9));
    assertThrows(IllegalArgumentException.class, () -> Vision.extract(frame, new Point(.5, .5), 8));
  }

  @Test
  public void matchingFindsTranslatedAndMirroredPatch() {
    PixelTemplate patch = Fixtures.patch(9, 4);
    TemplateMatcher matcher = new TemplateMatcher(patch);
    int[] pixels = Fixtures.image(160, 100, 12);
    Fixtures.stamp(pixels, 160, 74, 57, patch, true);
    TemplateMatcher.Match match =
        matcher.find(Fixtures.gray(pixels), 160, 100, 0, 0, 159, 99, true);
    assertEquals(74, match.x());
    assertEquals(57, match.y());
    assertEquals(1.0, match.score(), 1e-9);
  }

  @Test
  public void normalizedCorrelationToleratesBrightnessAndContrast() {
    PixelTemplate patch = Fixtures.patch(9, 53);
    int[] brighter = patch.copyPixels();
    for (int i = 0; i < brighter.length; i++) {
      int level = PixelTemplate.luma(brighter[i]) / 2 + 50;
      brighter[i] = 0xff000000 | level * 0x010101;
    }
    int[] pixels = Fixtures.image(160, 100, 12);
    Fixtures.stamp(pixels, 160, 74, 57, new PixelTemplate(9, brighter), false);
    TemplateMatcher.Match match =
        new TemplateMatcher(patch).find(Fixtures.gray(pixels), 160, 100, 0, 0, 159, 99, true);
    assertEquals(74, match.x());
    assertEquals(57, match.y());
    assertTrue(match.score() > .999);
  }

  @Test
  public void flatSceneryDoesNotBecomeAConfidentMatch() {
    TemplateMatcher.Match match =
        new TemplateMatcher(Fixtures.patch(9, 4))
            .find(Fixtures.gray(Fixtures.image(160, 100, 80)), 160, 100, 0, 0, 159, 99, true);
    assertEquals(-1, match.score(), 0);
  }

  @Test
  public void duplicateIndependentMatchesHaveNoSeparation() {
    PixelTemplate patch = Fixtures.patch(9, 4);
    int[] pixels = Fixtures.image(160, 100, 12);
    Fixtures.stamp(pixels, 160, 49, 40, patch, false);
    Fixtures.stamp(pixels, 160, 109, 40, patch, false);
    TemplateMatcher.Match match =
        new TemplateMatcher(patch).find(Fixtures.gray(pixels), 160, 100, 0, 0, 159, 99, true);
    assertEquals(1, match.score(), 1e-9);
    assertEquals(0, match.separation(), 1e-9);
  }

  @Test
  public void clippedSearchWindowDoesNotReadOutsideBuffer() {
    TemplateMatcher matcher = new TemplateMatcher(Fixtures.patch(9, 4));
    byte[] pixels = Fixtures.gray(Fixtures.image(16, 16, 80));
    assertEquals(-1, matcher.find(pixels, 16, 16, -100, -100, 100, 100, true).score(), 0);
    assertEquals(-1, matcher.find(pixels, 16, 16, 20, 20, 30, 30, true).score(), 0);
  }

  @Test
  public void visionRejectsUnsupportedAnalysisScale() {
    Vision vision = new Vision(Fixtures.calibration());
    assertThrows(
        IllegalArgumentException.class,
        () -> vision.analyze(new Frame(320, 160, Fixtures.image(320, 160, 12), 0)));
    assertThrows(
        IllegalArgumentException.class,
        () -> vision.analyze(new Frame(640, 700, Fixtures.image(640, 700, 12), 0)));
  }

  @Test
  public void calibratedPlayerAndNearbyTerrainProduceAValidObservation() {
    Calibration calibration = Fixtures.calibration();
    int[] pixels = Fixtures.image(640, 300, 12);
    Fixtures.stamp(pixels, 640, 320, 150, calibration.player(), false);
    int unit = (int) Math.round(Vision.pixelsPerUnit(640, 300));
    Fixtures.stamp(
        pixels, 640, 310, 150 + (int) Math.round(unit * .85), calibration.terrain(), false);
    Fixtures.stamp(
        pixels, 640, 330, 150 + (int) Math.round(unit * .85), calibration.terrain(), false);
    Vision.Result result = new Vision(calibration).analyze(new Frame(640, 300, pixels, 100));
    assertTrue(result.valid());
    assertEquals(.5, result.player().x(), .005);
    assertEquals(.5, result.player().y(), .005);
    assertTrue(result.groundBelow());
    assertTrue(result.terrainConfidence() >= .75);
  }

  @Test
  public void losingPlayerReturnsUnavailableInsteadOfExtrapolatedCoordinates() {
    Calibration calibration = Fixtures.calibration();
    Vision vision = new Vision(calibration);
    int[] pixels = Fixtures.image(640, 300, 12);
    Fixtures.stamp(pixels, 640, 320, 150, calibration.player(), false);
    assertTrue(vision.analyze(new Frame(640, 300, pixels, 100)).valid());
    Vision.Result missing = vision.analyze(new Frame(640, 300, Fixtures.image(640, 300, 12), 500));
    assertFalse(missing.valid());
    assertNull(missing.player());
    vision.reset();
    assertFalse(vision.analyze(new Frame(640, 300, Fixtures.image(640, 300, 12), 900)).valid());
  }
}
