package dev.corefilter.pilot.core;

/** Reproducible JVM replay measurement. Phone throughput must be measured separately. */
public final class VisionBenchmark {
  private VisionBenchmark() {}

  public static void main(String[] args) throws Exception {
    Frame[] frames = {
      RecordedVisionTest.frame(516, 0),
      RecordedVisionTest.frame(528, 400),
      RecordedVisionTest.frame(540, 800),
      RecordedVisionTest.frame(552, 1200)
    };
    Vision vision = new Vision(RecordedVisionTest.calibration(frames[0]));
    for (int i = 0; i < 8; i++) vision.analyze(frames[i % 4]);
    long[] times = new long[40];
    int valid = 0;
    for (int i = 0; i < times.length; i++) {
      Frame f = frames[i % 4];
      long start = System.nanoTime();
      Vision.Result result =
          vision.analyze(new Frame(f.width(), f.height(), f.argb(), 2000 + i * 400L));
      times[i] = System.nanoTime() - start;
      if (result.valid()) valid++;
    }
    java.util.Arrays.sort(times);
    System.out.printf(
        java.util.Locale.ROOT,
        "{\"iterations\":%d,\"valid\":%d,\"medianMs\":%.3f,\"p95Ms\":%.3f,\"maxMs\":%.3f,\"scope\":\"JVM"
            + " recorded replay; not Android throughput\"}%n",
        times.length,
        valid,
        times[20] / 1e6,
        times[38] / 1e6,
        times[39] / 1e6);
  }
}
