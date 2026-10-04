package com.corefilter.farmer;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import com.corefilter.farmer.maps.MapTransferServer;
import java.net.*;

/** User-started Wi-Fi sharing survives closing the dashboard and entering the game. */
public final class MapSharingService extends Service {
    private static final String STOP="stop";private static final int NOTIFICATION=41;
    private static volatile MapSharingService instance;
    private MapTransferServer server;
    public static String link(){MapSharingService s=instance;return s==null||s.server==null?null:s.server.url();}
    public static void start(Context c){c.startForegroundService(new Intent(c,MapSharingService.class));}
    public static void stop(Context c){c.startService(new Intent(c,MapSharingService.class).setAction(STOP));}
    @Override public android.os.IBinder onBind(Intent i){return null;}
    @Override public void onCreate(){super.onCreate();instance=this;}
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent!=null&&STOP.equals(intent.getAction())){getSharedPreferences("sharing",0).edit().putBoolean("enabled",false).apply();stopSelf();return START_NOT_STICKY;}
        if(intent==null&&!getSharedPreferences("sharing",0).getBoolean("enabled",false)){stopSelf();return START_NOT_STICKY;}
        NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("map-sharing","Wi-Fi map transfer",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class).putExtra("openMaps",true),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,0,new Intent(this,MapSharingService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,"map-sharing").setSmallIcon(R.drawable.ic_scout).setContentTitle("Map sharing is active").setContentText("Use your laptop link. Tap to view it; Stop ends sharing.").setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"Stop",stop).build()).build();
        if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);else startForeground(NOTIFICATION,notification);
        if(server!=null)return START_STICKY;
        try{
            InetAddress ip=MapExportUi.wifiAddress(this);if(ip==null)throw new java.io.IOException("Connect to Wi-Fi first");
            SharedPreferences prefs=getSharedPreferences("sharing",0);String capability=prefs.getString("token",null);int port=prefs.getInt("port",0);
            try{server=new MapTransferServer(this,ip,port,capability);}catch(java.io.IOException bind){if(port==0)throw bind;server=new MapTransferServer(this,ip,0,capability);}
            URI uri=URI.create(server.url());prefs.edit().putString("token",uri.getPath().split("/")[1]).putInt("port",uri.getPort()).putBoolean("enabled",true).remove("error").apply();
        }catch(Exception e){getSharedPreferences("sharing",0).edit().putString("error",e.getMessage()==null?"Could not start Wi-Fi sharing":e.getMessage()).putBoolean("enabled",false).apply();stopSelf();return START_NOT_STICKY;}
        return START_STICKY;
    }
    @Override public void onDestroy(){if(server!=null){server.close();server=null;}instance=null;stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
}
