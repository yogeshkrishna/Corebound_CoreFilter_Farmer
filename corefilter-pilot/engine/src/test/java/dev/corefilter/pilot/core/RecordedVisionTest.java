package dev.corefilter.pilot.core;

import static org.junit.Assert.*;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import org.junit.Test;

/** Real recorded pixels test perception only; they are not a live farming test. */
public final class RecordedVisionTest {
  static Frame frame(int index, long time) throws IOException {
    try (InputStream input =
        RecordedVisionTest.class.getResourceAsStream("/recordings/run01-frame" + index + ".png")) {
      if (input == null) throw new IOException("Missing recorded fixture");
      BufferedImage im = ImageIO.read(input);
      return new Frame(
          im.getWidth(),
          im.getHeight(),
          im.getRGB(0, 0, im.getWidth(), im.getHeight(), null, 0, im.getWidth()),
          time);
    }
  }

  static Calibration calibration(Frame first) {
    return new Calibration(
        new Point(.16, .80),
        new Point(.28, .80),
        new Point(.83, .80),
        Vision.extract(first, new Point(326.0 / 639, 188.0 / 294), 21),
        Vision.extract(first, new Point(420.0 / 639, 224.0 / 294), 25),
        2344,
        1080);
  }

  @Test
  public void tracksCalibratedCrawlerAcrossActualRecordedMovement() throws IOException {
    Frame first = frame(516, 0);
    Vision vision = new Vision(calibration(first));
    int[] indices = {516, 528, 540, 552};
    double[] xs = {326, 300.5, 288.7, 293};
    for (int i = 0; i < indices.length; i++) {
      Vision.Result result = vision.analyze(frame(indices[i], i * 400L));
      System.out.println(indices[i] + " " + result);
      assertTrue("Player must be found in recorded frame " + indices[i], result.valid());
      assertEquals(xs[i], result.player().x() * 640, 3);
      assertEquals(187, result.player().y() * 295, 4);
      assertFalse("The visible corridor has no immediate left wall", result.wallLeft());
      assertFalse("The visible corridor has no immediate right wall", result.wallRight());
      assertFalse("Background patterns are not the main ceiling", result.ceilingAbove());
    }
  }

  @Test
  public void calibratedMainFloorIsObservedWithoutTreatingSkyAsTerrain() throws IOException {
    Frame first = frame(516, 0);
    Vision.Result result = new Vision(calibration(first)).analyze(first);
    assertTrue(result.valid());
    assertTrue(
        "Actual main-floor patch must support navigation", result.terrainConfidence() >= .75);
    assertTrue(result.groundBelow());
    assertFalse("Open space above the Crawler is not a ceiling", result.ceilingAbove());
  }

  @Test
  public void changingThePlayerAppearanceFailsClosed() throws IOException {
    Frame first = frame(516, 0);
    Vision vision = new Vision(calibration(first));
    int[] black = new int[first.argb().length];
    java.util.Arrays.fill(black, 0xff101010);
    assertFalse(vision.analyze(new Frame(640, 295, black, 1000)).valid());
  }
}
