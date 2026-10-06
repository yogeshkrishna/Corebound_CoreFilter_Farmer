package com.corefilter.farmer;

import android.content.Context;
import android.graphics.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.corefilter.farmer.mapping.NativeAtlas;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;
import java.lang.reflect.*;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** Runs the actual packaged OpenCV and Android PNG/bitmap implementation. */
@RunWith(AndroidJUnit4.class)
public class OfflineNativeTest {
    @Test public void stripMaskMatchesFullFrameHsvExactly()throws Exception{
        assertTrue(OpenCVLoader.initLocal());Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();File recording=RecordingStore.create(c);
        int w=321,h=153;Bitmap image=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);int[] colors=new int[w*h];Random random=new Random(7319);
        for(int i=0;i<colors.length;i++){int value=random.nextInt(256);colors[i]=0xff000000|value<<16|value<<8|value;}
        // Colored patches straddle strip boundaries. Most pixels remain gray so
        // the dilation and explicit occlusion masks have observable edges.
        for(int y:new int[]{12,62,63,64,125,127,128,149})for(int x:new int[]{1,75,180,318})colors[y*w+x]=0xff000000|random.nextInt(0x1000000);
        image.setPixels(colors,0,w,0,0,w,h);JSONObject meta=new JSONObject().put("occlusions",new JSONArray().put(new JSONArray().put(-4).put(60).put(29).put(77)).put(new JSONArray().put(290).put(127).put(350).put(180)));
        Method fullMethod=OfflineReconstructor.class.getDeclaredMethod("mask",Mat.class,JSONObject.class),stripMethod=OfflineReconstructor.class.getDeclaredMethod("mask",Bitmap.class,JSONObject.class);fullMethod.setAccessible(true);stripMethod.setAccessible(true);
        Mat bgr=new Mat(),full=null,strip=null;try(OfflineReconstructor mapper=new OfflineReconstructor(recording,()->false,(stage,done,total)->{})){
            Utils.bitmapToMat(image,bgr);Imgproc.cvtColor(bgr,bgr,Imgproc.COLOR_RGBA2BGR);full=(Mat)fullMethod.invoke(mapper,bgr,meta);strip=(Mat)stripMethod.invoke(mapper,image,meta);
            byte[] expected=new byte[w*h],actual=new byte[w*h];full.get(0,0,expected);strip.get(0,0,actual);int visible=0;for(byte value:expected)if(value!=0)visible++;assertTrue("Fixture must exercise visible and excluded regions",visible>100&&visible<expected.length-100);assertArrayEquals("Strip boundaries, colors, and UI occlusions must retain exact exclusions",expected,actual);
        }finally{bgr.release();if(full!=null)full.release();if(strip!=null)strip.release();image.recycle();}
    }

    @Test public void batchMappingPreservesNativePixelsAcrossReversalDropAndResume()throws Exception{
        assertTrue(OpenCVLoader.initLocal());Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();File recording=RecordingStore.create(c);
        int worldW=2240,worldH=1220,w=1600,h=900;Bitmap world=Bitmap.createBitmap(worldW,worldH,Bitmap.Config.ARGB_8888);int[] data=new int[worldW*worldH];Random random=new Random(73512);
        for(int y=0;y<worldH;y++)for(int x=0;x<worldW;x++){int v=35+random.nextInt(125);data[y*worldW+x]=0xff000000|v<<16|v<<8|v;}world.setPixels(data,0,worldW,0,0,worldW,worldH);
        Canvas terrain=new Canvas(world);Paint texture=new Paint();texture.setColor(0xffaaaaaa);texture.setStyle(Paint.Style.STROKE);texture.setStrokeWidth(3);
        for(int i=0;i<180;i++){int x=random.nextInt(worldW-60),y=random.nextInt(worldH-60);terrain.drawRect(x,y,x+15+random.nextInt(40),y+15+random.nextInt(40),texture);}
        int[][] path={{0,0},{320,0},{640,0},{640,320},{320,320},{0,320}};
        for(int i=0;i<path.length;i++){Bitmap frame=Bitmap.createBitmap(world,path[i][0],path[i][1],w,h).copy(Bitmap.Config.ARGB_8888,true);Canvas canvas=new Canvas(frame);Paint pad=new Paint();pad.setColor(0xff888888);pad.setStyle(Paint.Style.STROKE);pad.setStrokeWidth(8);
            int left=160,top=650,pw=192,ph=170;Paint inside=new Paint();inside.setColor(Color.BLACK);canvas.drawRect(left,top,left+2*pw,top+ph,inside);
            canvas.drawRect(left,top,left+pw,top+ph,pad);canvas.drawRect(left+pw,top,left+2*pw,top+ph,pad);
            RecordingStore.saveFrame(recording,i+1,frame,new JSONObject().put("captureElapsedMs",1000+i*300).put("width",w).put("height",h).put("occlusions",new JSONArray()));frame.recycle();}
        RecordingStore.finish(recording,"Ready");
        // Pause after a few fully committed tiles, then reopen the mapper from disk.
        try(OfflineReconstructor mapper=new OfflineReconstructor(recording,()->false,(stage,done,total)->{if(stage.equals("Building native map")&&done==2)throw new InterruptedIOException("Pause test");})){mapper.build();fail("Expected pause");}catch(InterruptedIOException expected){}
        JSONObject map;final OfflineReconstructor[] current=new OfflineReconstructor[1];try(OfflineReconstructor mapper=new OfflineReconstructor(recording,()->false,(stage,done,total)->{
            if(stage.equals("Preparing previews")){Field cache=OfflineReconstructor.class.getDeclaredField("cache"),sift=OfflineReconstructor.class.getDeclaredField("sift");cache.setAccessible(true);sift.setAccessible(true);assertTrue("Feature descriptors must be released before preview",((Map<?,?>)cache.get(current[0])).isEmpty());assertNull("Native SIFT must not remain resident in preview stage",sift.get(current[0]));}
        })){current[0]=mapper;map=mapper.build();}
        JSONArray sections=map.getJSONArray("sections");assertEquals("Native CV should connect the whole known trajectory: "+map,1,sections.length());JSONObject section=sections.getJSONObject(0);assertEquals(map.toString(),path.length,section.getInt("frames"));JSONArray bounds=section.getJSONArray("bounds");
        assertEquals(worldW,bounds.getInt(2)-bounds.getInt(0));assertEquals(worldH,bounds.getInt(3)-bounds.getInt(1));
        File png=new File(recording,"test-native.png");try(NativeAtlas atlas=new NativeAtlas(new File(recording,"map-v1/section-0"));OutputStream out=new FileOutputStream(png)){
            atlas.bounds=new int[]{bounds.getInt(0),bounds.getInt(1),bounds.getInt(2),bounds.getInt(3)};atlas.export(out,()->false,value->{});}
        Bitmap exported=BitmapFactory.decodeFile(png.getPath());assertNotNull(exported);assertEquals(worldW,exported.getWidth());assertEquals(worldH,exported.getHeight());int checked=0;
        for(int y=0;y<worldH;y+=3)for(int x=0;x<worldW;x+=3){int pixel=exported.getPixel(x,y);if((pixel>>>24)!=0){assertEquals("Native source pixel at "+x+","+y,world.getPixel(x,y),pixel);checked++;}}
        assertTrue("Most of the scene should be covered",checked>worldW*worldH/9*.65);exported.recycle();world.recycle();
    }
}
