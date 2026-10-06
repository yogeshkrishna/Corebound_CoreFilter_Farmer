package com.corefilter.farmer;

import java.nio.ByteBuffer;

/** ImageReader row padding is not image data; RGBA planes must not be stretched. */
public final class NativeCapturePixels {
    private NativeCapturePixels(){}
    public static int[] decode(ByteBuffer source,int width,int height,int rowStride,int pixelStride){
        if(width<1||height<1||pixelStride<4||rowStride<(long)width*pixelStride||(long)width*height>20_000_000L)throw new IllegalArgumentException("Invalid native image geometry");
        ByteBuffer bytes=source.duplicate();int offset=bytes.position();
        long required=(long)(height-1)*rowStride+(long)(width-1)*pixelStride+4;
        if(required>bytes.remaining())throw new IllegalArgumentException("Truncated native image plane");
        int[] pixels=new int[width*height];
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){
            int i=offset+y*rowStride+x*pixelStride;
            int r=bytes.get(i)&255,g=bytes.get(i+1)&255,b=bytes.get(i+2)&255,a=bytes.get(i+3)&255;
            pixels[y*width+x]=(a<<24)|(r<<16)|(g<<8)|b;
        }
        return pixels;
    }
}
