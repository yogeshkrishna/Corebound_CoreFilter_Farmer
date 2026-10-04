package com.corefilter.farmer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.net.*;
import android.view.WindowManager;
import android.widget.*;
import com.corefilter.farmer.maps.MapArchiveStore;
import com.corefilter.farmer.maps.MapTransferServer;
import java.io.IOException;
import java.net.*;

/** Sharing lives only while this activity/dialog is visible; the private queue survives it. */
final class MapExportUi {
    private final Activity activity;private MapTransferServer server;private TextView count,address;private Button toggle;private AlertDialog dialog;
    MapExportUi(Activity activity){this.activity=activity;}
    void show(){
        if(dialog!=null&&dialog.isShowing())return;
        LinearLayout body=Ui.column(activity);int p=Ui.dp(activity,20);body.setPadding(p,0,p,p);body.setBackgroundColor(Ui.BG);
        count=Ui.text(activity,"",15,Ui.INK);body.addView(count);
        body.addView(Ui.text(activity,"Cleared, failed and paused runs stay in private phone storage. A ZIP includes the map picture, raw observations and your build settings.",13,Ui.MUTED));
        body.addView(Ui.text(activity,"Connect your laptop to this phone's Wi-Fi network. Keep this page open during transfer.",13,Ui.MUTED));
        address=Ui.text(activity,"Sharing is stopped.",13,Ui.MINT);address.setTextIsSelectable(true);body.addView(address);
        toggle=Ui.button(activity,"Start Wi-Fi transfer",()->{if(server==null)start();else stop();});toggle.setTag("map-sharing-toggle");body.addView(toggle);
        body.addView(Ui.secondaryButton(activity,"Copy laptop link",()->{if(server==null){Toast.makeText(activity,"Start Wi-Fi transfer first",Toast.LENGTH_SHORT).show();return;}((android.content.ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Ceiling Scout maps",server.url()));Toast.makeText(activity,"Link copied",Toast.LENGTH_SHORT).show();}));
        body.addView(Ui.secondaryButton(activity,"Refresh stored count",this::refresh));
        body.addView(Ui.text(activity,"Open the link on your laptop and download the receiver. It verifies each saved ZIP before removing that exact phone copy. Interrupted or unverified transfers keep the phone files.",12,Ui.MUTED));
        body.addView(Ui.text(activity,"Sharing stays on this Wi-Fi network. Anyone with the current link can access the queue while sharing is open.",12,Ui.MUTED));
        ScrollView scroll=new ScrollView(activity);scroll.addView(body);dialog=new AlertDialog.Builder(activity).setTitle("Saved level maps").setView(scroll).setNegativeButton("Close",null).create();
        dialog.setOnDismissListener(d->stop());dialog.show();dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Ui.MINT);refresh();
    }
    private void refresh(){if(count!=null){int n=MapArchiveStore.count(activity);count.setText(n+" map bundle"+(n==1?"":"s")+" stored on this phone");}}
    private void start(){
        try{InetAddress ip=wifiAddress(activity);if(ip==null)throw new IOException("Connect this phone to Wi-Fi first. A private IPv4 address is needed.");server=new MapTransferServer(activity,ip);
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);address.setText(server.url());toggle.setText("Stop Wi-Fi transfer");refresh();
        }catch(IOException|SecurityException e){Toast.makeText(activity,e.getMessage()==null?"Could not start local sharing":e.getMessage(),Toast.LENGTH_LONG).show();}
    }
    void stop(){if(server!=null){server.close();server=null;}activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);if(address!=null)address.setText("Sharing stopped. Unacknowledged maps stay on this phone.");if(toggle!=null)toggle.setText("Start Wi-Fi transfer");refresh();}
    private static InetAddress wifiAddress(Context context){
        ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);if(cm==null)return null;
        Network active=cm.getActiveNetwork();InetAddress ip=address(cm,active);if(ip!=null)return ip;
        for(Network network:cm.getAllNetworks()){ip=address(cm,network);if(ip!=null)return ip;}return null;
    }
    private static InetAddress address(ConnectivityManager cm,Network network){
        if(network==null)return null;NetworkCapabilities caps=cm.getNetworkCapabilities(network);LinkProperties properties=cm.getLinkProperties(network);
        if(caps==null||!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||properties==null)return null;
        for(LinkAddress link:properties.getLinkAddresses()){InetAddress ip=link.getAddress();if(ip instanceof Inet4Address&&ip.isSiteLocalAddress())return ip;}return null;
    }
}
