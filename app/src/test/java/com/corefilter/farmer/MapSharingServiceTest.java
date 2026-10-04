package com.corefilter.farmer;

import android.content.Intent;
import com.corefilter.farmer.maps.MapTransferServer;
import java.lang.reflect.Field;
import java.net.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.*;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

/** Real local HTTP server with a simulated Android dashboard/service lifecycle. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class MapSharingServiceTest {
    @Test public void closingAndRecreatingDashboardDoesNotStopSharing()throws Exception{
        ServiceController<MapSharingService> sharing=Robolectric.buildService(MapSharingService.class).create();
        MapSharingService service=sharing.get();
        try(MapTransferServer server=new MapTransferServer(service,InetAddress.getByName("127.0.0.1"))){
            Field field=MapSharingService.class.getDeclaredField("server");field.setAccessible(true);field.set(service,server);
            service.onStartCommand(new Intent(service,MapSharingService.class),0,1);String link=MapSharingService.link();
            try(ActivityController<MainActivity> dashboard=Robolectric.buildActivity(MainActivity.class).setup()){
                new MapExportUi(dashboard.get()).show();ShadowAlertDialog.getLatestAlertDialog().dismiss();
                dashboard.pause().stop();assertEquals(link,MapSharingService.link());dashboard.restart().start().resume().visible().recreate();
                assertEquals(link,MapSharingService.link());service.onStartCommand(new Intent(service,MapSharingService.class),0,2);
                assertEquals(link,MapSharingService.link());
                HttpURLConnection request=(HttpURLConnection)new URL(link+"api/maps").openConnection();
                assertEquals(200,request.getResponseCode());request.disconnect();
            }
        }finally{sharing.destroy();}
        assertNull(MapSharingService.link());
    }
}
