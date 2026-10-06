package com.corefilter.farmer;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.*;

/** Keeps a user-started update alive when the activity rotates or goes to the background. */
public final class UpdateDownloadService extends Service {
    private static final int NOTICE=46;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wake;
    private final Runnable refresh=new Runnable(){
        @Override public void run(){String status=UpdateManager.downloadStatus();if(status==null){stopSelf();return;}
            if(Build.VERSION.SDK_INT<33||checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)
                getSystemService(NotificationManager.class).notify(NOTICE,notification(status));
            handler.postDelayed(this,1000);
        }
    };
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&"cancel".equals(intent.getAction())){UpdateManager.cancelDownload();return START_NOT_STICKY;}
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("app-updates","App updates",NotificationManager.IMPORTANCE_LOW));
        try{
            startForeground(NOTICE,notification("Preparing download"),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            if(wake==null){wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"CeilingScout:Update");wake.acquire(60L*60*1000);}
            handler.removeCallbacks(refresh);handler.post(refresh);
        }catch(RuntimeException ex){UpdateManager.failDownload("Android could not keep the download running. Progress is saved; reopen Ceiling Scout and retry.");stopSelf();}
        return START_NOT_STICKY;
    }
    private Notification notification(String text){
        PendingIntent open=PendingIntent.getActivity(this,4,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent cancel=PendingIntent.getService(this,5,new Intent(this,UpdateDownloadService.class).setAction("cancel"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"app-updates").setSmallIcon(R.drawable.ic_scout).setContentTitle("Ceiling Scout update").setContentText(text.replace('\n',' '))
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(new Notification.Action.Builder(null,"Cancel",cancel).build()).build();
    }
    @Override public void onTimeout(int startId,int type){UpdateManager.failDownload("Android paused this background download. Progress is saved; reopen Ceiling Scout and retry.");stopSelf();}
    @Override public void onDestroy(){handler.removeCallbacks(refresh);if(wake!=null&&wake.isHeld())wake.release();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
}
