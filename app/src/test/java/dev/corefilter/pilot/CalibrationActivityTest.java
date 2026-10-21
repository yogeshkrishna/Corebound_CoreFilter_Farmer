package dev.corefilter.pilot;

import static org.junit.Assert.*;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import dev.corefilter.pilot.core.Calibration;
import dev.corefilter.pilot.core.Frame;
import dev.corefilter.pilot.core.Point;
import dev.corefilter.pilot.core.Vision;
import java.io.IOException;
import java.util.Random;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowToast;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 35)
public final class CalibrationActivityTest {
  private static final Point LEFT = new Point(.15, .85);
  private static final Point RIGHT = new Point(.28, .85);
  private static final Point JUMP = new Point(.8, .82);
  private static final Point PLAYER = new Point(.5, .5);
  private static final Point TERRAIN = new Point(.65, .7);
  private SettingsStore store;
  private Frame source;

  @Before
  public void setUp() throws IOException {
    store = new SettingsStore(RuntimeEnvironment.getApplication());
    store.clearCalibration();
    store.removeSnapshot();
    int[] pixels = new int[640 * 295];
    Random random = new Random(314159);
    for (int i = 0; i < pixels.length; i++) {
      int level = 30 + random.nextInt(190);
      pixels[i] = 0xff000000 | level * 0x010101;
    }
    source = new Frame(640, 295, pixels, 0);
    saveSnapshot(pixels);
  }

  @Test
  public void fiveTouchesSaveNormalizedCoordinatesAndExactTemplates() throws IOException {
    try (ActivityController<CalibrationActivity> controller = open()) {
      CalibrationActivity activity = controller.get();
      View picture = picture(activity);
      View save = text(activity.getWindow().getDecorView(), "Save calibration");
      assertFalse(save.isEnabled());
      chooseFive(picture, PLAYER);
      assertTrue(save.isEnabled());
      save.performClick();

      Calibration result = store.loadCalibration();
      assertNotNull(result);
      assertPoint(LEFT, result.left());
      assertPoint(RIGHT, result.right());
      assertPoint(JUMP, result.jump());
      assertEquals(2344, result.displayWidth());
      assertEquals(1080, result.displayHeight());
      assertEquals(Vision.extract(source, PLAYER, 21), result.player());
      assertEquals(Vision.extract(source, TERRAIN, 25), result.terrain());
      assertTrue(activity.isFinishing());
      assertFalse(store.snapshotFile().exists());
    }
  }

  @Test
  public void letterboxTouchesAreIgnoredAndUndoRequiresAReplacementPoint() throws IOException {
    try (ActivityController<CalibrationActivity> controller = open()) {
      CalibrationActivity activity = controller.get();
      View picture = picture(activity);
      touch(picture, 640, 50);
      assertNotNull(
          text(activity.getWindow().getDecorView(), "1 / 5 · Tap the LEFT movement button"));
      chooseFive(picture, PLAYER);
      View save = text(activity.getWindow().getDecorView(), "Save calibration");
      assertTrue(save.isEnabled());
      text(activity.getWindow().getDecorView(), "Undo").performClick();
      assertFalse(save.isEnabled());
      assertNull(store.loadCalibration());
      choose(picture, TERRAIN);
      assertTrue(save.isEnabled());
      save.performClick();
      assertNotNull(store.loadCalibration());
    }
  }

  @Test
  public void cancelPreservesExistingProfileAndRemovesTheFrozenScreenshot() throws IOException {
    Calibration original = profile();
    store.saveCalibration(original);
    try (ActivityController<CalibrationActivity> controller = open()) {
      CalibrationActivity activity = controller.get();
      choose(picture(activity), LEFT);
      text(activity.getWindow().getDecorView(), "Cancel").performClick();
      assertTrue(activity.isFinishing());
      assertEquals(original, store.loadCalibration());
    }
    assertFalse(store.snapshotFile().exists());
  }

  @Test
  public void flatPlayerPatchCannotOverwriteAnExistingProfile() throws IOException {
    Calibration original = profile();
    store.saveCalibration(original);
    int[] pixels = source.argb().clone();
    for (int y = 130; y < 165; y++)
      for (int x = 300; x < 340; x++) pixels[y * 640 + x] = 0xff202020;
    saveSnapshot(pixels);
    Bitmap saved = store.loadSnapshot();
    assertEquals(0xff202020, saved.getPixel(320, 147));
    saved.recycle();
    try (ActivityController<CalibrationActivity> controller = open()) {
      CalibrationActivity activity = controller.get();
      chooseFive(picture(activity), PLAYER);
      text(activity.getWindow().getDecorView(), "Save calibration").performClick();
      assertFalse(activity.isFinishing());
      assertEquals(original, store.loadCalibration());
      assertTrue(store.snapshotFile().exists());
      assertTrue(ShadowToast.getTextOfLatestToast().contains("textured patch"));
    }
  }

  @Test
  public void playerPatchAtScreenEdgeFailsWithoutSavingOrClosing() throws IOException {
    try (ActivityController<CalibrationActivity> controller = open()) {
      CalibrationActivity activity = controller.get();
      chooseFive(picture(activity), new Point(.005, .5));
      text(activity.getWindow().getDecorView(), "Save calibration").performClick();
      assertFalse(activity.isFinishing());
      assertNull(store.loadCalibration());
      assertTrue(store.snapshotFile().exists());
      assertTrue(ShadowToast.getTextOfLatestToast().contains("screen edge"));
    }
  }

  private Calibration profile() {
    return new Calibration(
        LEFT,
        RIGHT,
        JUMP,
        Vision.extract(source, PLAYER, 21),
        Vision.extract(source, TERRAIN, 25),
        2344,
        1080);
  }

  private void saveSnapshot(int[] pixels) throws IOException {
    // Fixture replacement avoids the host-Windows rename-to-existing difference in AtomicFile.
    store.removeSnapshot();
    Bitmap bitmap = Bitmap.createBitmap(pixels, 640, 295, Bitmap.Config.ARGB_8888);
    try {
      store.saveSnapshot(bitmap);
    } finally {
      bitmap.recycle();
    }
  }

  private static ActivityController<CalibrationActivity> open() {
    Intent intent =
        new Intent(RuntimeEnvironment.getApplication(), CalibrationActivity.class)
            .putExtra("displayWidth", 2344)
            .putExtra("displayHeight", 1080);
    return Robolectric.buildActivity(CalibrationActivity.class, intent).setup();
  }

  private static View picture(CalibrationActivity activity) {
    View picture =
        description(
            activity.getWindow().getDecorView(),
            "Frozen game screenshot; tap the requested calibration point");
    assertNotNull(picture);
    picture.measure(
        View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY));
    picture.layout(0, 0, 1280, 900);
    Bitmap canvas = Bitmap.createBitmap(1280, 900, Bitmap.Config.ARGB_8888);
    try {
      picture.draw(new Canvas(canvas));
    } finally {
      canvas.recycle();
    }
    return picture;
  }

  private static void chooseFive(View picture, Point player) {
    for (Point point : new Point[] {LEFT, RIGHT, JUMP, player, TERRAIN}) choose(picture, point);
  }

  private static void choose(View picture, Point point) {
    // The 640x295 image scales to 1280x590, centered vertically in the 1280x900 view.
    touch(picture, (float) (point.x() * 1280), (float) (155 + point.y() * 590));
  }

  private static void touch(View picture, float x, float y) {
    MotionEvent event = MotionEvent.obtain(0, 10, MotionEvent.ACTION_UP, x, y, 0);
    try {
      picture.dispatchTouchEvent(event);
    } finally {
      event.recycle();
    }
  }

  private static void assertPoint(Point expected, Point actual) {
    assertEquals(expected.x(), actual.x(), .000001);
    assertEquals(expected.y(), actual.y(), .000001);
  }

  private static View text(View root, String value) {
    if (root instanceof TextView text && text.getText().toString().equals(value)) return root;
    if (root instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = text(group.getChildAt(i), value);
        if (found != null) return found;
      }
    return null;
  }

  private static View description(View root, String value) {
    if (value.contentEquals(
        root.getContentDescription() == null ? "" : root.getContentDescription())) return root;
    if (root instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = description(group.getChildAt(i), value);
        if (found != null) return found;
      }
    return null;
  }
}
