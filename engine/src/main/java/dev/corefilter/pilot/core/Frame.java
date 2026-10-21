package dev.corefilter.pilot.core;

/** Borrowed pixel buffer; consumers must not retain or mutate it. */
public record Frame(int width, int height, int[] argb, long timeMs) {
  public Frame {
    if (width < 16 || height < 16 || (long) width * height != argb.length || timeMs < 0) {
      throw new IllegalArgumentException("Invalid frame dimensions or timestamp");
    }
  }

  public int pixel(int x, int y) {
    return argb[y * width + x];
  }
}
