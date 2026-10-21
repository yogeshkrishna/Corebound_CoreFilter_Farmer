package dev.corefilter.pilot;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import dev.corefilter.pilot.core.Calibration;
import dev.corefilter.pilot.core.Frame;
import dev.corefilter.pilot.core.Point;
import dev.corefilter.pilot.core.Vision;
import java.io.IOException;

public final class CalibrationActivity extends Activity {
  private static final String[] PROMPTS = {
    "Tap the LEFT movement button",
    "Tap the RIGHT movement button",
    "Tap a clear JUMP area outside the movement controls",
    "Tap the CENTER of your player (a textured body/core region)",
    "Tap a TEXTURED MAIN FLOOR tile, away from an edge, platform, or background"
  };
  private final Point[] points = new Point[5];
  private Bitmap bitmap;
  private Frame frame;
  private int count, displayWidth, displayHeight;
  private TextView prompt;
  private Button save;
  private Picture picture;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow()
        .setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN);
    try {
      bitmap = new SettingsStore(this).loadSnapshot();
    } catch (IOException error) {
      Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
      finish();
      return;
    }
    displayWidth = getIntent().getIntExtra("displayWidth", 0);
    displayHeight = getIntent().getIntExtra("displayHeight", 0);
    if (displayWidth <= displayHeight || bitmap.getWidth() != Vision.ANALYSIS_WIDTH) {
      Toast.makeText(this, "Capture a landscape Corebound screen first", Toast.LENGTH_LONG).show();
      finish();
      return;
    }
    int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
    bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
    frame = new Frame(bitmap.getWidth(), bitmap.getHeight(), pixels, 0);
    if (state != null) {
      double[] saved = state.getDoubleArray("points");
      count = state.getInt("count", 0);
      if (saved != null && saved.length == count * 2 && count <= 5)
        for (int i = 0; i < count; i++) points[i] = new Point(saved[i * 2], saved[i * 2 + 1]);
      else count = 0;
    }
    LinearLayout body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setBackgroundColor(Color.BLACK);
    prompt = new TextView(this);
    prompt.setTextColor(Color.WHITE);
    prompt.setTextSize(16);
    prompt.setPadding(12, 8, 12, 8);
    body.addView(prompt);
    picture = new Picture();
    body.addView(picture, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout buttons = new LinearLayout(this);
    Button undo = new Button(this);
    undo.setText("Undo");
    buttons.addView(undo);
    undo.setOnClickListener(
        v -> {
          if (count > 0) {
            points[--count] = null;
            update();
          }
        });
    Button cancel = new Button(this);
    cancel.setText("Cancel");
    buttons.addView(cancel);
    cancel.setOnClickListener(v -> finish());
    save = new Button(this);
    save.setText("Save calibration");
    buttons.addView(save);
    save.setOnClickListener(v -> save());
    body.addView(buttons);
    setContentView(body);
    update();
  }

  private void update() {
    prompt.setText(
        count < 5
            ? (count + 1) + " / 5 · " + PROMPTS[count]
            : "Check the five marked points, then save.");
    save.setEnabled(count == 5);
    picture.invalidate();
  }

  private void save() {
    try {
      Calibration calibration =
          new Calibration(
              points[0],
              points[1],
              points[2],
              Vision.extract(frame, points[3], 21),
              Vision.extract(frame, points[4], 25),
              displayWidth,
              displayHeight);
      SettingsStore store = new SettingsStore(this);
      store.saveCalibration(calibration);
      store.removeSnapshot();
      PilotService service = PilotService.current();
      if (service != null) service.stopSession("Calibration saved; press Run to load it");
      Toast.makeText(
              this, "Calibration saved. Return to Corebound and press Run.", Toast.LENGTH_LONG)
          .show();
      finish();
    } catch (IOException | IllegalArgumentException error) {
      Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    double[] values = new double[count * 2];
    for (int i = 0; i < count; i++) {
      values[i * 2] = points[i].x();
      values[i * 2 + 1] = points[i].y();
    }
    out.putDoubleArray("points", values);
    out.putInt("count", count);
  }

  @Override
  protected void onDestroy() {
    if (bitmap != null) {
      bitmap.recycle();
      bitmap = null;
    }
    if (isFinishing()) {
      new SettingsStore(this).removeSnapshot();
      PilotService s = PilotService.current();
      if (s != null) s.showControls();
    }
    super.onDestroy();
  }

  private final class Picture extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    Picture() {
      super(CalibrationActivity.this);
      setContentDescription("Frozen game screenshot; tap the requested calibration point");
    }

    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      if (bitmap == null) return;
      float scale =
          Math.min(
              (float) getWidth() / bitmap.getWidth(), (float) getHeight() / bitmap.getHeight());
      float w = bitmap.getWidth() * scale, h = bitmap.getHeight() * scale;
      rect.set(
          (getWidth() - w) / 2, (getHeight() - h) / 2, (getWidth() + w) / 2, (getHeight() + h) / 2);
      canvas.drawBitmap(bitmap, null, rect, paint);
      paint.setTextSize(18);
      for (int i = 0; i < count; i++) {
        float x = rect.left + (float) points[i].x() * rect.width(),
            y = rect.top + (float) points[i].y() * rect.height();
        paint.setColor(Color.CYAN);
        canvas.drawCircle(x, y, 7, paint);
        paint.setColor(Color.WHITE);
        canvas.drawText(Integer.toString(i + 1), x + 9, y - 5, paint);
      }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
      if (event.getAction() != MotionEvent.ACTION_UP) return true;
      if (count < 5 && rect.contains(event.getX(), event.getY())) {
        points[count++] =
            new Point(
                (event.getX() - rect.left) / rect.width(),
                (event.getY() - rect.top) / rect.height());
        update();
      }
      performClick();
      return true;
    }

    @Override
    public boolean performClick() {
      super.performClick();
      return true;
    }
  }
}
