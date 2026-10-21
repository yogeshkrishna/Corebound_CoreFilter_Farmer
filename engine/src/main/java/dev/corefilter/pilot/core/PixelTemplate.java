package dev.corefilter.pilot.core;

import java.util.Arrays;

/** Immutable, small color patch at the canonical analysis scale. */
public final class PixelTemplate {
  private final int side;
  private final int[] pixels;

  public PixelTemplate(int side, int[] pixels) {
    if (side < 5 || side > 33 || side % 2 == 0 || pixels.length != side * side) {
      throw new IllegalArgumentException("Template must be an odd 5–33 pixel square");
    }
    this.side = side;
    this.pixels = pixels.clone();
    double sum = 0, sq = 0;
    for (int c : pixels) {
      int v = luma(c);
      sum += v;
      sq += (double) v * v;
    }
    if (sq / pixels.length - Math.pow(sum / pixels.length, 2) < 16) {
      throw new IllegalArgumentException("Choose a textured patch, away from flat sky or UI");
    }
  }

  public int side() {
    return side;
  }

  public int pixel(int index) {
    return pixels[index];
  }

  public int[] copyPixels() {
    return pixels.clone();
  }

  public static int luma(int c) {
    return (77 * ((c >> 16) & 255) + 150 * ((c >> 8) & 255) + 29 * (c & 255)) >> 8;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof PixelTemplate p && side == p.side && Arrays.equals(pixels, p.pixels);
  }

  @Override
  public int hashCode() {
    return 31 * side + Arrays.hashCode(pixels);
  }
}
