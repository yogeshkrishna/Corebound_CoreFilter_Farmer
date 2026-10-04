package com.corefilter.farmer.maps;

import android.content.Context;
import com.corefilter.farmer.Profile;
import com.corefilter.farmer.engine.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class MapArchiveStoreTest {
    @Test public void manualFramesAreIncludedInSavedBundleAndRecoveredAfterInterruption()throws Exception{
        Context c=RuntimeEnvironment.getApplication();FarmEngine.Config config=new FarmEngine.Config();config.manualMapping=true;FarmEngine engine=new FarmEngine(config);
        FarmEngine.Frame frame=new FarmEngine.Frame(1000,"com.Overcurve.Corebound","",null);frame.gameplay=true;frame.cameraConfidence=frame.playerConfidence=.9;frame.cameraX=frame.cameraY=0;frame.playerX=.5;frame.playerY=.6;
        frame.terrainCols=48;frame.terrainRows=24;frame.terrainCells=new byte[48*24];Arrays.fill(frame.terrainCells,(byte)1);frame.viewportAspectRatio=2.17;
        engine.next(frame);byte[] image={ (byte)0xff,(byte)0xd8,1,2,(byte)0xff,(byte)0xd9 };
        ManualRecordingStore.record(c,engine.recordingId(),frame,engine.navigationSnapshot(),image);
        engine.stop();File saved=MapArchiveStore.save(c,engine.takeFinishedMap());
        try(ZipFile z=new ZipFile(saved)){
            JSONObject data=new JSONObject(new String(z.getInputStream(z.getEntry("map.json")).readAllBytes(),StandardCharsets.UTF_8));assertEquals("manual-mapping",data.getString("recordingMode"));
            assertTrue(data.getJSONObject("model").isNull("remainingJumps"));assertTrue(data.getJSONObject("model").isNull("learnedJumpRise"));
            assertArrayEquals(image,z.getInputStream(z.getEntry("manual-frames/frame-000000-1000.jpg")).readAllBytes());assertNotNull(z.getEntry("manual-frames/frames.jsonl"));
        }
        assertEquals(0,ManualRecordingStore.stagedCount(c));
        String interrupted=UUID.randomUUID().toString();ManualRecordingStore.record(c,interrupted,frame,engine.navigationSnapshot(),image);
        assertEquals(1,MapArchiveStore.recoverManual(c));assertEquals(0,MapArchiveStore.recoverManual(c));
        boolean recovered=false;for(MapArchiveStore.Bundle b:MapArchiveStore.list(c))try(ZipFile z=new ZipFile(b.file)){
            JSONObject data=new JSONObject(new String(z.getInputStream(z.getEntry("map.json")).readAllBytes(),StandardCharsets.UTF_8));if(data.getString("outcome").equals("manual-recovered")){recovered=true;assertFalse(data.getBoolean("complete"));assertNotNull(z.getEntry("manual-frames/frames.jsonl"));}
        }assertTrue(recovered);
    }
    private Context context;
    @Before public void prepare()throws Exception {context=RuntimeEnvironment.getApplication();for(MapArchiveStore.Bundle b:MapArchiveStore.list(context))assertTrue(b.file.delete());new Profile().save(context);}
    private MapNavigator.Snapshot snapshot(){
        MapNavigator navigator=new MapNavigator(new FarmEngine.Config());
        for(int i=0;i<3;i++){
            FarmEngine.Frame f=new FarmEngine.Frame(i*500,"com.Overcurve.Corebound","",null);f.gameplay=true;f.playerConfidence=.95;f.cameraConfidence=.95;f.cameraX=f.cameraY=0;f.viewportAspectRatio=2.17;
            f.playerX=i==1?.60:.50;f.playerY=.6;f.playerLeft=f.playerX-.025;f.playerRight=f.playerX+.025;f.playerTop=.56;f.playerBottom=.64;
            f.terrainCols=48;f.terrainRows=24;f.terrainCells=new byte[48*24];
            for(int y=4;y<21;y++)for(int x=1;x<47;x++)f.terrainCells[y*48+x]=1;
            for(int x=0;x<48;x++){f.terrainCells[3*48+x]=2;f.terrainCells[21*48+x]=2;}
            for(int y=3;y<=21;y++){f.terrainCells[y*48]=2;f.terrainCells[y*48+47]=2;}
            f.enemyBoxes=new double[][]{{.73,.5,.78,.57,0,0}};navigator.observe(f);
        }
        navigator.finish(true);return navigator.snapshot();
    }
    @Test public void zipHasRealRenderedGeometryRawUnitsCoverageAndBuildData()throws Exception {
        File file=MapArchiveStore.save(context,snapshot(),"completed",17);assertEquals(1,MapArchiveStore.count(context));
        try(ZipFile zip=new ZipFile(file)){
            JSONObject data=new JSONObject(new String(zip.getInputStream(zip.getEntry("map.json")).readAllBytes(),StandardCharsets.UTF_8));
            assertEquals(17,data.getLong("runNumber"));assertEquals(2.17,data.getJSONObject("units").getDouble("viewportAspectRatio"),.001);
            assertTrue(data.getJSONArray("cells").length()>700);assertTrue(data.getJSONArray("borders").length()>0);assertTrue(data.getJSONArray("path").length()>=3);
            assertEquals(3,data.getJSONObject("build").getInt("hookshotCount"));assertTrue(data.has("enemyHistory"));assertTrue(data.has("incompleteReasons"));assertTrue(data.getBoolean("runEnded"));assertTrue(data.getBoolean("runSucceeded"));
            Png image=decode(zip.getInputStream(zip.getEntry("map.png")));assertEquals(1536,image.width);assertTrue(image.height<=4096);
            int floors=0,ceilings=0,walls=0,path=0;
            for(int y=176;y<image.height;y++)for(int x=0;x<image.width;x++){int color=image.pixels[y*image.width+x];if(color==0x79D4E1)floors++;if(color==0xF2C56B)ceilings++;if(color==0xAE9BDC)walls++;if(color==0x91DDB3)path++;}
            assertTrue("Floor segments must be rendered",floors>100);assertTrue("Ceiling segments must be rendered",ceilings>100);assertTrue("Wall segments must be rendered",walls>50);assertTrue("Observed path must be rendered",path>10);
        }
        // Reading a bundle or constructing another context view does not consume it.
        assertEquals(file.getName(),MapArchiveStore.list(context.getApplicationContext()).get(0).file.getName());
    }
    @Test public void onlyMatchingReceiptDeletesAndIdentifiersCannotEscapeStorage()throws Exception {
        File file=MapArchiveStore.save(context,snapshot(),"failed",1);MapArchiveStore.Bundle b=MapArchiveStore.list(context).get(0);
        assertFalse(MapArchiveStore.acknowledge(context,b.id,"0".repeat(64)));assertTrue(file.exists());
        assertFalse(MapArchiveStore.acknowledge(context,b.id,"bad"));assertTrue(file.exists());
        try{MapArchiveStore.find(context,"../outside");fail("Traversal must fail");}catch(IOException expected){}
        assertTrue(MapArchiveStore.acknowledge(context,b.id,b.sha256));assertFalse(file.exists());assertEquals(0,MapArchiveStore.count(context));
    }
    @Test public void serverDownloadAndInterruptedConnectionRetainUntilAuthenticatedHashReceipt()throws Exception {
        File file=MapArchiveStore.save(context,snapshot(),"completed",1);MapArchiveStore.Bundle b=MapArchiveStore.list(context).get(0);
        try(MapTransferServer server=new MapTransferServer(context,InetAddress.getByName("127.0.0.1"))){
            String base=server.url().substring(0,server.url().length()-1),token=base.substring(base.lastIndexOf('/')+1);
            HttpURLConnection request=(HttpURLConnection)new URL(base+"/download/"+b.id).openConnection();assertEquals(200,request.getResponseCode());byte[] downloaded=request.getInputStream().readAllBytes();request.disconnect();
            assertEquals(b.bytes,downloaded.length);assertArrayEquals(java.nio.file.Files.readAllBytes(file.toPath()),downloaded);assertTrue(file.exists());
            URL url=new URL(base);try(Socket interrupted=new Socket(url.getHost(),url.getPort())){interrupted.getOutputStream().write(("GET "+url.getPath()+"/download/"+b.id+" HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes(StandardCharsets.US_ASCII));interrupted.getOutputStream().flush();assertTrue(interrupted.getInputStream().read()!=-1);}assertTrue(file.exists());
            JSONObject receipt=new JSONObject().put("id",b.id).put("sha256",b.sha256);
            assertEquals(403,post(base,receipt,"wrong"));assertTrue(file.exists());
            assertEquals(409,post(base,new JSONObject().put("id",b.id).put("sha256","f".repeat(64)),token));assertTrue(file.exists());
            assertEquals(200,post(base,receipt,token));assertFalse(file.exists());
        }
    }
    @Test public void serverRejectsMissingCapabilityTraversalAndCrossOriginReceipt()throws Exception {
        File file=MapArchiveStore.save(context,snapshot(),"failed",4);MapArchiveStore.Bundle b=MapArchiveStore.list(context).get(0);
        try(MapTransferServer server=new MapTransferServer(context,InetAddress.getByName("127.0.0.1"))){
            String base=server.url().substring(0,server.url().length()-1);URL address=new URL(base);
            HttpURLConnection noCapability=(HttpURLConnection)new URL("http://"+address.getHost()+":"+address.getPort()+"/api/maps").openConnection();assertEquals(403,noCapability.getResponseCode());noCapability.disconnect();
            HttpURLConnection traversal=(HttpURLConnection)new URL(base+"/download/%2e%2e%2fsecret").openConnection();assertEquals(403,traversal.getResponseCode());traversal.disconnect();assertTrue(file.exists());
            HttpURLConnection helper=(HttpURLConnection)new URL(base+"/receive-maps.ps1").openConnection();assertEquals(200,helper.getResponseCode());String script=new String(helper.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertTrue(script.contains(base+"/"));assertFalse(script.contains("'__CEILING_SCOUT_BASE_URL__'"+"\n"));helper.disconnect();
        }
        assertTrue(file.exists());
    }
    private int post(String base,JSONObject receipt,String token)throws Exception {HttpURLConnection connection=(HttpURLConnection)new URL(base+"/api/ack").openConnection();connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json");connection.setRequestProperty("Authorization","Bearer "+token);byte[] body=receipt.toString().getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(body.length);try(OutputStream out=connection.getOutputStream()){out.write(body);}int status=connection.getResponseCode();connection.disconnect();return status;}
    private static class Png {int width,height;int[] pixels;}
    private Png decode(InputStream input)throws Exception {
        DataInputStream in=new DataInputStream(input);assertEquals(0x89504e470d0a1a0aL,in.readLong());Png png=new Png();ByteArrayOutputStream compressed=new ByteArrayOutputStream();
        while(true){int n=in.readInt();assertTrue(n>=0&&n<12000000);byte[] kind=new byte[4];in.readFully(kind);byte[] data=new byte[n];in.readFully(data);CRC32 crc=new CRC32();crc.update(kind);crc.update(data);assertEquals((int)crc.getValue(),in.readInt());String name=new String(kind,StandardCharsets.US_ASCII);
            if(name.equals("IHDR")){DataInputStream header=new DataInputStream(new ByteArrayInputStream(data));png.width=header.readInt();png.height=header.readInt();assertEquals(8,header.readByte());assertEquals(2,header.readByte());}
            if(name.equals("IDAT"))compressed.write(data);if(name.equals("IEND"))break;
        }
        png.pixels=new int[png.width*png.height];DataInputStream raw=new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(compressed.toByteArray())));
        for(int y=0;y<png.height;y++){assertEquals(0,raw.readUnsignedByte());for(int x=0;x<png.width;x++)png.pixels[y*png.width+x]=(raw.readUnsignedByte()<<16)|(raw.readUnsignedByte()<<8)|raw.readUnsignedByte();}
        assertEquals(-1,raw.read());return png;
    }
}
