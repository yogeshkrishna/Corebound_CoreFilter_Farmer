package com.corefilter.farmer;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.*;

/** Display-only decoding. Native tiles and exported pixels are never downsampled. */
final class PreviewImages {
    private PreviewImages(){}
    static int sample(int width,int height,long budget){
        if(width<1||height<1||budget<4)throw new IllegalArgumentException("Invalid preview dimensions");
        int sample=1;
        while(((width+(long)sample-1)/sample)*((height+(long)sample-1)/sample)>budget/4){
            if(sample>=1<<29)throw new IllegalArgumentException("Preview dimensions are too large");sample*=2;
        }
        return sample;
    }
    static Bitmap read(File file)throws IOException{
        if(!file.isFile())throw new IOException("The display preview is unavailable. Your native map is saved; you can still export its PNG.");
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),bounds);
        if(bounds.outWidth<1||bounds.outHeight<1)throw new IOException("Cannot read this display preview. You can still save the native map PNG.");
        long budget=Math.max(256*1024,Math.min(4L*1024*1024,Runtime.getRuntime().maxMemory()/32));
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=sample(bounds.outWidth,bounds.outHeight,budget);
        options.inPreferredConfig=Bitmap.Config.ARGB_8888;options.inScaled=false;
        for(int attempt=0;attempt<4;attempt++){
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Preview closed");
            try{Bitmap image=BitmapFactory.decodeFile(file.getPath(),options);if(image==null)throw new IOException("Cannot read the display preview. Your native map is retained.");return image;}
            catch(OutOfMemoryError lowMemory){options.inSampleSize*=2;}
        }
        throw new IOException("Not enough free memory to show a preview. Close the viewer and use Save native PNG; your full-resolution map is saved.");
    }
}
