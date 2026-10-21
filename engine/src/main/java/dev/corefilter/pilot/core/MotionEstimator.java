package dev.corefilter.pilot.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

/** Local normalized correlation rejects brightness flicker as evidence of camera movement. */
final class MotionEstimator {
  record Shift(double x, double y) {
    double magnitude(int width) {
      return Math.hypot(x, y) / width;
    }
  }

  private MotionEstimator() {}

  private record Anchor(int x, int y, double variance) {}

  static Shift estimate(byte[] previous, byte[] current, int width, int height) {
    return estimate(previous, current, width, height, null);
  }

  static Shift estimate(byte[] previous, byte[] current, int width, int height, Point player) {
    ArrayList<Anchor> candidates = new ArrayList<>(32);
    for (int row = 0; row < 4; row++)
      for (int col = 0; col < 8; col++) {
        int x = (int) (width * (.1 + .8 * col / 7)), y = (int) (height * (.22 + .56 * row / 3));
        if (x < width * .38 && y > height * .68) continue;
        if (player != null
            && Math.hypot(x - player.x() * width, y - player.y() * height) < width * .08) continue;
        double sum = 0, sq = 0;
        for (int dy = -6; dy <= 6; dy++)
          for (int dx = -6; dx <= 6; dx++) {
            int v = previous[(y + dy) * width + x + dx] & 255;
            sum += v;
            sq += (double) v * v;
          }
        double variance = sq / 169 - Math.pow(sum / 169, 2);
        if (variance >= 25) candidates.add(new Anchor(x, y, variance));
      }
    candidates.sort(Comparator.comparingDouble(Anchor::variance).reversed());
    int[] ax = new int[4], ay = new int[4];
    int selected = 0;
    for (Anchor candidate : candidates) {
      boolean near = false;
      for (int i = 0; i < selected; i++)
        if (Math.hypot(candidate.x - ax[i], candidate.y - ay[i]) < width / 8.0) {
          near = true;
          break;
        }
      if (near) continue;
      ax[selected] = candidate.x;
      ay[selected] = candidate.y;
      if (++selected == 4) break;
    }
    double[] horizontal = new double[4], vertical = new double[4];
    int valid = 0;
    for (int i = 0; i < selected; i++) {
      int[] patch = new int[169];
      double sum = 0, sq = 0;
      int k = 0;
      for (int y = -6; y <= 6; y++)
        for (int x = -6; x <= 6; x++) {
          int v = previous[(ay[i] + y) * width + ax[i] + x] & 255;
          patch[k++] = 0xff000000 | (v << 16) | (v << 8) | v;
          sum += v;
          sq += (double) v * v;
        }
      if (sq / 169 - Math.pow(sum / 169, 2) < 25) continue;
      TemplateMatcher matcher = new TemplateMatcher(new PixelTemplate(13, patch));
      if (matcher.score(current, width, ax[i], ay[i], false) > .995) {
        valid++;
        continue;
      }
      TemplateMatcher.Match found =
          matcher.find(
              current,
              width,
              height,
              Math.max(width / 16, ax[i] - 70),
              Math.max(height / 6, ay[i] - 70),
              Math.min(width * 15 / 16, ax[i] + 70),
              Math.min(height * 4 / 5, ay[i] + 70),
              false);
      if (found.score() >= .96) {
        horizontal[valid] = found.x() - ax[i];
        vertical[valid] = found.y() - ay[i];
        valid++;
      }
    }
    if (valid < 2) return new Shift(0, 0);
    Arrays.sort(horizontal, 0, valid);
    Arrays.sort(vertical, 0, valid);
    return new Shift(horizontal[valid / 2], vertical[valid / 2]);
  }
}
