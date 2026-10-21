package dev.corefilter.pilot.core;

import static org.junit.Assert.*;

import java.util.Random;
import org.junit.Test;

public final class MotionEstimatorTest {
  private static byte[] texture() {
    byte[] a = new byte[640 * 295];
    Random random = new Random(180);
    for (int i = 0; i < a.length; i++) a[i] = (byte) (32 + random.nextInt(160));
    return a;
  }

  @Test
  public void brightnessFlickerIsNotProgress() {
    byte[] a = texture(), b = a.clone();
    for (int i = 0; i < b.length; i++) b[i] = (byte) ((b[i] & 255) + 12);
    MotionEstimator.Shift shift = MotionEstimator.estimate(a, b, 640, 295);
    assertEquals(0, shift.x(), 0);
    assertEquals(0, shift.y(), 0);
  }

  @Test
  public void actualCameraTranslationIsRecovered() {
    byte[] a = texture(), b = new byte[a.length];
    for (int y = 0; y < 295; y++)
      for (int x = 0; x < 640; x++) {
        int sx = x - 8, sy = y + 5;
        if (sx >= 0 && sx < 640 && sy < 295) b[y * 640 + x] = a[sy * 640 + sx];
      }
    MotionEstimator.Shift shift = MotionEstimator.estimate(a, b, 640, 295);
    assertEquals(8, shift.x(), 0);
    assertEquals(-5, shift.y(), 0);
  }

  @Test
  public void FeaturelessFramesProvideNoInventedMotion() {
    MotionEstimator.Shift shift =
        MotionEstimator.estimate(new byte[640 * 295], new byte[640 * 295], 640, 295);
    assertEquals(0, shift.magnitude(640), 0);
  }

  @Test
  public void followsVisibleTextureInsteadOfFixedBlackScreenLocations() {
    byte[] source = texture(), a = new byte[source.length], b = new byte[source.length];
    for (int y = 95; y < 155; y++)
      for (int x = 220; x < 460; x++) a[y * 640 + x] = source[y * 640 + x];
    for (int y = 0; y < 295; y++)
      for (int x = 0; x < 640; x++) {
        int sx = x - 8, sy = y + 5;
        if (sx >= 0 && sy < 295) b[y * 640 + x] = a[sy * 640 + sx];
      }
    MotionEstimator.Shift shift = MotionEstimator.estimate(a, b, 640, 295);
    assertEquals(8, shift.x(), 0);
    assertEquals(-5, shift.y(), 0);
  }
}
