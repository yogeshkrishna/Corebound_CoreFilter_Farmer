package com.corefilter.farmer.mapping;

import android.graphics.*;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/** Native pixels live on disk; only a small working set of 512px tiles lives in memory. */
public final class NativeAtlas implements AutoCloseable {
    public static final int TILE=512;
    private static final int CACHE_TILES=4, PAINT_ROWS=32, PREVIEW_EDGE=1024;
    private static final long EXPORT_BAND_BYTES=16L*1024*1024;
    public final File directory;
    private final LinkedHashMap<String,Tile> cache=new LinkedHashMap<>(8,.75f,true);
    public final Set<String> keys=new TreeSet<>();
    public int[] bounds;
    private static final class Tile {
        final int[] pixels=new int[TILE*TILE];
        final byte[] quality=new byte[TILE*TILE];
        boolean pixelsDirty,qualityDirty;
    }
    public static final class PreviewMemoryException extends IOException {
        public PreviewMemoryException(){super("Preview unavailable at the current memory limit; native map is retained");}
    }
    public NativeAtlas(File directory)throws Exception {
        this.directory=directory;
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot create map tiles");
        File[] files=directory.listFiles((d,n)->n.matches("-?[0-9]+_-?[0-9]+\\.png"));
        if(files!=null)for(File f:files)keys.add(f.getName().replace(".png",""));
    }
    private Tile tile(String key)throws IOException {
        Tile t=cache.get(key);if(t!=null)return t;
        // Evict before allocating the replacement, including while a source frame is live.
        if(cache.size()>=CACHE_TILES){Map.Entry<String,Tile> first=cache.entrySet().iterator().next();save(first.getKey(),first.getValue());cache.remove(first.getKey());}
        t=new Tile();File file=new File(directory,key+".png");
        if(file.isFile()){
            Bitmap b=readTile(key,1);
            try{b.getPixels(t.pixels,0,TILE,0,0,TILE,TILE);}finally{b.recycle();}
            File q=new File(directory,key+".quality");
            if(q.isFile())try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(q)))){in.readFully(t.quality);}
        }
        cache.put(key,t);return t;
    }
    private void save(String key,Tile t)throws IOException {
        if(t.pixelsDirty){
            writeAtomic(new File(directory,key+".png"),out->NativePngWriter.write(out,TILE,TILE,(row,argb)->System.arraycopy(t.pixels,row*TILE,argb,0,TILE)));
            t.pixelsDirty=false;
        }
        if(t.qualityDirty){
            writeAtomic(new File(directory,key+".quality"),out->out.write(t.quality));
            t.qualityDirty=false;
        }
    }
    public void paint(Bitmap image,byte[] mask,float[] distance,int x,int y)throws IOException {
        paint(image,mask,null,distance,x,y);
    }
    /** Quantized distance quality needs one byte per source pixel instead of a full float array. */
    public void paint(Bitmap image,byte[] mask,byte[] qualityScores,int x,int y)throws IOException {
        paint(image,mask,qualityScores,null,x,y);
    }
    private void paint(Bitmap image,byte[] mask,byte[] qualityScores,float[] distance,int x,int y)throws IOException {
        int w=image.getWidth(),h=image.getHeight();long count=(long)w*h;
        if(mask.length<count||(qualityScores==null?distance.length:qualityScores.length)<count)throw new IOException("Incomplete source visibility data");
        int[] src=new int[TILE*PAINT_ROWS];
        for(int ty=Math.floorDiv(y,TILE);ty<=Math.floorDiv(y+h-1,TILE);ty++)for(int tx=Math.floorDiv(x,TILE);tx<=Math.floorDiv(x+w-1,TILE);tx++){
            int ax=Math.max(x,tx*TILE),ay=Math.max(y,ty*TILE),bx=Math.min(x+w,(tx+1)*TILE),by=Math.min(y+h,(ty+1)*TILE);
            int stripWidth=bx-ax;String key=tx+"_"+ty;Tile t=null;
            for(int stripY=ay;stripY<by;stripY+=PAINT_ROWS){
                int rows=Math.min(PAINT_ROWS,by-stripY);
                image.getPixels(src,0,stripWidth,ax-x,stripY-y,stripWidth,rows);
                for(int sy=stripY;sy<stripY+rows;sy++)for(int sx=ax;sx<bx;sx++){
                    int si=(sy-y)*w+sx-x;if(mask[si]==0)continue;
                    if(t==null)t=tile(key);int ti=(sy-ty*TILE)*TILE+sx-tx*TILE;
                    int score=qualityScores!=null?(qualityScores[si]&255):1+(int)Math.min(64,distance[si]);
                    if((t.pixels[ti]>>>24)==0||score>=(t.quality[ti]&255)){
                        int pixel=src[(sy-stripY)*stripWidth+sx-ax]|0xff000000;
                        if(t.pixels[ti]!=pixel){t.pixels[ti]=pixel;t.pixelsDirty=true;}
                        if((t.quality[ti]&255)!=score){t.quality[ti]=(byte)score;t.qualityDirty=true;}
                    }
                }
            }
            if(t!=null){keys.add(key);if(bounds==null)bounds=new int[]{ax,ay,bx,by};else{bounds[0]=Math.min(bounds[0],ax);bounds[1]=Math.min(bounds[1],ay);bounds[2]=Math.max(bounds[2],bx);bounds[3]=Math.max(bounds[3],by);}}
        }
    }
    public JSONObject summary(int frames)throws JSONException {
        return new JSONObject().put("frames",frames).put("bounds",bounds==null?JSONObject.NULL:new JSONArray(bounds)).put("tiles",new JSONArray(keys));
    }
    public void preview(File destination)throws IOException {
        if(!preview(destination,()->false))throw new PreviewMemoryException();
    }
    /** Display previews may shrink on a constrained phone. Native map/export pixels never shrink. */
    public boolean preview(File destination,BooleanSupplier cancelled)throws IOException {
        if(bounds==null)return true;
        releaseCache();
        for(int edge=PREVIEW_EDGE;edge>=256;edge/=2){
            check(cancelled);Bitmap image=null;Canvas canvas=null;
            try{
                int w=bounds[2]-bounds[0],h=bounds[3]-bounds[1];double scale=Math.min(1,(double)edge/Math.max(w,h));
                image=Bitmap.createBitmap(Math.max(1,(int)Math.ceil(w*scale)),Math.max(1,(int)Math.ceil(h*scale)),Bitmap.Config.ARGB_8888);
                canvas=new Canvas(image);Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
                int sample=1;while(sample<512&&sample*2<=1/scale)sample*=2;
                for(String key:keys){
                    check(cancelled);int[] position=position(key);int x=position[0]*TILE-bounds[0],y=position[1]*TILE-bounds[1];
                    Bitmap tile=readTile(key,sample);
                    try{canvas.drawBitmap(tile,null,new RectF((float)(x*scale),(float)(y*scale),(float)((x+TILE)*scale),(float)((y+TILE)*scale)),paint);}
                    finally{tile.recycle();}
                }
                check(cancelled);Bitmap complete=image;
                writeAtomic(destination,out->{if(!complete.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("Cannot save preview");check(cancelled);});
                return true;
            }catch(OutOfMemoryError insufficientMemory){
                // Retry with at most one quarter of the preview pixels, after recycling below.
            }finally{if(canvas!=null)canvas.setBitmap(null);if(image!=null)image.recycle();}
        }
        return false;
    }
    private Bitmap readTile(String key,int sample)throws IOException {
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=sample;options.inScaled=false;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
        String path=new File(directory,key+".png").getPath();
        if(sample>1){options.inSampleSize=1;options.inJustDecodeBounds=true;BitmapFactory.decodeFile(path,options);
            if(options.outWidth!=TILE||options.outHeight!=TILE)throw new IOException("Unreadable map tile "+key);options.inJustDecodeBounds=false;options.inSampleSize=sample;}
        Bitmap b=BitmapFactory.decodeFile(path,options);
        if(b==null||b.getWidth()!=TILE/sample||b.getHeight()!=TILE/sample){if(b!=null)b.recycle();throw new IOException("Unreadable map tile "+key);}
        return b;
    }
    private static int[] position(String key){int underscore=key.indexOf('_');return new int[]{Integer.parseInt(key.substring(0,underscore)),Integer.parseInt(key.substring(underscore+1))};}
    private static void check(BooleanSupplier cancelled)throws InterruptedIOException {
        if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Map operation paused");
    }
    private interface FileWriter {void write(FileOutputStream out)throws IOException;}
    private static void writeAtomic(File destination,FileWriter writer)throws IOException {
        File pending=File.createTempFile(".atlas-", ".pending",destination.getParentFile());
        try{
            try(FileOutputStream out=new FileOutputStream(pending)){writer.write(out);out.flush();out.getFD().sync();}
            try{Files.move(pending.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException unavailable){Files.move(pending.toPath(),destination.toPath(),StandardCopyOption.REPLACE_EXISTING);}
        }finally{pending.delete();}
    }
    public void export(OutputStream out,BooleanSupplier cancelled,IntConsumer progress)throws IOException {
        if(bounds==null)throw new IOException("No mapped pixels to export");
        int width=bounds[2]-bounds[0],height=bounds[3]-bounds[1];NativePngWriter.validateDimensions(width,height);check(cancelled);releaseCache();
        // An export used to decode the same PNG up to 512 times when a row crossed >8 tiles.
        // Assemble bounded bands on disk so native-sized rows stream without retaining the map.
        // One export job runs at a time. Reuse its private scratch path after
        // process death instead of leaving another 16 MB temporary file per retry.
        File scratch=new File(directory,".export-band.tmp");
        try(RandomAccessFile band=new RandomAccessFile(scratch,"rw")){
            final int[] bandEnd={-1};byte[] packed=new byte[32768];int[] tileRows=new int[TILE*PAINT_ROWS];
            NativePngWriter.write(out,width,height,(row,argb)->{
                check(cancelled);
                if(row>=bandEnd[0]){
                    int y=row+bounds[1],tileEnd=(Math.floorDiv(y,TILE)+1)*TILE-bounds[1];
                    int rows=(int)Math.max(1,Math.min(TILE,EXPORT_BAND_BYTES/((long)width*4)));
                    bandEnd[0]=Math.min(height,Math.min(tileEnd,row+rows));
                    buildBand(band,row,bandEnd[0],width,tileRows,packed,cancelled);
                    band.seek(0);
                }
                int offset=0;
                while(offset<width){int pixels=Math.min(width-offset,packed.length/4);band.readFully(packed,0,pixels*4);
                    for(int i=0,k=0;i<pixels;i++,k+=4)argb[offset+i]=(packed[k]&255)<<24|(packed[k+1]&255)<<16|(packed[k+2]&255)<<8|(packed[k+3]&255);
                    offset+=pixels;
                }
                if(row%64==0)progress.accept(row*100/height);
            });
            progress.accept(100);
        }finally{scratch.delete();}
    }
    private void buildBand(RandomAccessFile band,int first,int end,int width,int[] tileRows,byte[] packed,BooleanSupplier cancelled)throws IOException {
        band.setLength(0);band.setLength((long)(end-first)*width*4);
        int worldY=first+bounds[1],ty=Math.floorDiv(worldY,TILE);
        for(int tx=Math.floorDiv(bounds[0],TILE);tx<=Math.floorDiv(bounds[2]-1,TILE);tx++){
            check(cancelled);String key=tx+"_"+ty;if(!keys.contains(key))continue;
            int ax=Math.max(bounds[0],tx*TILE),bx=Math.min(bounds[2],(tx+1)*TILE),length=bx-ax;
            Bitmap tile=readTile(key,1);
            try{for(int strip=first;strip<end;strip+=PAINT_ROWS){
                check(cancelled);int rows=Math.min(PAINT_ROWS,end-strip);
                tile.getPixels(tileRows,0,length,ax-tx*TILE,Math.floorMod(strip+bounds[1],TILE),length,rows);
                for(int row=strip;row<strip+rows;row++){
                    for(int i=0,k=0;i<length;i++){int p=tileRows[(row-strip)*length+i];packed[k++]=(byte)(p>>>24);packed[k++]=(byte)(p>>16);packed[k++]=(byte)(p>>8);packed[k++]=(byte)p;}
                    band.seek(((long)(row-first)*width+ax-bounds[0])*4);band.write(packed,0,length*4);
                }
            }}finally{tile.recycle();}
        }
    }
    public void flush()throws IOException {for(Map.Entry<String,Tile> e:cache.entrySet())save(e.getKey(),e.getValue());}
    public void releaseCache()throws IOException {flush();cache.clear();}
    @Override public void close()throws IOException {releaseCache();}
}
