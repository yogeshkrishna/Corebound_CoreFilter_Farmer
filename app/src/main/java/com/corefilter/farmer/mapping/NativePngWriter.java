package com.corefilter.farmer.mapping;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;

/** Streaming RGBA PNG: one row and one compressed chunk in memory, at native size. */
public final class NativePngWriter {
    public interface Rows { void read(int y,int[] argb) throws IOException; }
    public static void write(OutputStream output,int width,int height,Rows rows) throws IOException {
        if(width<1||height<1||width>200000||height>200000)throw new IOException("Invalid export dimensions");
        DataOutputStream out=new DataOutputStream(output);out.write(new byte[]{(byte)137,80,78,71,13,10,26,10});
        ByteArrayOutputStream head=new ByteArrayOutputStream();DataOutputStream h=new DataOutputStream(head);
        h.writeInt(width);h.writeInt(height);h.write(new byte[]{8,6,0,0,0});chunk(out,"IHDR",head.toByteArray(),head.size());
        ChunkStream chunks=new ChunkStream(out);Deflater deflater=new Deflater(6);
        try {DeflaterOutputStream compressed=new DeflaterOutputStream(chunks,deflater,32768);
            int[] pixels=new int[width];byte[] row=new byte[1+width*4];
            for(int y=0;y<height;y++){rows.read(y,pixels);row[0]=0;for(int x=0,k=1;x<width;x++){int p=pixels[x];row[k++]=(byte)(p>>16);row[k++]=(byte)(p>>8);row[k++]=(byte)p;row[k++]=(byte)(p>>>24);}compressed.write(row);}
            compressed.finish();chunks.flush();chunk(out,"IEND",new byte[0],0);out.flush();
        }finally{deflater.end();}
    }
    private static void chunk(DataOutputStream out,String type,byte[] data,int count)throws IOException{
        byte[] name=type.getBytes(StandardCharsets.US_ASCII);CRC32 crc=new CRC32();crc.update(name);crc.update(data,0,count);
        out.writeInt(count);out.write(name);out.write(data,0,count);out.writeInt((int)crc.getValue());
    }
    private static final class ChunkStream extends OutputStream {
        private final DataOutputStream out;private final byte[] buffer=new byte[65536];private int used;
        ChunkStream(DataOutputStream out){this.out=out;}
        @Override public void write(int v)throws IOException{buffer[used++]=(byte)v;if(used==buffer.length)flush();}
        @Override public void write(byte[] b,int off,int len)throws IOException{while(len>0){int n=Math.min(len,buffer.length-used);System.arraycopy(b,off,buffer,used,n);used+=n;off+=n;len-=n;if(used==buffer.length)flush();}}
        @Override public void flush()throws IOException{if(used>0){chunk(out,"IDAT",buffer,used);used=0;}}
    }
}
