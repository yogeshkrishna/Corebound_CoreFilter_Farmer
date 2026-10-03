package com.corefilter.farmer;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class UpdateInstallReceiverTest {
    private Context context;
    private SharedPreferences prefs;
    private File apk;
    @Before public void prepare()throws Exception{
        context=RuntimeEnvironment.getApplication();prefs=context.getSharedPreferences(UpdateManager.PREFS,0);
        prefs.edit().clear().putString("installNonce","expected").putInt("installSession",42).putString("preparedHash","hash").putLong("preparedAt",1000).commit();
        apk=new File(new File(context.getFilesDir(),"updates"),"update.apk");assertTrue(apk.getParentFile().isDirectory()||apk.getParentFile().mkdirs());Files.write(apk.toPath(),"abc".getBytes(StandardCharsets.UTF_8));
    }
    private Intent result(String nonce,int session){return new Intent(UpdateManager.CALLBACK).putExtra("nonce",nonce).putExtra(PackageInstaller.EXTRA_SESSION_ID,session).putExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE_ABORTED);}
    @Test public void ignoresWrongNonceAndSession(){UpdateInstallReceiver receiver=new UpdateInstallReceiver();receiver.onReceive(context,result("forged",42));receiver.onReceive(context,result("expected",999));assertEquals("expected",prefs.getString("installNonce",""));assertTrue(apk.isFile());}
    @Test public void cleansOnlyItsOwnPreparedApkOnCanceledInstaller(){File unrelated=new File(context.getFilesDir(),"keep.txt");try{Files.write(unrelated.toPath(),new byte[]{1});}catch(Exception ex){throw new AssertionError(ex);}new UpdateInstallReceiver().onReceive(context,result("expected",42));assertFalse(apk.exists());assertTrue(unrelated.exists());assertFalse(prefs.contains("installNonce"));assertFalse(prefs.contains("preparedHash"));}
    @Test public void actualFileDigestIsSha256()throws Exception{assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",UpdateManager.sha256(apk));}
}
