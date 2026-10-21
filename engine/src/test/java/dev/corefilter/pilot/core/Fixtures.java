package dev.corefilter.pilot.core;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

final class Fixtures {
  private Fixtures() {}

  static PixelTemplate patch(int side, long seed) {
    Random random = new Random(seed);
    int[] pixels = new int[side * side];
    for (int i = 0; i < pixels.length; i++) {
      int level = 30 + random.nextInt(180);
      pixels[i] = 0xff000000 | level * 0x010101;
    }
    return new PixelTemplate(side, pixels);
  }

  static Calibration calibration() {
    return new Calibration(
        new Point(.15, .85),
        new Point(.28, .85),
        new Point(.8, .82),
        patch(15, 41),
        patch(15, 74),
        2344,
        1080);
  }

  static Config config(boolean ads, int extraJumps) {
    return new Config(400, 160, 15000, 8000, 3, 120000, 60, ads, extraJumps);
  }

  static Vision.Result view(
      boolean grounded,
      boolean ceiling,
      boolean left,
      boolean right,
      double vertical,
      double change) {
    return new Vision.Result(
        true, new Point(.5, .55), .96, grounded, left, right, ceiling, .9, change, vertical);
  }

  static Vision.Result game() {
    return view(true, false, false, false, 0, .03);
  }

  static TextToken token(String text) {
    return token(text, .4, .6, .6, .7);
  }

  static TextToken token(String text, double l, double t, double r, double b) {
    return new TextToken(text, new Box(l, t, r, b));
  }

  static Observation observation(long now, long textAt, Vision.Result vision, TextToken... tokens) {
    return new Observation(
        now, Observation.GAME_PACKAGE, 2344, 1080, List.of(tokens), textAt, vision);
  }

  static Observation menu(long now, TextToken... tokens) {
    return observation(now, now, Vision.Result.unavailable(), tokens);
  }

  static Observation gameplay(long now) {
    return observation(now, -1, game());
  }

  static int[] image(int width, int height, int shade) {
    int[] pixels = new int[width * height];
    Arrays.fill(pixels, 0xff000000 | shade * 0x010101);
    return pixels;
  }

  static void stamp(int[] image, int width, int cx, int cy, PixelTemplate patch, boolean mirror) {
    int side = patch.side(), radius = side / 2;
    for (int y = 0; y < side; y++) {
      for (int x = 0; x < side; x++) {
        int sx = mirror ? side - 1 - x : x;
        image[(cy - radius + y) * width + cx - radius + x] = patch.pixel(y * side + sx);
      }
    }
  }

  static byte[] gray(int[] pixels) {
    byte[] values = new byte[pixels.length];
    for (int i = 0; i < pixels.length; i++) values[i] = (byte) PixelTemplate.luma(pixels[i]);
    return values;
  }
}
