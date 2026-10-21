package dev.corefilter.pilot;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.os.Handler;
import android.os.Looper;
import dev.corefilter.pilot.core.Calibration;
import dev.corefilter.pilot.core.Config;
import dev.corefilter.pilot.core.Engine;
import dev.corefilter.pilot.core.PixelTemplate;
import dev.corefilter.pilot.core.Point;
import dev.corefilter.pilot.core.Vision;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 35)
public final class PilotServiceTest {
  private static Engine engine() {
    int[] data = new int[25];
    for (int i = 0; i < data.length; i++) data[i] = i % 2 == 0 ? 0xff202020 : 0xffc0c0c0;
    PixelTemplate t = new PixelTemplate(5, data);
    Calibration c =
        new Calibration(
            new Point(.15, .8), new Point(.25, .8), new Point(.8, .8), t, t, 2344, 1080);
    Engine e = new Engine(Config.defaults(), c);
    e.start(0);
    return e;
  }

  @Test
  public void systemInterruptStopsTheEngineAndPolling() {
    PilotService service = Robolectric.buildService(PilotService.class).create().get();
    Engine e = engine();
    ReflectionHelpers.setField(service, "engine", e);
    ReflectionHelpers.setField(service, "running", true);
    service.onInterrupt();
    assertEquals(Engine.State.STOPPED, e.state());
    assertFalse(ReflectionHelpers.getField(service, "running"));
    service.onDestroy();
  }

  @Test
  public void stopReleasesIdlePixelBuffers() {
    PilotService service = Robolectric.buildService(PilotService.class).create().get();
    ReflectionHelpers.setField(service, "engine", engine());
    ReflectionHelpers.setField(service, "pixels", new int[640 * 295]);
    service.stopSession("test");
    int[] retained = ReflectionHelpers.getField(service, "pixels");
    assertEquals(0, retained.length);
    service.onDestroy();
  }

  @Test
  public void destroyShutsDownItsOwnedExecutor() {
    PilotService service = Robolectric.buildService(PilotService.class).create().get();
    ExecutorService worker = ReflectionHelpers.getField(service, "worker");
    service.onDestroy();
    assertTrue(worker.isShutdown());
  }

  @Test
  public void destroyDoesNotWaitForAnAbsentScreenshotCallback() {
    PilotService service = Robolectric.buildService(PilotService.class).create().get();
    ExecutorService worker = ReflectionHelpers.getField(service, "worker");
    ReflectionHelpers.setField(service, "captureBusy", true);
    service.onDestroy();
    assertTrue(worker.isShutdown());
  }

  @Test
  public void stopCancelsAQueuedCalibrationCapture() {
    PilotService service = Robolectric.buildService(PilotService.class).create().get();
    ReflectionHelpers.callInstanceMethod(service, "scheduleCalibrationCapture");
    service.stopSession("Stopped during calibration delay");
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
    assertEquals("Stopped during calibration delay", service.statusText());
    assertFalse(ReflectionHelpers.getField(service, "captureBusy"));
    service.onDestroy();
  }

  @Test
  public void repeatedStaleCapturesPauseInsteadOfBypassingDeadlines() throws Exception {
    PilotService service = Robolectric.buildService(PilotService.class).create().get();
    Engine e = engine();
    ReflectionHelpers.setField(service, "engine", e);
    ReflectionHelpers.setField(service, "running", true);
    org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofSeconds(3));
    Class<?> captured = Class.forName("dev.corefilter.pilot.PilotService$Captured");
    Constructor<?> constructor = captured.getDeclaredConstructors()[0];
    constructor.setAccessible(true);
    Object frame =
        constructor.newInstance(Vision.Result.unavailable(), "com.Overcurve.Corebound", 0L);
    Method finish =
        PilotService.class.getDeclaredMethod(
            "finishCapture",
            long.class,
            long.class,
            captured,
            String.class,
            int.class,
            int.class,
            int.class);
    finish.setAccessible(true);
    for (long id = 1; id <= 3; id++) {
      ReflectionHelpers.setField(service, "captureId", id);
      finish.invoke(service, id, 0L, frame, null, 2344, 1080, 0);
      if (id < 3) assertTrue(ReflectionHelpers.getField(service, "running"));
    }
    assertEquals(Engine.State.PAUSED, e.state());
    assertTrue(e.status().contains("Three captures"));
    assertEquals(0, e.stats().observations());
    service.onDestroy();
  }

  @Test
  public void unresponsiveOcrPausesAndRetainsItsOwnedInputUntilCompletion() {
    PilotService service = Robolectric.buildService(PilotService.class).create().get();
    Engine e = engine();
    ReflectionHelpers.setField(service, "engine", e);
    ReflectionHelpers.setField(service, "running", true);
    ReflectionHelpers.setField(service, "ocrBusy", true);
    ReflectionHelpers.setField(service, "ocrId", 4L);
    ReflectionHelpers.callInstanceMethod(
        service, "expireOcr", ReflectionHelpers.ClassParameter.from(long.class, 3L));
    assertEquals(Engine.State.OBSERVING, e.state());
    ReflectionHelpers.callInstanceMethod(
        service, "expireOcr", ReflectionHelpers.ClassParameter.from(long.class, 4L));
    assertEquals(Engine.State.PAUSED, e.state());
    assertTrue(ReflectionHelpers.getField(service, "ocrBusy"));
    assertTrue(ReflectionHelpers.getField(service, "ocrTimedOut"));
    service.onDestroy();
  }

  @Test
  public void lateOcrWatchdogCannotRestartAStoppedSession() {
    PilotService service = Robolectric.buildService(PilotService.class).create().get();
    Engine e = engine();
    ReflectionHelpers.setField(service, "engine", e);
    ReflectionHelpers.setField(service, "ocrBusy", true);
    ReflectionHelpers.setField(service, "ocrId", 5L);
    service.stopSession("Stopped");
    ReflectionHelpers.callInstanceMethod(
        service, "expireOcr", ReflectionHelpers.ClassParameter.from(long.class, 5L));
    assertEquals(Engine.State.STOPPED, e.state());
    service.onDestroy();
  }

  @Test
  public void destroyPreservesAlreadyQueuedResourceCleanup() {
    PilotService service = Robolectric.buildService(PilotService.class).create().get();
    AtomicBoolean cleaned = new AtomicBoolean();
    Handler main = ReflectionHelpers.getField(service, "main");
    main.post(() -> cleaned.set(true));
    service.onDestroy();
    shadowOf(Looper.getMainLooper()).idle();
    assertTrue(cleaned.get());
  }
}
