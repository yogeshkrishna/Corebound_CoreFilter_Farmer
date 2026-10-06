package com.corefilter.farmer.mapping;

import android.graphics.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NativeAtlasTest {
    @Test public void nativePixelsSurviveWideNegativeTilesMasksQualityAndRestart()throws Exception{
        File directory=Files.createTempDirectory("native-atlas").toFile();int w=9*NativeAtlas.TILE+71,h=35;
        Bitmap image=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);int[] source=new int[w*h];byte[] mask=new byte[w*h],quality=new byte[w*h];Arrays.fill(mask,(byte)255);Arrays.fill(quality,(byte)31);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){source[y*w+x]=0xff000000|((x*3571+y*7919)&0xffffff);if(x>2300&&x<2350)mask[y*w+x]=0;}
        image.setPixels(source,0,w,0,0,w,h);int[] bounds;
        try(NativeAtlas atlas=new NativeAtlas(directory)){atlas.paint(image,mask,quality,-37,-17);bounds=atlas.bounds.clone();}
        // A less visible later source must not replace original terrain pixels.
        Bitmap later=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);later.eraseColor(Color.RED);float[] lowDistance=new float[w*h];Arrays.fill(lowDistance,1);
        try(NativeAtlas atlas=new NativeAtlas(directory)){atlas.bounds=bounds;atlas.paint(later,mask,lowDistance,-37,-17);}
        later.recycle();image.recycle();
        File destination=new File(directory,"native-export.png");AtomicInteger progress=new AtomicInteger();
        try(NativeAtlas atlas=new NativeAtlas(directory);OutputStream out=new FileOutputStream(destination)){atlas.bounds=bounds;atlas.export(out,()->false,progress::set);}
        Bitmap png=BitmapFactory.decodeFile(destination.getPath());assertNotNull(png);assertEquals(w,png.getWidth());assertEquals(h,png.getHeight());int[] actual=new int[w*h];png.getPixels(actual,0,w,0,0,w,h);png.recycle();
        for(int i=0;i<source.length;i++)assertEquals("Exact native pixel "+i,mask[i]==0?0:source[i],actual[i]);
        assertEquals(100,progress.get());assertEquals(0,directory.listFiles((d,n)->n.startsWith(".export-band")).length);
    }
    @Test public void previewDoesNotReadUnusedQualityFilesAndCancellationKeepsExistingPreview()throws Exception{
        File directory=Files.createTempDirectory("native-preview").toFile();Bitmap image=Bitmap.createBitmap(530,60,Bitmap.Config.ARGB_8888);image.eraseColor(Color.BLUE);byte[] mask=new byte[530*60],quality=new byte[mask.length];Arrays.fill(mask,(byte)255);Arrays.fill(quality,(byte)31);int[] bounds;
        try(NativeAtlas atlas=new NativeAtlas(directory)){atlas.paint(image,mask,quality,-4,-6);bounds=atlas.bounds.clone();}image.recycle();
        for(File q:directory.listFiles((d,n)->n.endsWith(".quality")))try(FileOutputStream out=new FileOutputStream(q)){out.write(1);}
        File preview=new File(directory,"preview.png");byte[] before;
        try(NativeAtlas atlas=new NativeAtlas(directory)){atlas.bounds=bounds;assertTrue(atlas.preview(preview,()->false));before=Files.readAllBytes(preview.toPath());
            try{atlas.preview(preview,()->true);fail();}catch(InterruptedIOException expected){}assertArrayEquals(before,Files.readAllBytes(preview.toPath()));}
        Bitmap png=BitmapFactory.decodeFile(preview.getPath());assertEquals(530,png.getWidth());assertEquals(60,png.getHeight());assertEquals(Color.BLUE,png.getPixel(120,20));png.recycle();
    }
    @Test public void previewHasABoundedDisplaySizeAndNativeExportStaysFullSize()throws Exception{
        File directory=Files.createTempDirectory("native-preview-size").toFile();Bitmap image=Bitmap.createBitmap(2049,10,Bitmap.Config.ARGB_8888);image.eraseColor(Color.GREEN);byte[] mask=new byte[2049*10],quality=new byte[mask.length];Arrays.fill(mask,(byte)255);Arrays.fill(quality,(byte)31);
        try(NativeAtlas atlas=new NativeAtlas(directory)){atlas.paint(image,mask,quality,0,0);File preview=new File(directory,"preview.png");atlas.preview(preview);Bitmap p=BitmapFactory.decodeFile(preview.getPath());assertEquals(1024,p.getWidth());assertTrue(p.getHeight()>=1);p.recycle();
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();atlas.export(bytes,()->false,value->{});Bitmap nativeImage=BitmapFactory.decodeByteArray(bytes.toByteArray(),0,bytes.size());assertEquals(2049,nativeImage.getWidth());assertEquals(10,nativeImage.getHeight());nativeImage.recycle();}
        image.recycle();
    }
    @Test public void betterVisibilityDoesNotRecompressUnchangedTerrainPixels()throws Exception{
        File directory=Files.createTempDirectory("native-quality-update").toFile();Bitmap image=Bitmap.createBitmap(60,30,Bitmap.Config.ARGB_8888);image.eraseColor(Color.BLUE);byte[] mask=new byte[1800],quality=new byte[1800];Arrays.fill(mask,(byte)255);Arrays.fill(quality,(byte)10);
        try(NativeAtlas atlas=new NativeAtlas(directory)){atlas.paint(image,mask,quality,0,0);atlas.flush();File png=new File(directory,"0_0.png");assertTrue(png.setLastModified(1234567890000L));long before=png.lastModified();
            Arrays.fill(quality,(byte)40);atlas.paint(image,mask,quality,0,0);atlas.flush();assertEquals("Quality-only changes must not encode the terrain again",before,png.lastModified());
            byte[] scores=Files.readAllBytes(new File(directory,"0_0.quality").toPath());assertEquals(40,scores[10*NativeAtlas.TILE+10]&255);
        }image.recycle();
    }
    @Test public void cancelledExportRemovesScratchAndMalformedTileDoesNotMasqueradeAsLowMemory()throws Exception{
        File directory=Files.createTempDirectory("native-export-cancel").toFile();Bitmap image=Bitmap.createBitmap(60,30,Bitmap.Config.ARGB_8888);image.eraseColor(Color.BLUE);byte[] mask=new byte[1800],quality=new byte[1800];Arrays.fill(mask,(byte)255);Arrays.fill(quality,(byte)10);
        try(NativeAtlas atlas=new NativeAtlas(directory)){atlas.paint(image,mask,quality,0,0);AtomicInteger calls=new AtomicInteger();
            try{atlas.export(new ByteArrayOutputStream(),()->calls.incrementAndGet()>5,value->{});fail();}catch(InterruptedIOException expected){}
            assertEquals(0,directory.listFiles((d,n)->n.startsWith(".export-band")).length);
            try(FileOutputStream out=new FileOutputStream(new File(directory,"0_0.png"))){out.write(new byte[]{1,2,3});}
            try{atlas.preview(new File(directory,"preview.png"),()->false);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("Unreadable map tile"));}
        }image.recycle();
    }
}
