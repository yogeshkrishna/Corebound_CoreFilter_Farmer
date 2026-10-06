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
}
