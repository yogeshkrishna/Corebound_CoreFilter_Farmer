package dev.corefilter.pilot.core;

/** Normalized display coordinates, independent of capture resolution. */
public record Point(double x, double y) {
  public Point {
    if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || x > 1 || y < 0 || y > 1) {
      throw new IllegalArgumentException("Point must lie within the display");
    }
  }

  public double distance(Point other) {
    return Math.hypot(x - other.x, y - other.y);
  }
}
