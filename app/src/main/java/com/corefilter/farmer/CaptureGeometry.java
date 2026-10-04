package com.corefilter.farmer;

import android.graphics.*;

/** Screen coordinates stay native: mismatched buffers must be recaptured, never stretched. */
final class CaptureGeometry {
    private CaptureGeometry(){}
    static boolean matches(Bitmap image,Rect bounds){return image.getWidth()==bounds.width()&&image.getHeight()==bounds.height();}
    static Bitmap placeWindow(Bitmap image,Rect window,Rect display){
        if(display.left!=0||display.top!=0||window.isEmpty()||!display.contains(window)||!matches(image,window))return null;
        if(window.equals(display))return image;
        Bitmap full=Bitmap.createBitmap(display.width(),display.height(),Bitmap.Config.ARGB_8888);
        new Canvas(full).drawBitmap(image,window.left,window.top,null);return full;
    }
    static RectF fit(Bitmap image,RectF area){
        float scale=Math.min(area.width()/image.getWidth(),area.height()/image.getHeight());
        float width=image.getWidth()*scale,height=image.getHeight()*scale;
        float left=area.centerX()-width/2,top=area.centerY()-height/2;
        return new RectF(left,top,left+width,top+height);
    }
}
