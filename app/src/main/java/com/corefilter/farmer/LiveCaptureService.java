package com.corefilter.farmer;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.*;
import android.hardware.display.*;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.*;
import android.os.*;
import android.view.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.*;
import org.json.*;

/** Bounded native capture, saved locally by default; laptop streaming remains optional. */
public final class LiveCaptureService extends Service {
    public static volatile LiveCaptureService instance;
    private static final int NOTICE=42;
    private final Handler main=new Handler(Looper.getMainLooper());
    private HandlerThread captureThread;private Handler capture;
    private final ExecutorService sender=Executors.newSingleThreadExecutor();
    private MediaProjection projection;private VirtualDisplay display;private ImageReader reader;
    private LiveEndpoint endpoint;private final String session=UUID.randomUUID().toString();
    private File recording;private boolean offline;private String finalMessage="Recording saved. Ready to build.";
    private static volatile boolean finishing;
    public static boolean finishing(){return finishing;}
    private volatile boolean closed,busy;private volatile String status="Connecting to laptop";
    private volatile long sent,skipped,sequence;private long lastSample;
    private int width,height,dpi;
    private volatile Rect captureMask=new Rect();
    private JSONObject build;
    public static boolean active(){return instance!=null&&!instance.closed;}
    public static String statusLine(){LiveCaptureService s=instance;return s==null?"Live capture stopped":s.status;}
    public static void stop(Context c){c.stopService(new Intent(c,LiveCaptureService.class));}
    @Override public IBinder onBind(Intent i){return null;}
    @Override public void onCreate(){super.onCreate();instance=this;captureThread=new HandlerThread("scout-native-capture");captureThread.start();capture=new Handler(captureThread.getLooper());}
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent==null||"stop".equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}if(projection!=null)return START_NOT_STICKY;
        try{
            offline=intent.getBooleanExtra("offline",false);
            if(offline){if(OfflineMapService.active())throw new IllegalStateException("Pause map processing before recording");recording=RecordingStore.create(this);}
            else endpoint=LiveEndpoint.parse(getSharedPreferences("live",0).getString("endpoint",""));
            Profile profile=Profile.load(this);build=new JSONObject().put("profile",profile.name).put("hull",profile.hull).put("hookshots",profile.hookshotCount).put("jumpBudget",profile.totalJumpBudget()).put("weapons",profile.weapons.substring(0,Math.min(512,profile.weapons.length())));
            NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("live-capture","Game image recording",NotificationManager.IMPORTANCE_LOW));
            PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,LiveCaptureService.class).setAction("stop"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            Notification n=new Notification.Builder(this,"live-capture").setSmallIcon(R.drawable.ic_scout).setContentTitle(offline?"Recording map on this phone":"Live map → laptop").setContentText("Original game images. Tap to view; Stop ends capture.").setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"Stop",stop).build()).build();
            startForeground(NOTICE,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            Intent consent=intent.getParcelableExtra("consent");int result=intent.getIntExtra("result",Activity.RESULT_CANCELED);
            if(consent==null||result!=Activity.RESULT_OK)throw new IllegalArgumentException("Screen-sharing permission is required");
            projection=getSystemService(MediaProjectionManager.class).getMediaProjection(result,consent);
            if(FarmerService.instance!=null)captureMask=FarmerService.instance.overlayBounds();
            projection.registerCallback(new MediaProjection.Callback(){
                @Override public void onStop(){main.post(()->{setStatus("Screen sharing ended");stopSelf();});}
                @Override public void onCapturedContentResize(int w,int h){capture.post(()->resize(w,h));}
            },capture);
            Rect b=getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();dpi=getResources().getDisplayMetrics().densityDpi;
            capture.post(()->resize(b.width(),b.height()));
        }catch(Exception e){setStatus("Capture stopped: "+e.getMessage());stopSelf();}
        return START_NOT_STICKY;
    }
    @Override public void onConfigurationChanged(Configuration configuration){super.onConfigurationChanged(configuration);if(Build.VERSION.SDK_INT<34){Rect b=getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();capture.post(()->resize(b.width(),b.height()));}}
    private void resize(int w,int h){
        if(closed||w<1||h<1||projection==null||w==width&&h==height&&reader!=null)return;
        try{
            ImageReader next=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,3);next.setOnImageAvailableListener(this::frame,capture);
            if(display==null)display=projection.createVirtualDisplay("Ceiling Scout native stream",w,h,dpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,next.getSurface(),null,capture);
            else{display.resize(w,h,dpi);display.setSurface(next.getSurface());}
            ImageReader old=reader;reader=next;width=w;height=h;if(old!=null)old.close();
        }catch(Exception e){main.post(()->{setStatus("Capture resize failed: "+e.getMessage());stopSelf();});}
    }
    private void frame(ImageReader source){
        Bitmap owned=null;
        boolean claimed=false;
        try(Image image=source.acquireLatestImage()){
            if(image==null||closed||source!=reader)return;
            long now=SystemClock.elapsedRealtime();FarmerService farmer=FarmerService.instance;
            if(now-lastSample<125)return;lastSample=now;
            if(farmer==null||!FarmerService.GAME.equals(farmer.foregroundPackage())){setStatus("Waiting for Corebound");return;}
            if(image.getWidth()<=image.getHeight()){setStatus("Waiting for landscape");return;}
            if(busy){skipped++;return;}busy=true;claimed=true;
            Image.Plane p=image.getPlanes()[0];int stride=p.getRowStride(),pixel=p.getPixelStride();
            Bitmap nativeImage=Bitmap.createBitmap(image.getWidth(),image.getHeight(),Bitmap.Config.ARGB_8888);owned=nativeImage;
            nativeImage.setPixels(NativeCapturePixels.decode(p.getBuffer(),image.getWidth(),image.getHeight(),stride,pixel),0,image.getWidth(),0,0,image.getWidth(),image.getHeight());
            main.post(()->{if(!closed&&FarmerService.instance==farmer)captureMask=farmer.overlayBounds();});
            Rect mask=new Rect(captureMask);if(!mask.intersect(0,0,nativeImage.getWidth(),nativeImage.getHeight()))mask.setEmpty();
            if(!mask.isEmpty()){if(!nativeImage.isMutable()){Bitmap mutable=nativeImage.copy(Bitmap.Config.ARGB_8888,true);nativeImage.recycle();nativeImage=mutable;}Paint paint=new Paint();paint.setColor(Color.BLACK);new Canvas(nativeImage).drawRect(mask,paint);}
            JSONObject metadata=new JSONObject().put("schema",1).put("session",session).put("sequence",++sequence).put("captureElapsedMs",now).put("captureWallMs",System.currentTimeMillis()).put("imageTimestampNs",image.getTimestamp()).put("width",nativeImage.getWidth()).put("height",nativeImage.getHeight()).put("rotation",farmer.displayRotation()).put("package",FarmerService.GAME).put("device",Build.MANUFACTURER+" "+Build.MODEL).put("android",Build.VERSION.SDK_INT).put("appVersion",getPackageManager().getPackageInfo(getPackageName(),0).versionName).put("format","png").put("capture","mediaProjection").put("resized",false).put("skippedBeforeUpload",skipped).put("farmerStatus",farmer.statusLine());
            JSONArray masks=new JSONArray();if(!mask.isEmpty())masks.put(new JSONArray(new int[]{mask.left,mask.top,mask.right,mask.bottom}));metadata.put("occlusions",masks);
            metadata.put("build",build);
            Bitmap sendImage=nativeImage;
            sender.execute(()->send(sendImage,metadata));
            owned=null;
        }catch(Exception e){if(owned!=null&&!owned.isRecycled())owned.recycle();if(claimed)busy=false;setStatus("Capture error: "+e.getMessage());}
    }
    private void send(Bitmap image,JSONObject metadata){
        HttpURLConnection connection=null;
        try{
            if(offline){RecordingStore.saveFrame(recording,metadata.getLong("sequence"),image,metadata);sent++;setStatus(sent+" images saved · "+skipped+" skipped");return;}
            if(closed||FarmerService.instance==null||!FarmerService.GAME.equals(FarmerService.instance.foregroundPackage()))return;
            ByteArrayOutputStream png=new ByteArrayOutputStream();if(!image.compress(Bitmap.CompressFormat.PNG,100,png))throw new IOException("Could not encode native image");
            metadata.put("uploadStartedWallMs",System.currentTimeMillis());
            byte[] header=metadata.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);byte[] pixels=png.toByteArray();
            connection=(HttpURLConnection)new URL(endpoint.base+"/api/frame").openConnection();connection.setRequestMethod("POST");connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(1500);connection.setReadTimeout(3000);connection.setDoOutput(true);connection.setRequestProperty("Authorization","Bearer "+endpoint.token);connection.setRequestProperty("Content-Type","application/x-ceiling-scout-frame");connection.setFixedLengthStreamingMode(4+header.length+pixels.length);
            try(DataOutputStream out=new DataOutputStream(connection.getOutputStream())){out.writeInt(header.length);out.write(header);out.write(pixels);}
            int code=connection.getResponseCode();if(code!=200&&code!=201)throw new IOException(code==401?"Reconnect using the laptop's current link":"Laptop response "+code);
            try(InputStream response=connection.getInputStream()){byte[] reply=new byte[4096];int used=0,n;while(used<reply.length&&(n=response.read(reply,used,reply.length-used))!=-1)used+=n;JSONObject ack=new JSONObject(new String(reply,0,used,java.nio.charset.StandardCharsets.UTF_8));if(!ack.optBoolean("stored",false))throw new IOException("Laptop did not acknowledge storage");}
            sent++;setStatus(sent+" frames · "+image.getWidth()+"×"+image.getHeight()+" · "+skipped+" skipped");
        }catch(Exception e){if(offline){finalMessage=e.getMessage();setStatus(finalMessage);main.post(this::stopSelf);}else if(!closed)setStatus("Laptop offline · retrying ("+e.getMessage()+")");}
        finally{if(connection!=null)connection.disconnect();image.recycle();busy=false;}
    }
    private void setStatus(String value){if(value.equals(status))return;status=value;main.post(()->{if(!closed&&FarmerService.instance!=null)FarmerService.instance.liveStatus(value);});}
    @Override public void onDestroy(){closed=true;instance=null;finishing=recording!=null;
        // Finish the accepted frame before committing the stopped state. No shutdownNow:
        // interrupting its PNG write could silently lose the last part of a run.
        capture.post(()->{if(display!=null)display.release();if(reader!=null)reader.close();if(projection!=null)projection.stop();
            sender.execute(()->{try{if(recording!=null)RecordingStore.finish(recording,finalMessage);}catch(Exception ignored){}finally{finishing=false;}});sender.shutdown();captureThread.quitSafely();});
        stopForeground(STOP_FOREGROUND_REMOVE);if(FarmerService.instance!=null)FarmerService.instance.liveStatus(offline?"Recording saved · open Maps":"Live capture stopped");super.onDestroy();}
}
