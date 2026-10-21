package dev.corefilter.pilot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import dev.corefilter.pilot.core.Config;
import dev.corefilter.pilot.core.Observation;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity {
  private final Handler handler = new Handler(Looper.getMainLooper());
  private TextView status;
  private EditText minutes, jumps;
  private CheckBox ads;
  private SettingsStore store;
  private final Runnable refresh =
      new Runnable() {
        @Override
        public void run() {
          PilotService service = PilotService.current();
          status.setText(
              service == null
                  ? "Controls disconnected. Enable the accessibility service below."
                  : service.statusText());
          handler.postDelayed(this, 1000);
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    store = new SettingsStore(this);
    ScrollView scroll = new ScrollView(this);
    LinearLayout body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    int pad = (int) (20 * getResources().getDisplayMetrics().density);
    body.setPadding(pad, pad, pad, pad);
    body.setBackgroundColor(Color.rgb(244, 246, 250));
    scroll.addView(body);
    setContentView(scroll);
    scroll.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
          body.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom);
          return insets;
        });
    text(body, "Core Filter Pilot", 26);
    text(body, "Lost Scrapyard • Corebound 1.2.1.1 • Android 11+", 14);
    status = text(body, "Checking controls…", 16);
    status.setPadding(0, 20, 0, 20);
    text(
        body,
        "1. Enable controls. 2. Open Corebound with left/right buttons and enter Lost Scrapyard. 3."
            + " From the floating bar, capture and calibrate five points. Then press Run.",
        16);
    text(
        body,
        "Screen images stay on this phone. Pilot observes the game and sends movement and jump"
            + " touches only while a session is running. It does not control other apps. Unknown"
            + " screens pause the session.",
        14);
    button(
        body,
        "Enable accessibility controls",
        () ->
            new AlertDialog.Builder(this)
                .setTitle("Enable Core Filter Pilot controls")
                .setMessage(
                    "Android will allow Pilot to observe the screen and send calibrated touches."
                        + " Enable only ‘Core Filter Pilot controls’. For a sideloaded APK, App"
                        + " info → menu → Allow restricted settings may be required first.")
                .setPositiveButton(
                    "Open settings",
                    (d, w) -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .setNegativeButton("Cancel", null)
                .show());
    button(
        body,
        "Show floating controls",
        () -> {
          PilotService s = PilotService.current();
          if (s == null) message("Enable the accessibility service first");
          else s.showControls();
        });
    button(body, "Open Corebound", this::openGame);
    text(body, "Your actual equipment", 20);
    text(
        body,
        "Additional air jumps (exclude the first ground jump). Leave 0 unless your equipped build"
            + " supports more. No jump count or damage is inferred from item notes.",
        14);
    jumps = number(body, "Extra air jumps: 0–12");
    text(body, "Maximum session length in minutes (1–720)", 14);
    minutes = number(body, "Session minutes");
    ads = new CheckBox(this);
    ads.setText("Accept recognized free reward-video dialogs");
    body.addView(ads);
    text(
        body,
        "Unknown ad formats and external store screens pause for you. No paid bonus, shard"
            + " purchase, or consent dialog is accepted.",
        14);
    Config config = Config.defaults();
    try {
      config = store.loadConfig();
    } catch (IOException error) {
      message(error.getMessage());
    }
    jumps.setText(Integer.toString(config.maxAirJumps()));
    minutes.setText(Integer.toString(config.maxSessionMinutes()));
    ads.setChecked(config.watchRewardAds());
    button(body, "Save settings", this::save);
    button(
        body,
        "Restore default settings",
        () ->
            new AlertDialog.Builder(this)
                .setTitle("Restore default settings?")
                .setMessage(
                    "This resets equipment, session length and video preferences. Calibration"
                        + " remains.")
                .setPositiveButton(
                    "Restore",
                    (d, w) -> {
                      PilotService s = PilotService.current();
                      if (s != null) s.stopSession("Settings reset");
                      store.resetConfig();
                      Config defaults = Config.defaults();
                      jumps.setText(Integer.toString(defaults.maxAirJumps()));
                      minutes.setText(Integer.toString(defaults.maxSessionMinutes()));
                      ads.setChecked(defaults.watchRewardAds());
                      message("Default settings restored");
                    })
                .setNegativeButton("Cancel", null)
                .show());
    button(body, "Share local diagnostics", this::shareDiagnostics);
    button(
        body,
        "Reset calibration",
        () ->
            new AlertDialog.Builder(this)
                .setTitle("Reset calibration?")
                .setMessage(
                    "You will need to select the five points again. Saved equipment settings"
                        + " remain.")
                .setPositiveButton(
                    "Reset",
                    (d, w) -> {
                      PilotService s = PilotService.current();
                      if (s != null) s.stopSession("Calibration reset");
                      store.clearCalibration();
                      message("Calibration cleared");
                    })
                .setNegativeButton("Cancel", null)
                .show());
    text(body, "Validation boundary", 20);
    text(
        body,
        "Geometry and controls come from the audited APK. Navigation is a calibrated visual patrol,"
            + " not a seed reconstruction or a guarantee of complete ceiling coverage. A Core"
            + " Filter label or ended run is not counted as an earned filter. Live farming has not"
            + " been verified on a connected phone.",
        14);
  }

  @Override
  protected void onResume() {
    super.onResume();
    handler.removeCallbacks(refresh);
    handler.post(refresh);
  }

  @Override
  protected void onPause() {
    handler.removeCallbacks(refresh);
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    handler.removeCallbacks(refresh);
    super.onDestroy();
  }

  private void openGame() {
    Intent intent = getPackageManager().getLaunchIntentForPackage(Observation.GAME_PACKAGE);
    if (intent == null) message("Corebound is not installed under its supported package name");
    else startActivity(intent);
  }

  private void save() {
    try {
      Config old = store.loadConfig();
      Config next =
          new Config(
              old.captureIntervalMs(),
              old.gestureMs(),
              old.unknownTimeoutMs(),
              old.stuckTimeoutMs(),
              old.maxRecoveryAttempts(),
              old.adTimeoutMs(),
              Integer.parseInt(minutes.getText().toString().trim()),
              ads.isChecked(),
              Integer.parseInt(jumps.getText().toString().trim()));
      PilotService s = PilotService.current();
      if (s != null) s.stopSession("Settings changed; press Run to begin a new session");
      store.saveConfig(next);
      message("Settings saved");
    } catch (IOException | IllegalArgumentException error) {
      message(error.getMessage() == null ? "Enter valid whole numbers" : error.getMessage());
    }
  }

  private void shareDiagnostics() {
    try {
      PilotService s = PilotService.current();
      String json = s == null ? "{\"status\":\"service_disconnected\"}" : s.diagnostics();
      File file = new File(getCacheDir(), "session.json");
      try (FileOutputStream out = new FileOutputStream(file)) {
        out.write(json.getBytes(StandardCharsets.UTF_8));
      }
      Uri uri = Uri.parse("content://" + getPackageName() + ".diagnostics/session.json");
      Intent intent =
          new Intent(Intent.ACTION_SEND)
              .setType("application/json")
              .putExtra(Intent.EXTRA_STREAM, uri)
              .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      intent.setClipData(ClipData.newRawUri("Pilot diagnostics", uri));
      startActivity(Intent.createChooser(intent, "Share diagnostics"));
    } catch (IOException error) {
      message("Could not prepare diagnostics: " + error.getMessage());
    }
  }

  private TextView text(LinearLayout parent, String value, int size) {
    TextView v = new TextView(this);
    v.setText(value);
    v.setTextSize(size);
    v.setTextColor(Color.rgb(30, 41, 59));
    v.setPadding(0, 8, 0, 8);
    parent.addView(v);
    return v;
  }

  private EditText number(LinearLayout parent, String label) {
    EditText e = new EditText(this);
    e.setHint(label);
    e.setContentDescription(label);
    e.setInputType(InputType.TYPE_CLASS_NUMBER);
    parent.addView(e);
    return e;
  }

  private void button(LinearLayout parent, String label, Runnable action) {
    Button b = new Button(this);
    b.setText(label);
    parent.addView(b);
    b.setOnClickListener(v -> action.run());
  }

  private void message(String value) {
    Toast.makeText(this, value, Toast.LENGTH_LONG).show();
  }
}
