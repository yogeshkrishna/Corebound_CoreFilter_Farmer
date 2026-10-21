package dev.corefilter.pilot;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import dev.corefilter.pilot.core.Config;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowAlertDialog;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 35)
public final class MainActivityTest {
  private static View text(View root, String value) {
    if (root instanceof TextView t && t.getText().toString().equals(value)) return root;
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

  @Test
  public void initialScreenHasExplicitSetupAndDoesNotLaunchAnotherApp() {
    try (ActivityController<MainActivity> c =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      View root = c.get().getWindow().getDecorView();
      assertNotNull(text(root, "Enable accessibility controls"));
      assertNotNull(text(root, "Show floating controls"));
      assertNotNull(text(root, "Restore default settings"));
      assertNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
    }
  }

  @Test
  public void ordinarySettingsCanBeSavedWithoutEditingSource() throws Exception {
    SettingsStore store = new SettingsStore(RuntimeEnvironment.getApplication());
    store.resetConfig();
    try (ActivityController<MainActivity> c =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      View root = c.get().getWindow().getDecorView();
      ((EditText) description(root, "Extra air jumps: 0–12")).setText("4");
      ((EditText) description(root, "Session minutes")).setText("20");
      text(root, "Save settings").performClick();
      assertEquals(4, store.loadConfig().maxAirJumps());
      assertEquals(20, store.loadConfig().maxSessionMinutes());
    }
  }

  @Test
  public void corruptedSettingsHaveAWorkingExplicitRecoveryButton() throws Exception {
    SettingsStore store = new SettingsStore(RuntimeEnvironment.getApplication());
    File file = new File(RuntimeEnvironment.getApplication().getFilesDir(), "settings.json");
    Files.write(file.toPath(), "broken".getBytes(StandardCharsets.UTF_8));
    try (ActivityController<MainActivity> c =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      text(c.get().getWindow().getDecorView(), "Restore default settings").performClick();
      AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
      assertNotNull(dialog);
      dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
      shadowOf(Looper.getMainLooper()).idle();
      assertEquals(Config.defaults(), store.loadConfig());
    }
    shadowOf(Looper.getMainLooper()).idle();
  }
}
