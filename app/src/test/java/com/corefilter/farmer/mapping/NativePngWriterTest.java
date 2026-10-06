package com.corefilter.farmer.mapping;

import org.junit.Test;
import java.io.*;
import java.util.zip.*;
import static org.junit.Assert.*;

public class NativePngWriterTest {
    @Test public void exportedPixelsAndAlphaAreExactAcrossManyChunks()throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();int w=1601,h=211;
        NativePngWriter.write(out,w,h,(y,row)->{for(int x=0;x<w;x++)row[x]=pixel(x,y);});
        DataInputStream chunks=new DataInputStream(new ByteArrayInputStream(out.toByteArray()));byte[] signature=new byte[8];chunks.readFully(signature);assertArrayEquals(new byte[]{(byte)137,80,78,71,13,10,26,10},signature);
        ByteArrayOutputStream compressed=new ByteArrayOutputStream();boolean ended=false;
        while(chunks.available()>0){int n=chunks.readInt();byte[] type=new byte[4],bytes=new byte[n];chunks.readFully(type);chunks.readFully(bytes);CRC32 crc=new CRC32();crc.update(type);crc.update(bytes);assertEquals((int)crc.getValue(),chunks.readInt());String name=new String(type,java.nio.charset.StandardCharsets.US_ASCII);
            if(name.equals("IHDR")){DataInputStream header=new DataInputStream(new ByteArrayInputStream(bytes));assertEquals(w,header.readInt());assertEquals(h,header.readInt());assertEquals(8,header.readByte());assertEquals(6,header.readByte());}
            if(name.equals("IDAT"))compressed.write(bytes);if(name.equals("IEND"))ended=true;}
        assertTrue(ended);DataInputStream decoded=new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(compressed.toByteArray())));
        for(int y=0;y<h;y++){assertEquals(1,decoded.readUnsignedByte());int r=0,g=0,b=0,a=0;for(int x=0;x<w;x++){
            r=(r+decoded.readUnsignedByte())&255;g=(g+decoded.readUnsignedByte())&255;b=(b+decoded.readUnsignedByte())&255;a=(a+decoded.readUnsignedByte())&255;
            assertEquals(pixel(x,y),a<<24|r<<16|g<<8|b);
        }}assertEquals(-1,decoded.read());
    }
    private int pixel(int x,int y){int noise=Integer.rotateLeft(x*3571+y*7919,x%21);return ((x+y)%7==0?0:0xff000000)|(noise&0xffffff);}
    @Test public void cancellationStopsBeforeWritingAValidCompletedPng()throws Exception{
        try{NativePngWriter.write(new ByteArrayOutputStream(),100,20,(y,row)->{if(y==4)throw new InterruptedIOException("Paused");});fail();}catch(InterruptedIOException expected){assertEquals("Paused",expected.getMessage());}
    }
    @Test public void losslessSubFilterCompressesFlatWideSceneryWithoutAnExtraImageBuffer()throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();NativePngWriter.write(out,8000,40,(y,row)->java.util.Arrays.fill(row,0xff445566));
        assertTrue("Flat native terrain should compress to well below a raw row",out.size()<4000);
    }
    @Test public void invalidDimensionsAreRejectedBeforeOutputOrAllocation()throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();try{NativePngWriter.write(out,200001,1,(y,row)->fail());fail();}catch(IOException expected){}
        assertEquals(0,out.size());
    }
}
