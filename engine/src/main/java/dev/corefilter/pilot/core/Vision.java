package dev.corefilter.pilot.core;

/** Calibrated local observation; this does not infer a whole map or hardcode player colors. */
public final class Vision {
  public static final int ANALYSIS_WIDTH = 640;

  public record Result(
      boolean valid,
      Point player,
      double confidence,
      boolean groundBelow,
      boolean wallLeft,
      boolean wallRight,
      boolean ceilingAbove,
      double terrainConfidence,
      double sceneChange,
      double verticalDelta) {
    public static Result unavailable() {
      return new Result(false, null, 0, false, false, false, false, 0, 0, 0);
    }

    public Result {
      if (valid && player == null)
        throw new IllegalArgumentException("Valid detection requires a player");
    }
  }

  private final TemplateMatcher player, terrain;
  private byte[] gray = new byte[0], previous = new byte[0];
  private Point lastPlayer;
  private int misses;
  private long terrainSeen = -1;

  public Vision(Calibration calibration) {
    player = new TemplateMatcher(calibration.player());
    terrain = new TemplateMatcher(calibration.terrain(), 1);
  }

  public static PixelTemplate extract(Frame frame, Point at, int side) {
    int x = (int) Math.round(at.x() * (frame.width() - 1)),
        y = (int) Math.round(at.y() * (frame.height() - 1));
    int radius = side / 2;
    if (side % 2 == 0
        || x < radius
        || y < radius
        || x + radius >= frame.width()
        || y + radius >= frame.height()) {
      throw new IllegalArgumentException("Select a patch farther from the screen edge");
    }
    int[] pixels = new int[side * side];
    for (int row = 0; row < side; row++)
      System.arraycopy(
          frame.argb(), (y - radius + row) * frame.width() + x - radius, pixels, row * side, side);
    return new PixelTemplate(side, pixels);
  }

  public static double pixelsPerUnit(int width, int height) {
    if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid display size");
    return Math.max(height / 11.0, width * 9.0 / 176.0);
  }

  public Result analyze(Frame frame) {
    int w = frame.width(), h = frame.height(), size = w * h;
    if (w != ANALYSIS_WIDTH || w <= h || h < 160)
      throw new IllegalArgumentException("Analysis requires a 640-pixel-wide landscape frame");
    if (gray.length != size) {
      gray = new byte[size];
      previous = new byte[size];
      lastPlayer = null;
      misses = 0;
    }
    for (int i = 0; i < size; i++) gray[i] = (byte) PixelTemplate.luma(frame.argb()[i]);
    MotionEstimator.Shift shift = MotionEstimator.estimate(previous, gray, w, h, lastPlayer);
    double sceneChange = shift.magnitude(w);
    int l = w / 16, r = w * 15 / 16, t = h / 8, b = h * 7 / 8;
    boolean local = lastPlayer != null && misses < 2;
    if (local) {
      int x = (int) (lastPlayer.x() * w), y = (int) (lastPlayer.y() * h);
      l = Math.max(l, x - w / 6);
      r = Math.min(r, x + w / 6);
      t = Math.max(t, y - h / 3);
      b = Math.min(b, y + h / 3);
    }
    TemplateMatcher.Match found = player.find(gray, w, h, l, t, r, b, true);
    boolean valid = found.score() >= .77 && (local || found.separation() >= .025);
    Result result;
    if (!valid) {
      misses++;
      result = Result.unavailable();
    } else {
      Point at = new Point((double) found.x() / w, (double) found.y() / h);
      double vertical = lastPlayer == null ? 0 : at.y() - lastPlayer.y() - shift.y() / h;
      lastPlayer = at;
      misses = 0;
      double unit = pixelsPerUnit(w, h);
      double floor =
          Math.max(
              probe(w, h, found.x() - unit * .3, found.y() + unit * 1.1, unit * .45),
              probe(w, h, found.x() + unit * .3, found.y() + unit * 1.1, unit * .45));
      double left = probe(w, h, found.x() - unit * 1.05, found.y(), unit * .30);
      double right = probe(w, h, found.x() + unit * 1.05, found.y(), unit * .30);
      double ceiling = probe(w, h, found.x(), found.y() - unit * 1.15, unit * .30);
      double confidence = Math.max(Math.max(floor, ceiling), Math.max(left, right));
      boolean floorEdge = floorEdge(w, h, found.x(), found.y(), unit);
      if (confidence >= .82 || (floor >= .75 && floorEdge)) terrainSeen = frame.timeMs();
      // During a jump the nearby floor may leave the field of view briefly.
      if (terrainSeen >= 0 && frame.timeMs() - terrainSeen < 1600)
        confidence = Math.max(confidence, .75);
      result =
          new Result(
              true,
              at,
              Math.min(1, found.score()),
              floor >= .88 || (floor >= .75 && floorEdge),
              left >= .94,
              right >= .94,
              ceiling >= .94,
              Math.max(0, confidence),
              sceneChange,
              vertical);
    }
    byte[] swap = previous;
    previous = gray;
    gray = swap;
    return result;
  }

  private double probe(int w, int h, double x, double y, double radius) {
    return terrain
        .find(
            gray,
            w,
            h,
            (int) (x - radius),
            (int) (y - radius),
            (int) (x + radius),
            (int) (y + radius),
            false)
        .score();
  }

  private boolean floorEdge(int w, int h, int x, int y, double unit) {
    int half = (int) (unit * .4),
        first = Math.max(2, (int) (y + unit * .4)),
        last = Math.min(h - 3, (int) (y + unit * 1.05));
    for (int row = first; row <= last; row++) {
      int consistent = 0, total = 0;
      for (int col = Math.max(0, x - half); col <= Math.min(w - 1, x + half); col += 2) {
        if (Math.abs((gray[(row - 2) * w + col] & 255) - (gray[(row + 2) * w + col] & 255)) >= 10)
          consistent++;
        total++;
      }
      if (total > 0 && consistent >= total * .75) return true;
    }
    return false;
  }

  public void reset() {
    lastPlayer = null;
    misses = 0;
    terrainSeen = -1;
    previous = new byte[0];
    gray = new byte[0];
  }
}
