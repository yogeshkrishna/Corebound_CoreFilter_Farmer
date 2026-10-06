package com.corefilter.farmer;

import android.app.*;
import android.os.Looper;
import android.os.PowerManager;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowAlertDialog;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class UpdateProgressTest {
    private Class<?> operation;
    private Object op;
    private ActivityController<Activity> first,second;
    private AtomicBoolean busy()throws Exception{Field f=UpdateManager.class.getDeclaredField("BUSY");f.setAccessible(true);return (AtomicBoolean)f.get(null);}
    private Object call(String name,Class<?>[] types,Object...args)throws Exception{Method m=operation.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(op,args);}
    @Before public void start()throws Exception{
        operation=Class.forName("com.corefilter.farmer.UpdateManager$Operation");first=Robolectric.buildActivity(Activity.class).setup();
        Constructor<?> constructor=operation.getDeclaredConstructor(Activity.class,String.class);constructor.setAccessible(true);
        busy().set(true);op=constructor.newInstance(first.get(),"Downloading · 75%");
    }
    @After public void cleanup()throws Exception{
        call("cancel",new Class<?>[0]);call("finish",new Class<?>[0]);shadowOf(Looper.getMainLooper()).idle();
        if(second!=null)second.pause().stop().destroy();if(!first.get().isDestroyed())first.pause().stop().destroy();
        Field pending=UpdateManager.class.getDeclaredField("pending");pending.setAccessible(true);pending.set(null,null);
    }
    @Test public void updateButtonReopensProgressAfterScreenSwitch()throws Exception{
        AlertDialog old=ShadowAlertDialog.getLatestAlertDialog();assertTrue(old.isShowing());
        UpdateManager.onPause(first.get());assertFalse(old.isShowing());first.pause().stop().destroy();
        call("progress",new Class<?>[]{String.class},"Downloading · 81%\n38.7 / 45.6 MB");shadowOf(Looper.getMainLooper()).idle();
        second=Robolectric.buildActivity(Activity.class).setup();UpdateManager.show(second.get());
        AlertDialog restored=ShadowAlertDialog.getLatestAlertDialog();assertNotSame(old,restored);assertTrue(restored.isShowing());
        assertTrue(restored.findViewById(android.R.id.button2)!=null);
        assertTrue(busy().get());call("finish",new Class<?>[0]);shadowOf(Looper.getMainLooper()).idle();assertFalse(busy().get());assertFalse(restored.isShowing());
    }
    @Test public void canceledOperationReleasesInProgressState()throws Exception{
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        try{call("check",new Class<?>[0]);fail();}catch(InvocationTargetException ex){assertTrue(ex.getCause() instanceof java.io.InterruptedIOException);}
        call("finish",new Class<?>[0]);shadowOf(Looper.getMainLooper()).idle();assertFalse(busy().get());
    }
    @Test public void backgroundCompletionWaitsForVisibleActivity()throws Exception{
        AtomicBoolean delivered=new AtomicBoolean();Class<?> action=Class.forName("com.corefilter.farmer.UpdateManager$UiAction");
        Object result=Proxy.newProxyInstance(action.getClassLoader(),new Class<?>[]{action},(proxy,method,args)->{delivered.set(true);return null;});
        call("ui",new Class<?>[]{action},result);UpdateManager.onPause(first.get());call("finish",new Class<?>[0]);shadowOf(Looper.getMainLooper()).idle();
        assertFalse(delivered.get());assertFalse(busy().get());UpdateManager.onResume(first.get());assertTrue(delivered.get());
    }
    @Test public void backgroundServiceHoldsAndReleasesWakeLock()throws Exception{
        Field download=operation.getDeclaredField("downloading");download.setAccessible(true);download.setBoolean(op,true);
        var controller=Robolectric.buildService(UpdateDownloadService.class).create();UpdateDownloadService service=controller.get();
        service.onStartCommand(new android.content.Intent(),0,1);
        Field lock=UpdateDownloadService.class.getDeclaredField("wake");lock.setAccessible(true);PowerManager.WakeLock wake=(PowerManager.WakeLock)lock.get(service);
        assertNotNull(wake);assertTrue(wake.isHeld());
        service.onTimeout(1,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        try{call("check",new Class<?>[0]);fail();}catch(InvocationTargetException ex){assertTrue(ex.getCause().getMessage().contains("Android paused"));}
        controller.destroy();assertFalse(wake.isHeld());
    }
}
