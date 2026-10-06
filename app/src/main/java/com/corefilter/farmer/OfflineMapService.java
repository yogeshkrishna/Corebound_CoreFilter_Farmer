package com.corefilter.farmer;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.*;
import com.corefilter.farmer.mapping.NativeAtlas;
import org.json.*;
import org.opencv.android.OpenCVLoader;
import java.io.*;
import java.util.concurrent.*;

/** User-started, visible processing. Every pause preserves original images and checkpoints. */
public final class OfflineMapService extends Service {
    private static final int NOTICE=43;public static volatile OfflineMapService instance;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();private final Handler main=new Handler(Looper.getMainLooper());
    private volatile boolean cancelled;private volatile String status="Starting map processing";private volatile String recordingId="";private long lastNotice;
    private PowerManager.WakeLock wake;
    public static boolean active(){return instance!=null;}
    public static String statusLine(){return instance==null?"":instance.status;}
    public static void pause(Context c){OfflineMapService s=instance;if(s!=null)s.cancelled=true;}
    @Override public IBinder onBind(Intent i){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null){stopSelf();return START_NOT_STICKY;}if("pause".equals(intent.getAction())){cancelled=true;return START_NOT_STICKY;}if(instance!=null)return START_NOT_STICKY;
        instance=this;recordingId=intent.getStringExtra("recording");
        try{NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("offline-map","Offline map processing",NotificationManager.IMPORTANCE_LOW));
            startForeground(NOTICE,notification("Preparing saved images"),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"CeilingScout:OfflineMap");wake.acquire(6L*60*60*1000);
        }catch(RuntimeException e){status="Android could not start background processing. Saved images are safe. Retry from this screen later. "+e.getMessage();
            try{RecordingStore.update(RecordingStore.directory(this,recordingId),"export".equals(intent.getAction())?"complete":"paused",status);}catch(Exception ignored){}stopSelf();return START_NOT_STICKY;}
        worker.execute(()->run(intent));return START_NOT_STICKY;
    }
    private Notification notification(String message){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,OfflineMapsActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pause=PendingIntent.getService(this,2,new Intent(this,OfflineMapService.class).setAction("pause"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"offline-map").setSmallIcon(R.drawable.ic_scout).setContentTitle("Ceiling Scout · offline map").setContentText(message).setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"Pause",pause).build()).build();
    }
    private void progress(File f,String stage,int done,int total)throws Exception{
        if(cancelled)throw new InterruptedIOException("Paused. Tap Build / Resume to continue.");
        status=stage+" · "+done+" / "+total;long now=SystemClock.elapsedRealtime();if(now-lastNotice>800||done==total){lastNotice=now;
            RecordingStore.update(f,"processing",status);notifyAllowed(NOTICE,notification(status));}
    }
    private void run(Intent intent){File f=null;boolean exporting="export".equals(intent.getAction());
        try{f=RecordingStore.directory(this,recordingId);if(LiveCaptureService.active())throw new IOException("Stop recording before building or exporting");
            if(exporting){JSONObject map=RecordingStore.read(new File(f,"map.json"));int section=intent.getIntExtra("section",0);JSONObject group=map.getJSONArray("sections").getJSONObject(section);JSONArray b=group.getJSONArray("bounds");
                try(NativeAtlas atlas=new NativeAtlas(new File(f,"map-v1/section-"+section));OutputStream out=getContentResolver().openOutputStream(Uri.parse(intent.getStringExtra("destination")),"wt")){
                    if(out==null)throw new IOException("Cannot open export destination");atlas.bounds=new int[]{b.getInt(0),b.getInt(1),b.getInt(2),b.getInt(3)};final File saved=f;
                    atlas.export(out,()->cancelled,value->{try{progress(saved,"Exporting original-resolution PNG",value,100);}catch(Exception e){cancelled=true;}});}
                RecordingStore.update(f,"complete","Native PNG exported. The original recording is still saved.");
            }else{
                if(!OpenCVLoader.initLocal())throw new IOException("Image processing library could not start");
                final File saved=f;try(OfflineReconstructor mapper=new OfflineReconstructor(f,()->cancelled,(stage,done,total)->progress(saved,stage,done,total))){JSONObject map=mapper.build();int count=map.getJSONArray("sections").length();RecordingStore.update(f,"complete",count+" map section"+(count==1?"":"s")+" ready · native pixels. Uncertain joins stay separate.");}
            }
            PendingIntent open=PendingIntent.getActivity(this,3,new Intent(this,OfflineMapsActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            notifyAllowed(44,new Notification.Builder(this,"offline-map").setSmallIcon(R.drawable.ic_scout).setContentTitle(exporting?"Map PNG saved":"Offline map ready").setContentText("Tap to open your recorded maps.").setContentIntent(open).setAutoCancel(true).build());
        }catch(Exception|OutOfMemoryError e){status=e instanceof OutOfMemoryError?"Phone memory was exhausted. Saved images are safe; resume after closing other apps.":e.getMessage();
            if(exporting){try{android.provider.DocumentsContract.deleteDocument(getContentResolver(),Uri.parse(intent.getStringExtra("destination")));}catch(Exception ignored){}status="Export did not complete. Choose Save native PNG again. "+status;}
            if(f!=null)try{RecordingStore.update(f,exporting?"complete":cancelled?"paused":"error",status);}catch(Exception ignored){}
        }finally{if(wake!=null&&wake.isHeld())wake.release();main.post(this::stopSelf);}
    }
    private void notifyAllowed(int id,Notification notification){if(Build.VERSION.SDK_INT<33||checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED)getSystemService(NotificationManager.class).notify(id,notification);}
    @Override public void onTimeout(int startId,int fgsType){cancelled=true;stopSelf();}
    @Override public void onDestroy(){cancelled=true;if(instance==this)instance=null;if(wake!=null&&wake.isHeld())wake.release();worker.shutdown();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
}
