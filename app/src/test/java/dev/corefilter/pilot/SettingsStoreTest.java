package dev.corefilter.pilot;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.AtomicFile;
import dev.corefilter.pilot.core.Calibration;
import dev.corefilter.pilot.core.Config;
import dev.corefilter.pilot.core.PixelTemplate;
import dev.corefilter.pilot.core.Point;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 35)
public final class SettingsStoreTest {
  private Context context;
  private SettingsStore store;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    store = new SettingsStore(context);
    for (String name :
        new String[] {"settings.json", "calibration.json", "calibration-frame.png"}) {
      new AtomicFile(new File(context.getFilesDir(), name)).delete();
    }
  }

  @Test
  public void missingFilesHaveExplicitFirstRunMeaning() throws IOException {
    assertEquals(Config.defaults(), store.loadConfig());
    assertNull(store.loadCalibration());
    assertThrows(IOException.class, store::loadSnapshot);
  }

  @Test
  public void configRoundTripPreservesEveryPolicy() throws IOException {
    Config config = customConfig();
    store.saveConfig(config);
    assertEquals(config, store.loadConfig());
    assertFalse(new File(context.getFilesDir(), "settings.json.new").exists());
  }

  @Test
  public void calibrationRoundTripPreservesCoordinatesAndTemplates() throws IOException {
    Calibration original = calibration();
    store.saveCalibration(original);
    Calibration actual = store.loadCalibration();
    assertNotNull(actual);
    assertEquals(original, actual);
    assertArrayEquals(original.player().copyPixels(), actual.player().copyPixels());
    assertArrayEquals(original.terrain().copyPixels(), actual.terrain().copyPixels());
  }

  @Test
  public void malformedConfigDoesNotSilentlyResetPolicies() throws IOException {
    write("settings.json", "{broken");
    IOException error = assertThrows(IOException.class, store::loadConfig);
    assertTrue(error.getMessage().contains("cannot be read"));
    assertTrue(new File(context.getFilesDir(), "settings.json").exists());
  }

  @Test
  public void unsupportedConfigSchemaIsRejected() throws Exception {
    store.saveConfig(customConfig());
    mutate("settings.json", value -> value.put("schema", 2));
    assertThrows(IOException.class, store::loadConfig);
  }

  @Test
  public void missingConfigFieldIsRejected() throws Exception {
    store.saveConfig(customConfig());
    mutate("settings.json", value -> value.remove("captureIntervalMs"));
    assertThrows(IOException.class, store::loadConfig);
  }

  @Test
  public void validJsonWithUnsafePolicyIsRejected() throws Exception {
    store.saveConfig(customConfig());
    mutate("settings.json", value -> value.put("gestureMs", 900));
    assertThrows(IOException.class, store::loadConfig);
  }

  @Test
  public void oversizedConfigIsRejected() throws IOException {
    write("settings.json", " ".repeat(100_001));
    assertThrows(IOException.class, store::loadConfig);
  }

  @Test
  public void incompleteAtomicWriteRetainsLastCommittedConfig() throws IOException {
    Config expected = customConfig();
    store.saveConfig(expected);
    AtomicFile atomic = new AtomicFile(new File(context.getFilesDir(), "settings.json"));
    try (FileOutputStream stream = atomic.startWrite()) {
      stream.write("{incomplete".getBytes(StandardCharsets.UTF_8));
    }
    assertEquals(expected, store.loadConfig());
  }

  @Test
  public void atomicBackupRestoresMissingPrimary() throws IOException {
    Config expected = customConfig();
    store.saveConfig(expected);
    File primary = new File(context.getFilesDir(), "settings.json");
    byte[] committed = Files.readAllBytes(primary.toPath());
    Files.write(new File(context.getFilesDir(), "settings.json.bak").toPath(), committed);
    // A missing primary is portable to the Windows host filesystem. AtomicFile's
    // rename-over-existing semantics otherwise require an Android/Linux filesystem.
    Files.delete(primary.toPath());
    assertEquals(expected, store.loadConfig());
    assertArrayEquals(committed, Files.readAllBytes(primary.toPath()));
  }

  @Test
  public void calibrationTemplateShapeIsValidated() throws Exception {
    store.saveCalibration(calibration());
    mutate("calibration.json", value -> value.getJSONObject("player").put("side", 33));
    assertThrows(IOException.class, store::loadCalibration);
  }

  @Test
  public void calibrationSchemaIsValidatedIndependentlyOfTemplateShape() throws Exception {
    store.saveCalibration(calibration());
    mutate("calibration.json", value -> value.put("schema", -1));
    assertThrows(IOException.class, store::loadCalibration);
  }

  @Test
  public void overlappingControlCalibrationIsRejectedOnRead() throws Exception {
    store.saveCalibration(calibration());
    mutate("calibration.json", value -> value.put("jump", value.getJSONArray("left")));
    assertThrows(IOException.class, store::loadCalibration);
  }

  @Test
  public void calibrationFileCorruptionIsNotTreatedAsMissing() throws IOException {
    write("calibration.json", "[]");
    assertThrows(IOException.class, store::loadCalibration);
  }

  @Test
  public void snapshotsRoundTripAndAreRemovedExplicitly() throws IOException {
    Bitmap input = Bitmap.createBitmap(640, 300, Bitmap.Config.ARGB_8888);
    input.eraseColor(0xff102030);
    input.setPixel(80, 70, 0xfff0c020);
    try {
      store.saveSnapshot(input);
      Bitmap actual = store.loadSnapshot();
      try {
        assertEquals(640, actual.getWidth());
        assertEquals(300, actual.getHeight());
        assertEquals(input.getPixel(80, 70), actual.getPixel(80, 70));
      } finally {
        actual.recycle();
      }
      store.removeSnapshot();
      assertThrows(IOException.class, store::loadSnapshot);
    } finally {
      input.recycle();
    }
  }

  @Test
  public void invalidSnapshotDataIsRejected() throws IOException {
    write("calibration-frame.png", "not an image");
    assertThrows(IOException.class, store::loadSnapshot);
  }

  @Test
  public void snapshotDimensionsHaveAnUpperBound() throws IOException {
    Bitmap image = Bitmap.createBitmap(1281, 10, Bitmap.Config.ARGB_8888);
    try {
      store.saveSnapshot(image);
    } finally {
      image.recycle();
    }
    assertThrows(IOException.class, store::loadSnapshot);
  }

  @Test
  public void resetsAreExplicitAndIndependent() throws IOException {
    store.saveConfig(customConfig());
    store.saveCalibration(calibration());
    store.resetConfig();
    assertEquals(Config.defaults(), store.loadConfig());
    assertEquals(calibration(), store.loadCalibration());
    store.clearCalibration();
    assertNull(store.loadCalibration());
  }

  private static Config customConfig() {
    return new Config(700, 140, 9000, 5000, 2, 45000, 25, true, 3);
  }

  private static Calibration calibration() {
    int[] player = new int[25];
    int[] terrain = new int[49];
    for (int i = 0; i < player.length; i++) player[i] = i % 2 == 0 ? 0xff102030 : 0xffc0d0e0;
    for (int i = 0; i < terrain.length; i++) terrain[i] = i % 3 == 0 ? 0xff908070 : 0xff101010;
    return new Calibration(
        new Point(.12, .84),
        new Point(.23, .84),
        new Point(.85, .76),
        new PixelTemplate(5, player),
        new PixelTemplate(7, terrain),
        2344,
        1080);
  }

  private void write(String name, String value) throws IOException {
    Files.write(
        new File(context.getFilesDir(), name).toPath(), value.getBytes(StandardCharsets.UTF_8));
  }

  private void mutate(String name, JsonMutation mutation) throws Exception {
    File file = new File(context.getFilesDir(), name);
    JSONObject json =
        new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    mutation.apply(json);
    write(name, json.toString());
  }

  @FunctionalInterface
  private interface JsonMutation {
    void apply(JSONObject value) throws Exception;
  }
}
