package com.corefilter.farmer;

import android.graphics.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CaptureGeometryTest {
    @Test public void rotatedOrScaledWindowBuffersAreRejectedInsteadOfWarped(){
        Rect display=new Rect(0,0,600,300);
        Bitmap rotated=Bitmap.createBitmap(300,600,Bitmap.Config.ARGB_8888);
        assertNull(CaptureGeometry.placeWindow(rotated,display,display));assertFalse(CaptureGeometry.matches(rotated,display));
        Bitmap scaled=Bitmap.createBitmap(300,150,Bitmap.Config.ARGB_8888);
        assertNull(CaptureGeometry.placeWindow(scaled,display,display));
    }
    @Test public void nativeWindowPixelsRetainTheirScreenCoordinates(){
        Bitmap window=Bitmap.createBitmap(80,40,Bitmap.Config.ARGB_8888);window.eraseColor(Color.RED);window.setPixel(20,10,Color.GREEN);
        Bitmap full=CaptureGeometry.placeWindow(window,new Rect(50,30,130,70),new Rect(0,0,200,100));
        assertEquals(Color.GREEN,full.getPixel(70,40));assertEquals(Color.RED,full.getPixel(50,30));assertEquals(0,full.getPixel(49,30));
        assertNull(CaptureGeometry.placeWindow(window,new Rect(150,30,230,70),new Rect(0,0,200,100)));
    }
    @Test public void previewFitsWithoutChangingImageProportions(){
        Bitmap image=Bitmap.createBitmap(600,300,Bitmap.Config.ARGB_8888);
        RectF fit=CaptureGeometry.fit(image,new RectF(0,70,300,600));
        assertEquals(2,fit.width()/fit.height(),.001);assertTrue(fit.top>=70);assertEquals(300,fit.width(),.001);
    }
}
