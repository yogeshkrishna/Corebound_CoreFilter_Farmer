package dev.corefilter.pilot.core;

/** Small, allocation-free sampled NCC search; contrast normalization tolerates game lighting. */
final class TemplateMatcher {
  record Match(int x, int y, double score, double separation) {}

  private final int[] dx, dy;
  private final double[] values;
  private final double variance;
  private final int radius;

  TemplateMatcher(PixelTemplate patch) {
    this(patch, patch.side() > 15 ? 3 : 2);
  }

  TemplateMatcher(PixelTemplate patch, int step) {
    radius = patch.side() / 2;
    int count = ((patch.side() - 1) / step + 1);
    dx = new int[count * count];
    dy = new int[dx.length];
    values = new double[dx.length];
    double sum = 0;
    int k = 0;
    for (int y = 0; y < patch.side(); y += step)
      for (int x = 0; x < patch.side(); x += step) {
        dx[k] = x - radius;
        dy[k] = y - radius;
        values[k] = PixelTemplate.luma(patch.pixel(y * patch.side() + x));
        sum += values[k++];
      }
    double mean = sum / k, ss = 0;
    for (int i = 0; i < k; i++) {
      values[i] -= mean;
      ss += values[i] * values[i];
    }
    variance = ss;
  }

  Match find(
      byte[] gray,
      int width,
      int height,
      int left,
      int top,
      int right,
      int bottom,
      boolean mirror) {
    int l = Math.max(radius, left), t = Math.max(radius, top);
    int r = Math.min(width - radius - 1, right), b = Math.min(height - radius - 1, bottom);
    double best = -1, second = -1;
    int bx = l, by = t;
    // Dense spatial search is deliberate: a one-pixel offset can decorrelate a small
    // textured core. Sampling template pixels is safe; skipping candidate centers is not.
    for (int y = t; y <= b; y++)
      for (int x = l; x <= r; x++) {
        double score = score(gray, width, x, y, false);
        if (mirror) score = Math.max(score, score(gray, width, x, y, true));
        if (score > best) {
          if (Math.abs(x - bx) + Math.abs(y - by) > radius) second = best;
          best = score;
          bx = x;
          by = y;
        } else if (Math.abs(x - bx) + Math.abs(y - by) > radius) second = Math.max(second, score);
      }
    return new Match(bx, by, best, best - second);
  }

  double score(byte[] image, int width, int x, int y, boolean mirror) {
    double sum = 0, sq = 0, cross = 0;
    for (int k = 0; k < values.length; k++) {
      int v = image[(y + dy[k]) * width + x + (mirror ? -dx[k] : dx[k])] & 255;
      sum += v;
      sq += (double) v * v;
      cross += values[k] * v;
    }
    double ss = sq - sum * sum / values.length;
    if (ss < 16 * values.length || variance < 1) return -1;
    return cross / Math.sqrt(ss * variance);
  }
}
