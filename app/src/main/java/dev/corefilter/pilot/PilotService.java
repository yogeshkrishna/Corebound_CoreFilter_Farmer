package dev.corefilter.pilot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import dev.corefilter.pilot.core.Action;
import dev.corefilter.pilot.core.Box;
import dev.corefilter.pilot.core.Calibration;
import dev.corefilter.pilot.core.Config;
import dev.corefilter.pilot.core.Engine;
import dev.corefilter.pilot.core.Frame;
import dev.corefilter.pilot.core.Observation;
import dev.corefilter.pilot.core.TextToken;
import dev.corefilter.pilot.core.Vision;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** OS-owned service. A single bounded capture/vision transaction and a single finite gesture. */
public final class PilotService extends AccessibilityService {
  private static final long OCR_TIMEOUT_MS = 15000;
  private static WeakReference<PilotService> live = new WeakReference<>(null);
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(r -> new Thread(r, "Pilot vision"));
  private final ArrayDeque<String> events = new ArrayDeque<>();
  private Config config = Config.defaults();
  private Calibration calibration;
  private Engine engine;
  private Vision vision;
  private TextRecognizer recognizer;
  private List<TextToken> text = List.of();
  private long textAt = -1, lastOcr = -10000, captureAt, captureId, gestureId, ocrId;
  private volatile long generation;
  private volatile boolean destroyed;
  private volatile boolean processing;
  private boolean running, captureBusy, gestureBusy, ocrBusy, ocrTimedOut, calibrationCapture;
  private int screenshotFailures, staleFrames;
  private WindowManager manager;
  private LinearLayout overlay;
  private TextView label;
  private Button runButton;
  private String message = "Ready · calibrate in Corebound before running";
  private int displayWidth, displayHeight;
  private volatile long visionCpuMs;
  private int[] pixels = new int[0];
  private final Runnable tick = () -> capture(false);

  public static PilotService current() {
    return live.get();
  }

  @Override
  protected void onServiceConnected() {
    super.onServiceConnected();
    live = new WeakReference<>(this);
    manager = (WindowManager) getSystemService(WINDOW_SERVICE);
    showControls();
    record("connected", message);
  }

  public String statusText() {
    Engine e = engine;
    return e == null
        ? message
        : e.state()
            + " · "
            + e.status()
            + "\nRuns ended: "
            + e.stats().endedRuns()
            + " · filter-label screens: "
            + e.stats().filterLabelScreens()
            + " (not confirmed pickups)";
  }

  public void showControls() {
    if (destroyed) return;
    if (overlay != null) {
      overlay.setVisibility(View.VISIBLE);
      updateLabel();
      return;
    }
    overlay = new LinearLayout(this);
    overlay.setOrientation(LinearLayout.HORIZONTAL);
    overlay.setGravity(Gravity.CENTER_VERTICAL);
    overlay.setPadding(5, 2, 5, 2);
    overlay.setBackgroundColor(0xEE14233D);
    label = new TextView(this);
    label.setTextColor(Color.WHITE);
    label.setTextSize(11);
    label.setMaxLines(2);
    overlay.addView(label, new LinearLayout.LayoutParams(dp(150), -2));
    runButton =
        barButton(
            "Run",
            () -> {
              if (running) pauseSession("Paused by you");
              else if (engine != null && engine.state() == Engine.State.PAUSED) resumeSession();
              else startSession();
            });
    barButton("Calibrate", this::calibrate);
    barButton(
        "Stop",
        () -> {
          stopSession("Stopped by you");
          hideControls();
        });
    WindowManager.LayoutParams params =
        new WindowManager.LayoutParams(
            -2,
            -2,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT);
    params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
    params.y = dp(36);
    label.setOnTouchListener(
        new View.OnTouchListener() {
          float x, y;
          int px, py;

          @Override
          public boolean onTouch(View v, MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
              x = event.getRawX();
              y = event.getRawY();
              px = params.x;
              py = params.y;
              return true;
            }
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
              params.x = px + (int) (event.getRawX() - x);
              params.y = Math.max(0, py + (int) (event.getRawY() - y));
              manager.updateViewLayout(overlay, params);
              return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
              v.performClick();
              return true;
            }
            return false;
          }
        });
    manager.addView(overlay, params);
    updateLabel();
  }

  private Button barButton(String title, Runnable command) {
    Button b = new Button(this);
    b.setText(title);
    b.setTextSize(10);
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    b.setPadding(dp(7), 0, dp(7), 0);
    overlay.addView(b, new LinearLayout.LayoutParams(-2, dp(40)));
    b.setOnClickListener(v -> command.run());
    return b;
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private void hideControls() {
    if (overlay != null) {
      manager.removeView(overlay);
      overlay = null;
      label = null;
      runButton = null;
    }
  }

  private void updateLabel() {
    if (label != null) {
      overlay.setKeepScreenOn(running);
      label.setText(engine == null ? message : engine.status());
      runButton.setText(
          running
              ? "Pause"
              : engine != null && engine.state() == Engine.State.PAUSED ? "Resume" : "Run");
    }
  }

  private boolean supportedGame() {
    try {
      android.content.pm.PackageInfo game =
          getPackageManager().getPackageInfo(Observation.GAME_PACKAGE, 0);
      String version = game.versionName;
      if (!"1.2.1.1".equals(version) || game.getLongVersionCode() != 140) {
        message = "Supported Corebound version is 1.2.1.1; installed: " + version;
        return false;
      }
      return true;
    } catch (PackageManager.NameNotFoundException error) {
      message = "Corebound is not installed";
      return false;
    }
  }

  private void startSession() {
    try {
      if (ocrTimedOut && ocrBusy) {
        message = "Text recognition is still unresponsive. Re-enable Pilot accessibility controls.";
        updateLabel();
        return;
      }
      if (!supportedGame()) {
        updateLabel();
        return;
      }
      SettingsStore store = new SettingsStore(this);
      calibration = store.loadCalibration();
      config = store.loadConfig();
      if (calibration == null) {
        message = "Capture and calibrate five points first";
        engine = null;
        updateLabel();
        return;
      }
      if (!Observation.GAME_PACKAGE.equals(foreground())) {
        message = "Open Corebound, then press Run";
        engine = null;
        updateLabel();
        return;
      }
      if (gestureBusy || captureBusy) {
        message = "Waiting for the previous operation to finish";
        updateLabel();
        return;
      }
      generation++;
      text = List.of();
      textAt = -1;
      lastOcr = -10000;
      screenshotFailures = 0;
      staleFrames = 0;
      engine = new Engine(config, calibration);
      vision = new Vision(calibration);
      engine.start(SystemClock.elapsedRealtime());
      running = true;
      if (recognizer == null)
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
      record("start", "New calibrated session");
      updateLabel();
      schedule(0);
    } catch (IOException | IllegalArgumentException error) {
      message = error.getMessage();
      engine = null;
      updateLabel();
      record("configuration_error", message);
    }
  }

  private void resumeSession() {
    if (engine == null || !supportedGame()) return;
    if (ocrTimedOut && ocrBusy) {
      Toast.makeText(
              this, "Re-enable Pilot controls to restart text recognition", Toast.LENGTH_LONG)
          .show();
      return;
    }
    if (!Observation.GAME_PACKAGE.equals(foreground())) {
      Toast.makeText(this, "Return to Corebound first", Toast.LENGTH_SHORT).show();
      return;
    }
    if (captureBusy || gestureBusy) return;
    generation++;
    vision = new Vision(calibration);
    text = List.of();
    textAt = -1;
    engine.resume(SystemClock.elapsedRealtime());
    running = true;
    screenshotFailures = 0;
    staleFrames = 0;
    record("resume", engine.status());
    updateLabel();
    schedule(0);
  }

  public void pauseSession(String why) {
    generation++;
    running = false;
    main.removeCallbacks(tick);
    if (engine != null) engine.pause(why);
    message = why;
    record("pause", why);
    updateLabel();
  }

  public void stopSession(String why) {
    generation++;
    running = false;
    main.removeCallbacks(tick);
    if (engine != null) engine.stop(why);
    message = why;
    record("stop", why);
    updateLabel();
    releaseStoppedResources();
  }

  private void schedule(long delay) {
    main.removeCallbacks(tick);
    long interval = Math.min(2000, Math.max(config.captureIntervalMs(), visionCpuMs * 3));
    long remaining = interval - (SystemClock.elapsedRealtime() - captureAt);
    if (running && !destroyed) main.postDelayed(tick, Math.max(delay, Math.max(0, remaining)));
  }

  private void calibrate() {
    if (!Observation.GAME_PACKAGE.equals(foreground())) {
      Toast.makeText(this, "Open a landscape Corebound run first", Toast.LENGTH_LONG).show();
      return;
    }
    pauseSession("Capturing calibration; no automatic input");
    if (captureBusy || gestureBusy) {
      message = "Let the current capture/touch finish, then tap Calibrate again";
      updateLabel();
      return;
    }
    if (overlay != null) overlay.setVisibility(View.INVISIBLE);
    scheduleCalibrationCapture();
  }

  private void scheduleCalibrationCapture() {
    long token = generation;
    main.postDelayed(
        () -> {
          if (!destroyed && token == generation) capture(true);
        },
        150);
  }

  private void capture(boolean calibrating) {
    if (destroyed || (!running && !calibrating)) return;
    if (captureBusy || gestureBusy) {
      if (!calibrating) schedule(config.captureIntervalMs());
      return;
    }
    String foreground = foreground();
    if (!Observation.GAME_PACKAGE.equals(foreground)) {
      if (calibrating) {
        message = "Return to Corebound before calibration";
        showControls();
      } else pauseSession("Another app is in the foreground");
      return;
    }
    captureBusy = true;
    calibrationCapture = calibrating;
    captureAt = SystemClock.elapsedRealtime();
    long id = ++captureId, token = generation;
    main.postDelayed(
        () -> {
          if (!destroyed && captureBusy && id == captureId) {
            captureBusy = false;
            calibrationCapture = false;
            captureId++;
            pauseSession(
                "Screen capture timed out. Resume to retry; toggle accessibility if it repeats.");
            showControls();
          }
        },
        5000);
    try {
      takeScreenshot(
          Display.DEFAULT_DISPLAY,
          getMainExecutor(),
          new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult result) {
              if (destroyed || token != generation || id != captureId) {
                result.getHardwareBuffer().close();
                finishCapture(id, token, null, null, 0, 0, 0);
                return;
              }
              if (!Observation.GAME_PACKAGE.equals(foreground())) {
                result.getHardwareBuffer().close();
                pauseSession("Corebound left the foreground during capture");
                showControls();
                finishCapture(id, token, null, null, 0, 0, 0);
                return;
              }
              worker.execute(() -> processCapture(result, id, token, foreground, calibrating));
            }

            @Override
            public void onFailure(int code) {
              finishCapture(
                  id, token, null, "Screen capture failed (Android code " + code + ")", 0, 0, code);
            }
          });
    } catch (IllegalStateException | SecurityException error) {
      finishCapture(
          id,
          token,
          null,
          "Screen capture unavailable: " + error.getMessage(),
          0,
          0,
          ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS);
    }
  }

  private void processCapture(
      ScreenshotResult result, long id, long token, String foreground, boolean calibrating) {
    processing = true;
    long cpuStart = SystemClock.currentThreadTimeMillis();
    Bitmap hardware = null, software = null, scaled = null;
    HardwareBuffer buffer = result.getHardwareBuffer();
    try {
      hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
      if (hardware == null)
        throw new IllegalStateException("Android returned an unsupported image buffer");
      software = hardware.copy(Bitmap.Config.ARGB_8888, false);
      if (software == null) throw new IllegalStateException("Could not copy the captured image");
      int w = software.getWidth(), h = software.getHeight();
      if (w <= h) throw new IllegalArgumentException("Corebound must be in landscape");
      scaled =
          Bitmap.createScaledBitmap(
              software,
              Vision.ANALYSIS_WIDTH,
              Math.max(16, Math.round((float) h * Vision.ANALYSIS_WIDTH / w)),
              true);
      if (scaled == software) scaled = software.copy(Bitmap.Config.ARGB_8888, false);
      if (scaled == null) throw new IllegalStateException("Could not prepare the analysis image");
      if (destroyed || token != generation) {
        main.post(() -> finishCapture(id, token, null, null, 0, 0, 0));
        return;
      }
      if (calibrating) {
        new SettingsStore(this).saveSnapshot(scaled);
        main.post(() -> finishCalibration(id, token, w, h));
        return;
      }
      if (pixels.length != scaled.getWidth() * scaled.getHeight())
        pixels = new int[scaled.getWidth() * scaled.getHeight()];
      scaled.getPixels(pixels, 0, scaled.getWidth(), 0, 0, scaled.getWidth(), scaled.getHeight());
      Vision.Result view =
          vision.analyze(new Frame(scaled.getWidth(), scaled.getHeight(), pixels, captureAt));
      Bitmap ocrImage = scaled;
      // OCR owns this bitmap until its completion callback; normal capture never waits for OCR.
      boolean handoff = SystemClock.elapsedRealtime() - lastOcr >= 1000 && !ocrBusy;
      if (handoff) {
        scaled = null;
        main.post(() -> startOcr(ocrImage, token, captureAt));
      }
      main.post(
          () -> finishCapture(id, token, new Captured(view, foreground, captureAt), null, w, h, 0));
    } catch (IOException | IllegalArgumentException | IllegalStateException error) {
      String why = error.getMessage();
      main.post(
          () -> finishCapture(id, token, null, why, 0, 0, ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR));
    } catch (RuntimeException error) {
      String why =
          "Internal capture failure: "
              + error.getClass().getSimpleName()
              + ": "
              + error.getMessage();
      main.post(
          () ->
              finishCapture(
                  id, token, null, why, 0, 0, ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS));
    } finally {
      visionCpuMs = SystemClock.currentThreadTimeMillis() - cpuStart;
      if (scaled != null && scaled != software) scaled.recycle();
      if (software != null) software.recycle();
      if (hardware != null) hardware.recycle();
      buffer.close();
      processing = false;
      main.post(this::releaseStoppedResources);
    }
  }

  private record Captured(Vision.Result view, String foreground, long time) {}

  private void finishCalibration(long id, long token, int w, int h) {
    finishCapture(id, token, null, null, w, h, 0);
    if (destroyed || token != generation) return;
    Intent intent =
        new Intent(this, CalibrationActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("displayWidth", w)
            .putExtra("displayHeight", h);
    startActivity(intent);
  }

  private void finishCapture(
      long id, long token, Captured frame, String error, int w, int h, int code) {
    if (id != captureId) {
      releaseStoppedResources();
      return;
    }
    captureBusy = false;
    releaseStoppedResources();
    boolean wasCalibration = calibrationCapture;
    calibrationCapture = false;
    if (destroyed) {
      finishWorker();
      return;
    }
    if (token != generation) {
      if (running) schedule(config.captureIntervalMs());
      return;
    }
    if (error != null) {
      record("capture_error", error);
      screenshotFailures++;
      if (wasCalibration) {
        message = error;
        showControls();
        return;
      }
      if (code == ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS || screenshotFailures >= 3) {
        pauseSession(error + ". Check screen access and resume.");
        return;
      }
      schedule(Math.max(1000, config.captureIntervalMs() * screenshotFailures));
      return;
    }
    screenshotFailures = 0;
    displayWidth = w;
    displayHeight = h;
    if (frame == null || !running) return;
    if (SystemClock.elapsedRealtime() - frame.time() > 1500) {
      record("stale_frame", "Discarded slow capture");
      if (++staleFrames >= 3)
        pauseSession("Three captures arrived too late. Close other apps, then resume.");
      else schedule(config.captureIntervalMs());
      return;
    }
    staleFrames = 0;
    if (!frame.foreground().equals(foreground())) {
      pauseSession("Foreground app changed during capture");
      return;
    }
    Action action =
        engine.observe(
            new Observation(frame.time(), frame.foreground(), w, h, text, textAt, frame.view()));
    record(action.kind().name(), action.reason());
    updateLabel();
    if (action.kind() == Action.Kind.PAUSE) {
      pauseSession(action.reason());
      return;
    }
    if (action.kind() == Action.Kind.STOP) {
      stopSession(action.reason());
      return;
    }
    if (action.kind() == Action.Kind.MOVE || action.kind() == Action.Kind.TAP)
      dispatch(action, token);
    else schedule(action.durationMs());
  }

  private void startOcr(Bitmap bitmap, long token, long at) {
    if (destroyed || token != generation || ocrBusy) {
      bitmap.recycle();
      return;
    }
    if (recognizer == null) {
      bitmap.recycle();
      return;
    }
    ocrBusy = true;
    ocrTimedOut = false;
    long id = ++ocrId;
    lastOcr = at;
    main.postDelayed(() -> expireOcr(id), OCR_TIMEOUT_MS);
    try {
      recognizer
          .process(InputImage.fromBitmap(bitmap, 0))
          .addOnCompleteListener(
              getMainExecutor(),
              task -> {
                try {
                  if (!destroyed && token == generation) {
                    if (task.isSuccessful()) {
                      text = readText(task.getResult(), bitmap.getWidth(), bitmap.getHeight());
                      textAt = at;
                    } else {
                      text = List.of();
                      textAt = -1;
                      record(
                          "ocr_error",
                          task.getException() == null
                              ? "Text recognition failed"
                              : task.getException().getClass().getSimpleName());
                    }
                  }
                } finally {
                  bitmap.recycle();
                  ocrBusy = false;
                  ocrTimedOut = false;
                  releaseStoppedResources();
                  if (destroyed && recognizer != null) {
                    recognizer.close();
                    recognizer = null;
                  }
                }
              });
    } catch (IllegalStateException | IllegalArgumentException error) {
      bitmap.recycle();
      ocrBusy = false;
      ocrTimedOut = false;
      text = List.of();
      textAt = -1;
      record("ocr_error", error.getMessage());
    }
  }

  private void expireOcr(long id) {
    if (destroyed || !ocrBusy || id != ocrId) return;
    ocrTimedOut = true;
    // ML Kit retains its input until completion; never recycle its bitmap from the watchdog.
    String why =
        "Text recognition timed out. Re-enable Pilot accessibility controls if it persists.";
    record("ocr_timeout", why);
    if (running) pauseSession(why);
  }

  private static List<TextToken> readText(Text recognized, int width, int height) {
    List<TextToken> tokens = new ArrayList<>();
    for (Text.TextBlock block : recognized.getTextBlocks())
      for (Text.Line line : block.getLines()) {
        Rect b = line.getBoundingBox();
        if (b == null || b.width() <= 0 || b.height() <= 0) continue;
        double l = Math.max(0, (double) b.left / width),
            t = Math.max(0, (double) b.top / height),
            r = Math.min(1, (double) b.right / width),
            bottom = Math.min(1, (double) b.bottom / height);
        if (r > l && bottom > t)
          tokens.add(new TextToken(line.getText(), new Box(l, t, r, bottom)));
      }
    return List.copyOf(tokens);
  }

  private void dispatch(Action action, long token) {
    if (!running
        || token != generation
        || gestureBusy
        || !Observation.GAME_PACKAGE.equals(foreground())) {
      engine.acknowledge(false, SystemClock.elapsedRealtime());
      pauseSession("Input guard rejected a stale or foreign-screen action");
      return;
    }
    android.graphics.Point real = new android.graphics.Point();
    manager.getDefaultDisplay().getRealSize(real);
    if (real.x != displayWidth || real.y != displayHeight) {
      engine.acknowledge(false, SystemClock.elapsedRealtime());
      pauseSession("Display rotated or resized before input; capture again after stabilizing it");
      return;
    }
    GestureDescription gesture = TouchPlan.build(action, calibration, displayWidth, displayHeight);
    gestureBusy = true;
    long id = ++gestureId;
    main.postDelayed(
        () -> {
          if (!destroyed && gestureBusy && id == gestureId) {
            gestureBusy = false;
            gestureId++;
            if (engine != null) engine.acknowledge(false, SystemClock.elapsedRealtime());
            pauseSession("Android did not acknowledge the touch. Resume to retry.");
          }
        },
        action.durationMs() + 1000L);
    boolean accepted =
        dispatchGesture(
            gesture,
            new GestureResultCallback() {
              @Override
              public void onCompleted(GestureDescription gesture) {
                complete(true);
              }

              @Override
              public void onCancelled(GestureDescription gesture) {
                complete(false);
              }

              private void complete(boolean success) {
                if (id != gestureId) return;
                gestureBusy = false;
                if (!destroyed && token == generation && engine != null) {
                  engine.acknowledge(success, SystemClock.elapsedRealtime());
                  if (engine.state() == Engine.State.PAUSED) pauseSession(engine.status());
                  else
                    schedule(
                        Math.max(
                            100,
                            config.captureIntervalMs()
                                - (SystemClock.elapsedRealtime() - captureAt)));
                }
              }
            },
            main);
    if (!accepted) {
      gestureBusy = false;
      engine.acknowledge(false, SystemClock.elapsedRealtime());
      if (engine.state() == Engine.State.PAUSED) pauseSession(engine.status());
      else schedule(config.captureIntervalMs());
    }
  }

  private String foreground() {
    for (AccessibilityWindowInfo window : getWindows()) {
      if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION
          || (!window.isActive() && !window.isFocused())) continue;
      AccessibilityNodeInfo root = window.getRoot();
      if (root == null) continue;
      try {
        CharSequence pkg = root.getPackageName();
        if (pkg != null) return pkg.toString();
      } finally {
        root.recycle();
      }
    }
    return "";
  }

  @Override
  public void onAccessibilityEvent(AccessibilityEvent event) {
    if ((running || calibrationCapture)
        && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
      String pkg = foreground();
      if (!pkg.isEmpty() && !Observation.GAME_PACKAGE.equals(pkg)) {
        pauseSession("Corebound left the foreground");
        if (calibrationCapture) showControls();
      }
    }
  }

  @Override
  public void onInterrupt() {
    stopSession("Android interrupted accessibility controls");
  }

  @Override
  public boolean onUnbind(Intent intent) {
    stopSession("Accessibility controls disconnected");
    return super.onUnbind(intent);
  }

  @Override
  public void onDestroy() {
    stopSession("Service destroyed");
    destroyed = true;
    generation++;
    main.removeCallbacks(tick);
    hideControls();
    if (!ocrBusy && recognizer != null) {
      recognizer.close();
      recognizer = null;
    }
    finishWorker();
    if (live.get() == this) live.clear();
    super.onDestroy();
  }

  private void finishWorker() {
    if (destroyed) worker.shutdown();
  }

  private void releaseStoppedResources() {
    if (running || (engine != null && engine.state() != Engine.State.STOPPED)) return;
    if (!captureBusy && !processing) {
      vision = null;
      pixels = new int[0];
    }
    if (!ocrBusy && recognizer != null) {
      recognizer.close();
      recognizer = null;
    }
    text = List.of();
    textAt = -1;
  }

  private void record(String event, String detail) {
    synchronized (events) {
      while (events.size() >= 200) events.removeFirst();
      String bounded = detail == null ? "" : detail.substring(0, Math.min(512, detail.length()));
      try {
        events.addLast(
            new JSONObject()
                .put("elapsedMs", SystemClock.elapsedRealtime())
                .put("event", event)
                .put("detail", bounded)
                .toString());
      } catch (JSONException impossible) {
        throw new IllegalStateException(impossible);
      }
    }
  }

  public String diagnostics() {
    try {
      JSONObject data =
          new JSONObject()
              .put("appVersion", "1.0.0-rc1")
              .put("supportedGameVersion", "1.2.1.1")
              .put("status", statusText())
              .put("liveFarmingVerified", false)
              .put("lastVisionCpuMs", visionCpuMs);
      if (engine != null) {
        Engine.Stats s = engine.stats();
        data.put(
            "stats",
            new JSONObject()
                .put("observations", s.observations())
                .put("movementPulses", s.movements())
                .put("menuTaps", s.taps())
                .put("runsEnded", s.endedRuns())
                .put("coreFilterLabelScreens", s.filterLabelScreens())
                .put("inputFailures", s.inputFailures())
                .put("sessionElapsedMs", s.elapsedMs()));
      }
      JSONArray history = new JSONArray();
      synchronized (events) {
        for (String item : events) history.put(new JSONObject(item));
      }
      data.put("events", history);
      return data.toString(2);
    } catch (JSONException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
