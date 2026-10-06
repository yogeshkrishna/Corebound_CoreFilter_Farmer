package com.corefilter.farmer;

import android.app.AlertDialog;
import android.content.*;
import android.os.Looper;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import java.io.File;
import java.lang.reflect.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class OfflinePreviewUiTest {
    @Test public void missingPreviewStillAllowsNativeExportAndRetainsRecording()throws Exception{
        Context c=RuntimeEnvironment.getApplication();File recording=RecordingStore.create(c);
        RecordingStore.write(new File(recording,"map.json"),new JSONObject().put("sections",new JSONArray().put(new JSONObject().put("bounds",new JSONArray(new int[]{0,0,8000,3000})))));
        RecordingStore.finish(recording,"Native map ready");
        var controller=Robolectric.buildActivity(OfflineMapsActivity.class).setup();OfflineMapsActivity activity=controller.get();
        Method preview=OfflineMapsActivity.class.getDeclaredMethod("preview",File.class,int.class);preview.setAccessible(true);preview.invoke(activity,recording,0);
        Field load=OfflineMapsActivity.class.getDeclaredField("previewLoad");load.setAccessible(true);((Future<?>)load.get(activity)).get(3,TimeUnit.SECONDS);shadowOf(Looper.getMainLooper()).idle();
        AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertTrue(dialog.isShowing());assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Intent.ACTION_CREATE_DOCUMENT,shadowOf(activity).getNextStartedActivity().getAction());
        assertTrue(new File(recording,"map.json").isFile());assertTrue(new File(recording,"recording.json").isFile());
        controller.pause().stop().destroy();
    }
}
