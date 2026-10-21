package dev.corefilter.pilot;

import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import dev.corefilter.pilot.core.Action;
import dev.corefilter.pilot.core.Calibration;
import dev.corefilter.pilot.core.Point;

/** Produces simultaneous, finite, non-continuing strokes; each jump therefore gets a new edge. */
final class TouchPlan {
  private TouchPlan() {}

  static GestureDescription build(Action action, Calibration controls, int width, int height) {
    if (width <= height || height < 200)
      throw new IllegalArgumentException("A landscape display is required");
    GestureDescription.Builder builder = new GestureDescription.Builder();
    if (action.kind() == Action.Kind.TAP)
      stroke(builder, action.target(), action.durationMs(), width, height);
    else if (action.kind() == Action.Kind.MOVE) {
      if (action.direction() != 0)
        stroke(
            builder,
            action.direction() < 0 ? controls.left() : controls.right(),
            action.durationMs(),
            width,
            height);
      if (action.jump()) stroke(builder, controls.jump(), action.durationMs(), width, height);
    } else throw new IllegalArgumentException("Only acknowledged touch actions become gestures");
    return builder.build();
  }

  private static void stroke(
      GestureDescription.Builder builder, Point point, int duration, int width, int height) {
    Path path = new Path();
    path.moveTo((float) (point.x() * (width - 1)), (float) (point.y() * (height - 1)));
    builder.addStroke(new GestureDescription.StrokeDescription(path, 0, duration));
  }
}
