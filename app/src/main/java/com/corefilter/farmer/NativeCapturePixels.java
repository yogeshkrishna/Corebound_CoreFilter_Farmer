package com.corefilter.farmer;

import java.nio.ByteBuffer;

/** ImageReader row padding is not image data; RGBA planes must not be stretched. */
public final class NativeCapturePixels {
    private NativeCapturePixels(){}
    public static int[] decode(ByteBuffer source,int width,int height,int rowStride,int pixelStride){
        Decoder decoder=new Decoder(source,width,height,rowStride,pixelStride);
        int[] pixels=new int[width*height];
        decoder.decodeRows(0,height,pixels);
        return pixels;
    }

    /** Reads the original RGBA pixels into a caller-owned, reusable strip. */
    public static final class Decoder {
        private final ByteBuffer bytes;
        private final int width,height,rowStride,pixelStride,offset;
        public Decoder(ByteBuffer source,int width,int height,int rowStride,int pixelStride){
            if(width<1||height<1||pixelStride<4||rowStride<(long)width*pixelStride||(long)width*height>20_000_000L)throw new IllegalArgumentException("Invalid native image geometry");
            bytes=source.duplicate();offset=bytes.position();
            long required=(long)(height-1)*rowStride+(long)(width-1)*pixelStride+4;
            if(required>bytes.remaining())throw new IllegalArgumentException("Truncated native image plane");
            this.width=width;this.height=height;this.rowStride=rowStride;this.pixelStride=pixelStride;
        }
        public void decodeRows(int firstRow,int rows,int[] pixels){
            if(firstRow<0||rows<1||firstRow>(long)height-rows||pixels.length<(long)width*rows)throw new IllegalArgumentException("Invalid native image strip");
            int output=0;
            for(int y=firstRow;y<firstRow+rows;y++)for(int x=0;x<width;x++){
                int i=offset+y*rowStride+x*pixelStride;
                int r=bytes.get(i)&255,g=bytes.get(i+1)&255,b=bytes.get(i+2)&255,a=bytes.get(i+3)&255;
                pixels[output++]=(a<<24)|(r<<16)|(g<<8)|b;
            }
        }
    }
}
