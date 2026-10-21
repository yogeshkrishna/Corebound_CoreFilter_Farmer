package dev.corefilter.pilot.core;

public record Box(double left, double top, double right, double bottom) {
  public Box {
    if (!Double.isFinite(left + top + right + bottom)
        || left < 0
        || top < 0
        || right > 1
        || bottom > 1
        || right <= left
        || bottom <= top) {
      throw new IllegalArgumentException("Invalid normalized rectangle");
    }
  }

  public Point center() {
    return new Point((left + right) / 2, (top + bottom) / 2);
  }

  public boolean contains(Point point) {
    return point.x() >= left && point.x() <= right && point.y() >= top && point.y() <= bottom;
  }
}
