package com.corefilter.farmer;

import android.content.*;
import android.content.pm.PackageInstaller;
import android.widget.Toast;
import java.io.File;

/** Receives only the explicitly addressed PackageInstaller result for this update. */
public final class UpdateInstallReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if(!UpdateManager.CALLBACK.equals(intent.getAction()))return;
        SharedPreferences prefs=context.getSharedPreferences(UpdateManager.PREFS,Context.MODE_PRIVATE);
        String expected=prefs.getString("installNonce","");
        if(expected.isEmpty()||!expected.equals(intent.getStringExtra("nonce"))||prefs.getInt("installSession",-1)!=intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID,-2))return;
        int status=intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE);
        if(status==PackageInstaller.STATUS_PENDING_USER_ACTION){
            Intent confirm=intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if(confirm!=null){confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);try{context.startActivity(confirm);}catch(Exception ex){Toast.makeText(context,"Return to Ceiling Scout to retry the update installer.",Toast.LENGTH_LONG).show();}}
            return;
        }
        prefs.edit().remove("installNonce").remove("installSession").remove("preparedHash").remove("preparedAt").apply();
        File downloaded=new File(new File(context.getFilesDir(),"updates"),"update.apk");if(downloaded.exists())downloaded.delete();
        Toast.makeText(context,status==PackageInstaller.STATUS_SUCCESS?"Ceiling Scout updated. Your settings were kept.":status==PackageInstaller.STATUS_FAILURE_ABORTED?"Update canceled. Your current app is unchanged.":"Android could not install the update. Check for updates to retry.",Toast.LENGTH_LONG).show();
    }
}
