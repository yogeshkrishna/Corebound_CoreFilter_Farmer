package com.corefilter.farmer;
import org.junit.Test;
import java.nio.ByteBuffer;
import static org.junit.Assert.*;

public class NativeCapturePixelsTest {
    @Test public void skipsRowPaddingAndAllowsUnpaddedFinalRow(){
        byte[] bytes={1,2,3,(byte)255,4,5,6,(byte)255,99,99,99,99,7,8,9,(byte)255,10,11,12,(byte)255};
        assertArrayEquals(new int[]{0xff010203,0xff040506,0xff070809,0xff0a0b0c},NativeCapturePixels.decode(ByteBuffer.wrap(bytes),2,2,12,4));
    }
    @Test public void honorsBufferPositionAndPixelStride(){
        ByteBuffer bytes=ByteBuffer.wrap(new byte[]{99,1,2,3,4,0,0,0,0,5,6,7,8});bytes.position(1);
        assertArrayEquals(new int[]{0x04010203,0x08050607},NativeCapturePixels.decode(bytes,2,1,16,8));assertEquals(1,bytes.position());
    }
    @Test(expected=IllegalArgumentException.class)public void refusesIncompletePlane(){NativeCapturePixels.decode(ByteBuffer.allocate(8),2,2,12,4);}
    @Test public void reusableStripsMatchNativePixelsAcrossPaddingAndOffsets(){
        int width=73,height=37,pixelStride=8,rowStride=width*pixelStride+12,offset=9;
        ByteBuffer bytes=ByteBuffer.allocate(offset+(height-1)*rowStride+(width-1)*pixelStride+4);
        int[] expected=new int[width*height];
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){
            int i=offset+y*rowStride+x*pixelStride,r=(x*3+y)&255,g=(y*7+x)&255,b=(x+y*2)&255,a=(x*5+y)&255;
            bytes.put(i,(byte)r);bytes.put(i+1,(byte)g);bytes.put(i+2,(byte)b);bytes.put(i+3,(byte)a);
            expected[y*width+x]=(a<<24)|(r<<16)|(g<<8)|b;
        }
        bytes.position(offset);NativeCapturePixels.Decoder decoder=new NativeCapturePixels.Decoder(bytes,width,height,rowStride,pixelStride);
        int[] strip=new int[width*8],actual=new int[width*height];
        for(int y=0;y<height;y+=8){int count=Math.min(8,height-y);decoder.decodeRows(y,count,strip);System.arraycopy(strip,0,actual,y*width,count*width);}
        assertArrayEquals(expected,actual);assertEquals(offset,bytes.position());
    }
    @Test(expected=IllegalArgumentException.class)public void refusesStripBeyondImage(){new NativeCapturePixels.Decoder(ByteBuffer.allocate(24),2,3,8,4).decodeRows(2,2,new int[4]);}
    @Test(expected=IllegalArgumentException.class)public void refusesUndersizedReusableStrip(){new NativeCapturePixels.Decoder(ByteBuffer.allocate(24),2,3,8,4).decodeRows(0,2,new int[3]);}
}
