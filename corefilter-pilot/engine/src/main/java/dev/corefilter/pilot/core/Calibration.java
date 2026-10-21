package dev.corefilter.pilot.core;

import java.util.Objects;

/** Learned from the user's actual display; no imported screen coordinates. */
public record Calibration(
    Point left,
    Point right,
    Point jump,
    PixelTemplate player,
    PixelTemplate terrain,
    int displayWidth,
    int displayHeight) {
  public Calibration {
    Objects.requireNonNull(left);
    Objects.requireNonNull(right);
    Objects.requireNonNull(jump);
    Objects.requireNonNull(player);
    Objects.requireNonNull(terrain);
    if (displayWidth <= displayHeight || displayHeight < 200)
      throw new IllegalArgumentException("Calibrate in landscape");
    if (left.distance(right) < .03 || jump.distance(left) < .08 || jump.distance(right) < .08) {
      throw new IllegalArgumentException("Movement and jump controls must be distinct");
    }
    if (left.x() >= right.x())
      throw new IllegalArgumentException("Left control must be left of right control");
    if (Math.abs(left.y() - right.y()) > .12)
      throw new IllegalArgumentException("Choose the two buttons in the same movement panel");
    if (jump.x() >= left.x() - .05
        && jump.x() <= right.x() + .05
        && jump.y() >= Math.min(left.y(), right.y()) - .12
        && jump.y() <= Math.max(left.y(), right.y()) + .12) {
      throw new IllegalArgumentException("Choose a jump point outside the movement panel");
    }
  }

  public boolean matchesDisplay(int width, int height) {
    return Math.abs((double) width / height - (double) displayWidth / displayHeight) < .015;
  }
}
