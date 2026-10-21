package dev.corefilter.pilot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.AtomicFile;
import dev.corefilter.pilot.core.Calibration;
import dev.corefilter.pilot.core.Config;
import dev.corefilter.pilot.core.PixelTemplate;
import dev.corefilter.pilot.core.Point;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class SettingsStore {
  private static final int SCHEMA = 1;
  private final File directory;

  SettingsStore(Context context) {
    directory = context.getFilesDir();
  }

  Config loadConfig() throws IOException {
    JSONObject value = read("settings.json");
    if (value == null) return Config.defaults();
    try {
      checkSchema(value);
      return new Config(
          value.getInt("captureIntervalMs"),
          value.getInt("gestureMs"),
          value.getInt("unknownTimeoutMs"),
          value.getInt("stuckTimeoutMs"),
          value.getInt("maxRecoveryAttempts"),
          value.getInt("adTimeoutMs"),
          value.getInt("maxSessionMinutes"),
          value.getBoolean("watchRewardAds"),
          value.getInt("maxAirJumps"));
    } catch (JSONException | IllegalArgumentException error) {
      throw new IOException("Saved settings are invalid. Reset settings in the app.", error);
    }
  }

  void saveConfig(Config config) throws IOException {
    try {
      JSONObject value =
          new JSONObject()
              .put("schema", SCHEMA)
              .put("captureIntervalMs", config.captureIntervalMs())
              .put("gestureMs", config.gestureMs())
              .put("unknownTimeoutMs", config.unknownTimeoutMs())
              .put("stuckTimeoutMs", config.stuckTimeoutMs())
              .put("maxRecoveryAttempts", config.maxRecoveryAttempts())
              .put("adTimeoutMs", config.adTimeoutMs())
              .put("maxSessionMinutes", config.maxSessionMinutes())
              .put("watchRewardAds", config.watchRewardAds())
              .put("maxAirJumps", config.maxAirJumps());
      write("settings.json", value);
    } catch (JSONException error) {
      throw new IOException("Could not encode settings", error);
    }
  }

  Calibration loadCalibration() throws IOException {
    JSONObject value = read("calibration.json");
    if (value == null) return null;
    try {
      checkSchema(value);
      return new Calibration(
          point(value.getJSONArray("left")),
          point(value.getJSONArray("right")),
          point(value.getJSONArray("jump")),
          template(value.getJSONObject("player")),
          template(value.getJSONObject("terrain")),
          value.getInt("displayWidth"),
          value.getInt("displayHeight"));
    } catch (JSONException | IllegalArgumentException error) {
      throw new IOException(
          "Saved calibration is invalid. Calibrate again from the floating bar.", error);
    }
  }

  void saveCalibration(Calibration calibration) throws IOException {
    try {
      write(
          "calibration.json",
          new JSONObject()
              .put("schema", SCHEMA)
              .put("left", point(calibration.left()))
              .put("right", point(calibration.right()))
              .put("jump", point(calibration.jump()))
              .put("player", template(calibration.player()))
              .put("terrain", template(calibration.terrain()))
              .put("displayWidth", calibration.displayWidth())
              .put("displayHeight", calibration.displayHeight()));
    } catch (JSONException error) {
      throw new IOException("Could not encode calibration", error);
    }
  }

  File snapshotFile() {
    return new File(directory, "calibration-frame.png");
  }

  void saveSnapshot(Bitmap bitmap) throws IOException {
    AtomicFile file = new AtomicFile(snapshotFile());
    FileOutputStream stream = file.startWrite();
    try {
      if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        throw new IOException("Snapshot encoder failed");
      file.finishWrite(stream);
    } catch (IOException | RuntimeException error) {
      file.failWrite(stream);
      throw error;
    }
  }

  Bitmap loadSnapshot() throws IOException {
    File file = snapshotFile();
    if (!file.isFile() || file.length() > 8_000_000)
      throw new IOException("Calibration image is missing. Capture again.");
    byte[] signature = new byte[8];
    try (FileInputStream input = new FileInputStream(file)) {
      byte[] png = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
      if (input.read(signature) != 8 || !java.util.Arrays.equals(signature, png)) {
        throw new IOException("Calibration image is not a valid PNG. Capture again.");
      }
    }
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
    if (bounds.outWidth < 1
        || bounds.outHeight < 1
        || bounds.outWidth > 1280
        || bounds.outHeight > 1280) {
      throw new IOException("Calibration image dimensions are invalid. Capture again.");
    }
    Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
    if (bitmap == null || bitmap.getWidth() > 1280 || bitmap.getHeight() > 1280) {
      if (bitmap != null) bitmap.recycle();
      throw new IOException("Calibration image is invalid. Capture again.");
    }
    return bitmap;
  }

  void removeSnapshot() {
    new AtomicFile(snapshotFile()).delete();
  }

  void resetConfig() {
    new AtomicFile(new File(directory, "settings.json")).delete();
  }

  void clearCalibration() {
    new AtomicFile(new File(directory, "calibration.json")).delete();
  }

  private JSONObject read(String name) throws IOException {
    AtomicFile file = new AtomicFile(new File(directory, name));
    if (!file.getBaseFile().exists() && !new File(directory, name + ".bak").exists()) return null;
    try {
      byte[] bytes;
      try (FileInputStream input = file.openRead();
          ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        byte[] chunk = new byte[4096];
        int count;
        while ((count = input.read(chunk)) != -1) {
          if (output.size() + count > 100_000)
            throw new IOException("Saved configuration is too large");
          output.write(chunk, 0, count);
        }
        bytes = output.toByteArray();
      }
      return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    } catch (JSONException error) {
      throw new IOException("Saved configuration cannot be read", error);
    }
  }

  private void write(String name, JSONObject value) throws IOException {
    AtomicFile file = new AtomicFile(new File(directory, name));
    FileOutputStream stream = file.startWrite();
    try {
      stream.write(value.toString().getBytes(StandardCharsets.UTF_8));
      file.finishWrite(stream);
    } catch (IOException | RuntimeException error) {
      file.failWrite(stream);
      throw error;
    }
  }

  private static void checkSchema(JSONObject value) throws JSONException {
    if (value.getInt("schema") != SCHEMA) throw new JSONException("Unsupported schema");
  }

  private static Point point(JSONArray value) throws JSONException {
    if (value.length() != 2) throw new JSONException("Invalid point");
    return new Point(value.getDouble(0), value.getDouble(1));
  }

  private static JSONArray point(Point value) throws JSONException {
    return new JSONArray().put(value.x()).put(value.y());
  }

  private static PixelTemplate template(JSONObject value) throws JSONException {
    JSONArray values = value.getJSONArray("pixels");
    int side = value.getInt("side");
    if (side < 5 || side > 33 || values.length() != side * side)
      throw new JSONException("Invalid template dimensions");
    int[] pixels = new int[values.length()];
    for (int i = 0; i < pixels.length; i++) pixels[i] = values.getInt(i);
    return new PixelTemplate(side, pixels);
  }

  private static JSONObject template(PixelTemplate value) throws JSONException {
    JSONArray pixels = new JSONArray();
    for (int pixel : value.copyPixels()) pixels.put(pixel);
    return new JSONObject().put("side", value.side()).put("pixels", pixels);
  }
}
