package com.corefilter.farmer;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import com.corefilter.farmer.engine.FarmEngine;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

/** Android lifecycle/preferences smoke tests; these do not simulate game play or Accessibility. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class MainActivityTest {
    @Before public void resetState() {
        FarmerService.instance = null;
        RuntimeEnvironment.getApplication().getSharedPreferences("profile", 0).edit().clear().commit();
        RuntimeEnvironment.getApplication().getSharedPreferences("mode", 0).edit().clear().commit();
    }
    @Test public void modeSelectionPersistsAndScreenToolsStayInApp(){
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            MainActivity activity=controller.get();View root=activity.getWindow().getDecorView();
            root.findViewWithTag("mode-manual").performClick();controller.recreate();activity=controller.get();
            assertTrue(textOf(activity.getWindow().getDecorView()).contains("Selected: Offline mapper"));
            assertTrue(textOf(activity.getWindow().getDecorView()).contains("Start live capture"));
            assertFalse(textOf(activity.getWindow().getDecorView()).contains("Saved maps & Wi-Fi transfer"));
            activity.getWindow().getDecorView().findViewWithTag("mode-farmer").performClick();
            assertFalse(activity.getSharedPreferences("mode",0).getBoolean("manualMapping",true));
        }
    }

    @Test public void laptopPairingRejectsBadLinksAndSurvivesRecreation(){
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            MainActivity activity=controller.get();activity.getSharedPreferences("live",0).edit().clear().commit();
            activity.getWindow().getDecorView().findViewWithTag("pair-laptop").performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            EditText input=dialog.getWindow().getDecorView().findViewWithTag("laptop-link");
            input.setText("http://8.8.8.8:8767/connect/"+"A".repeat(43));dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertFalse(activity.getSharedPreferences("live",0).contains("endpoint"));assertNotNull(input.getError());
            String link="http://192.168.68.63:8767/connect/"+"A".repeat(43);input.setText(link);dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            controller.recreate();assertEquals(link,controller.get().getSharedPreferences("live",0).getString("endpoint",""));
            assertTrue(textOf(controller.get().getWindow().getDecorView()).contains("Paired: http://192.168.68.63:8767"));
        }
    }

    @Test public void homeStartsAndExplainsControlsWithoutStartingAutomation() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            String visibleText = textOf(activity.getWindow().getDecorView());
            assertTrue(visibleText.contains("Ceiling Scout"));
            assertTrue(visibleText.contains("Enable controls"));
            assertTrue(visibleText.contains("Edit build & farming settings"));
            assertTrue(visibleText.contains("Check for updates"));
            assertTrue(visibleText.contains("Connect laptop"));
            assertTrue(visibleText.contains("Record a map"));
            assertTrue(visibleText.contains("Recorded maps"));
            assertTrue(visibleText.contains("3 hookshots · 7 jumps"));
            assertNotNull(activity.findViewById(android.R.id.content).findViewWithTag("dashboard-insets"));
            assertNotNull(activity.findViewById(android.R.id.content).findViewWithTag("check-updates"));
            ViewGroup dashboard=activity.findViewById(android.R.id.content).findViewWithTag("dashboard-insets");
            int top=Ui.dp(activity,24),bottom=Ui.dp(activity,32);
            dashboard.dispatchApplyWindowInsets(new WindowInsets.Builder()
                    .setInsets(WindowInsets.Type.systemBars(),Insets.of(0,top,0,bottom)).build());
            assertEquals(top,dashboard.getPaddingTop());assertEquals(bottom,dashboard.getPaddingBottom());
            // Check the narrow portrait layout, including cards below the scroll fold.
            int width=Ui.dp(activity,360),height=Ui.dp(activity,800);
            dashboard.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
            dashboard.layout(0,0,width,height);
            for(String key:new String[]{"connect-controls","open-game","edit-build","check-updates","pair-laptop","start-live","record-offline","offline-maps"}){
                View action=dashboard.findViewWithTag(key);Rect rect=new Rect(0,0,action.getWidth(),action.getHeight());
                dashboard.offsetDescendantRectToMyCoords(action,rect);
                assertTrue(key+" clips left",rect.left>=0);assertTrue(key+" clips right",rect.right<=width);
                assertTrue(key+" needs a usable touch target",action.getHeight()>=Ui.dp(activity,48));
            }
            assertNull(FarmerService.instance);
        }
    }

    @Test public void editedBuildSurvivesActivityRecreation() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            controller.get().editProfile();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            View editor=dialog.getWindow().getDecorView();
            assertEquals(11, editableFields(editor).size());
            field(editor,"name").setText("Fast ceiling run");
            field(editor,"hull").setText("Crawler");
            field(editor,"weapons").setText("Gilded Ember + shields");
            field(editor,"notes").setText("Tested new equipment");
            field(editor,"hookshotCount").setText("4");
            field(editor,"extraJumps").setText("2");
            field(editor,"moveMs").setText("650");
            field(editor,"jumpSpacingMs").setText("600");
            field(editor,"settleMs").setText("150");
            field(editor,"maxRunSeconds").setText("300");
            ((Switch)editor.findViewWithTag("continuousFarm")).setChecked(false);
            field(editor,"maxSessionMinutes").setText("90");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(dialog.isShowing());
            controller.recreate();
            Profile restored = Profile.load(controller.get());
            assertEquals("Fast ceiling run", restored.name);
            assertEquals("Crawler", restored.hull);
            assertEquals("Gilded Ember + shields", restored.weapons);
            assertEquals("Tested new equipment", restored.notes);
            assertEquals(4, restored.hookshotCount);
            assertEquals(2, restored.extraJumps);
            assertEquals(11, restored.totalJumpBudget());
            assertEquals(650, restored.moveMs);
            assertEquals(600, restored.jumpSpacingMs);
            assertEquals(150, restored.settleMs);
            assertFalse(restored.continuousFarm);
            assertEquals(300, restored.maxRunSeconds);
            assertEquals(90, restored.maxSessionMinutes);
        }
    }

    @Test public void invalidTimingKeepsEditorOpenAndDoesNotOverwriteSavedProfile() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            int savedMoveMs = Profile.load(controller.get()).moveMs;
            controller.get().editProfile();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            field(dialog.getWindow().getDecorView(),"moveMs").setText("701");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(dialog.isShowing());
            assertEquals(savedMoveMs, Profile.load(controller.get()).moveMs);
            dialog.dismiss();
        }
    }

    @Test public void calibrationAndRewardPolicyPersistAndReachEngineConfig() {
        Context context = RuntimeEnvironment.getApplication();
        Profile profile = new Profile();
        profile.moveMs = 600; profile.jumpMs = 70; profile.jumpEveryMs = 2500;
        profile.settleMs = 150; profile.maxRunSeconds = 450; profile.maxSessionMinutes = 120;
        profile.hookshotCount=3;profile.extraJumps=1;profile.jumpSpacingMs=650;profile.continuousFarm=false;
        profile.watchFilterAds = false; profile.autoControls = false;
        profile.leftX = .1f; profile.leftY = .7f; profile.rightX = .3f; profile.rightY = .75f;
        profile.jumpX = .9f; profile.jumpY = .6f;
        profile.save(context);
        Profile restored = Profile.load(context);
        assertFalse(restored.watchFilterAds); assertFalse(restored.autoControls);
        assertEquals(.1f, restored.leftX, .0001f); assertEquals(.7f, restored.leftY, .0001f);
        assertEquals(.3f, restored.rightX, .0001f); assertEquals(.75f, restored.rightY, .0001f);
        assertEquals(.9f, restored.jumpX, .0001f); assertEquals(.6f, restored.jumpY, .0001f);
        FarmEngine.Config config = restored.config();
        assertEquals(600, config.moveMs); assertEquals(70, config.jumpTapMs);
        assertEquals(2500, config.jumpIntervalMs); assertEquals(150, config.settleMs);
        assertEquals(8,config.jumpBudget);assertEquals(650,config.jumpSpacingMs);assertFalse(config.continuousFarm);
        assertEquals(450, config.maxRunSeconds); assertEquals(120, config.maxSessionMinutes);
        assertFalse(config.watchFilterAds);
    }

    @Test public void versionOneDefaultsUpgradeWhileCalibrationAndNotesSurvive() {
        Context context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("profile",0).edit().putInt("moveMs",600).putInt("settleMs",700)
                .putString("notes","My custom equipment").putFloat("jumpX",.93f).putBoolean("watchFilterAds",false).commit();
        Profile upgraded=Profile.load(context);
        assertEquals(420,upgraded.moveMs);assertEquals(180,upgraded.settleMs);
        assertEquals(3,upgraded.hookshotCount);assertEquals(7,upgraded.totalJumpBudget());assertTrue(upgraded.continuousFarm);
        assertEquals("My custom equipment",upgraded.notes);assertEquals(.93f,upgraded.jumpX,.0001f);assertFalse(upgraded.watchFilterAds);
        assertEquals(4,context.getSharedPreferences("profile",0).getInt("schemaVersion",0));
    }

    @Test public void currentCustomTimingsSurviveAndUnsafeHoldIsCapped() {
        Context context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("profile",0).edit().putInt("moveMs",530).putInt("settleMs",140).commit();
        Profile upgraded=Profile.load(context);assertEquals(530,upgraded.moveMs);assertEquals(140,upgraded.settleMs);
        upgraded.moveMs=900;assertEquals(700,upgraded.config().moveMs);
        upgraded.save(context);assertEquals(700,Profile.load(context).moveMs);
    }

    @Test public void versionTwoJumpBatchesMigrateWithoutLosingBuildOrCalibration(){
        Context context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("profile",0).edit().putInt("schemaVersion",2).putInt("jumpSpacingMs",190)
                .putInt("moveMs",600).putString("weapons","My Ember").putFloat("leftX",.12f).commit();
        Profile p=Profile.load(context);assertEquals(350,p.jumpSpacingMs);assertEquals(600,p.moveMs);
        assertEquals("My Ember",p.weapons);assertEquals(.12f,p.leftX,.0001f);
        p.jumpSpacingMs=900;p.save(context);assertEquals(900,Profile.load(context).jumpSpacingMs);
    }

    private static EditText field(View editor,String key){
        EditText value=editor.findViewWithTag(key);assertNotNull("Missing editor field: "+key,value);return value;
    }

    private static String textOf(View view) {
        StringBuilder out = new StringBuilder();
        if (view instanceof TextView) out.append(((TextView) view).getText()).append('\n');
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) out.append(textOf(group.getChildAt(i)));
        }
        return out.toString();
    }

    private static List<EditText> editableFields(View view) {
        List<EditText> out = new ArrayList<>();
        if (view instanceof EditText) out.add((EditText) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) out.addAll(editableFields(group.getChildAt(i)));
        }
        return out;
    }
}
