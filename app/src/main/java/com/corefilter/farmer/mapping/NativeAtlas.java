package com.corefilter.farmer.mapping;

import android.graphics.*;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.util.*;

/** A bounded cache of 512px native tiles, with visibility-based source-pixel selection. */
public final class NativeAtlas implements AutoCloseable {
    public static final int TILE=512;
    public final File directory;
    private final LinkedHashMap<String,Tile> cache=new LinkedHashMap<>(16,.75f,true);
    public final Set<String> keys=new TreeSet<>();public int[] bounds;
    private static final class Tile {int[] pixels=new int[TILE*TILE];byte[] quality=new byte[TILE*TILE];boolean dirty;}
    public NativeAtlas(File directory)throws Exception{this.directory=directory;if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot create map tiles");
        File[] files=directory.listFiles((d,n)->n.matches("-?[0-9]+_-?[0-9]+\\.png"));if(files!=null)for(File f:files)keys.add(f.getName().replace(".png",""));}
    private Tile tile(String key)throws IOException{
        Tile t=cache.get(key);if(t!=null)return t;t=new Tile();File file=new File(directory,key+".png");
        if(file.isFile()){Bitmap b=BitmapFactory.decodeFile(file.getPath());if(b==null||b.getWidth()!=TILE||b.getHeight()!=TILE)throw new IOException("Unreadable map tile");b.getPixels(t.pixels,0,TILE,0,0,TILE,TILE);b.recycle();
            File q=new File(directory,key+".quality");if(q.isFile())try(DataInputStream in=new DataInputStream(new FileInputStream(q))){in.readFully(t.quality);}}
        cache.put(key,t);if(cache.size()>8){Map.Entry<String,Tile> first=cache.entrySet().iterator().next();save(first.getKey(),first.getValue());cache.remove(first.getKey());}return t;
    }
    private void save(String key,Tile t)throws IOException{if(!t.dirty)return;
        Bitmap b=Bitmap.createBitmap(t.pixels,TILE,TILE,Bitmap.Config.ARGB_8888);AtomicFile atom=new AtomicFile(new File(directory,key+".png"));FileOutputStream out=atom.startWrite();
        try{if(!b.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("Cannot save map tile");atom.finishWrite(out);}catch(IOException e){atom.failWrite(out);throw e;}finally{b.recycle();}
        AtomicFile quality=new AtomicFile(new File(directory,key+".quality"));out=quality.startWrite();try{out.write(t.quality);quality.finishWrite(out);}catch(IOException e){quality.failWrite(out);throw e;}t.dirty=false;
    }
    public void paint(Bitmap image,byte[] mask,float[] distance,int x,int y)throws IOException{
        int w=image.getWidth(),h=image.getHeight();int[] src=new int[w*h];image.getPixels(src,0,w,0,0,w,h);
        for(int ty=Math.floorDiv(y,TILE);ty<=Math.floorDiv(y+h-1,TILE);ty++)for(int tx=Math.floorDiv(x,TILE);tx<=Math.floorDiv(x+w-1,TILE);tx++){
            int ax=Math.max(x,tx*TILE),ay=Math.max(y,ty*TILE),bx=Math.min(x+w,(tx+1)*TILE),by=Math.min(y+h,(ty+1)*TILE);
            String key=tx+"_"+ty;Tile t=null;for(int sy=ay;sy<by;sy++)for(int sx=ax;sx<bx;sx++){
                int si=(sy-y)*w+sx-x;if(mask[si]==0)continue;if(t==null)t=tile(key);int ti=(sy-ty*TILE)*TILE+sx-tx*TILE;
                int score=1+(int)Math.min(64,distance[si]);if((t.pixels[ti]>>>24)==0||score>=(t.quality[ti]&255)){t.pixels[ti]=src[si]|0xff000000;t.quality[ti]=(byte)score;t.dirty=true;}}
            if(t!=null){keys.add(key);if(bounds==null)bounds=new int[]{ax,ay,bx,by};else{bounds[0]=Math.min(bounds[0],ax);bounds[1]=Math.min(bounds[1],ay);bounds[2]=Math.max(bounds[2],bx);bounds[3]=Math.max(bounds[3],by);}}}
    }
    public JSONObject summary(int frames)throws JSONException{return new JSONObject().put("frames",frames).put("bounds",bounds==null?JSONObject.NULL:new JSONArray(bounds)).put("tiles",new JSONArray(keys));}
    public void preview(File destination)throws IOException{
        if(bounds==null)return;int w=bounds[2]-bounds[0],h=bounds[3]-bounds[1];double scale=Math.min(1,2048.0/Math.max(w,h));
        Bitmap preview=Bitmap.createBitmap(Math.max(1,(int)Math.ceil(w*scale)),Math.max(1,(int)Math.ceil(h*scale)),Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(preview);Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
        for(String key:keys){String[] k=key.split("_");int x=Integer.parseInt(k[0])*TILE-bounds[0],y=Integer.parseInt(k[1])*TILE-bounds[1];Tile t=tile(key);Bitmap b=Bitmap.createBitmap(t.pixels,TILE,TILE,Bitmap.Config.ARGB_8888);
            canvas.drawBitmap(b,null,new RectF((float)(x*scale),(float)(y*scale),(float)((x+TILE)*scale),(float)((y+TILE)*scale)),paint);b.recycle();}
        try(FileOutputStream out=new FileOutputStream(destination)){if(!preview.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("Cannot save preview");}finally{preview.recycle();}
    }
    public void export(OutputStream out,java.util.function.BooleanSupplier cancelled,java.util.function.IntConsumer progress)throws IOException{
        if(bounds==null)throw new IOException("No mapped pixels to export");int width=bounds[2]-bounds[0],height=bounds[3]-bounds[1];
        NativePngWriter.write(out,width,height,(row,argb)->{if(cancelled.getAsBoolean())throw new InterruptedIOException("Export paused");Arrays.fill(argb,0);int y=row+bounds[1],ty=Math.floorDiv(y,TILE),iy=Math.floorMod(y,TILE);
            for(int tx=Math.floorDiv(bounds[0],TILE);tx<=Math.floorDiv(bounds[2]-1,TILE);tx++){String key=tx+"_"+ty;if(!keys.contains(key))continue;Tile t=tile(key);int ax=Math.max(bounds[0],tx*TILE),bx=Math.min(bounds[2],(tx+1)*TILE);System.arraycopy(t.pixels,iy*TILE+ax-tx*TILE,argb,ax-bounds[0],bx-ax);}if(row%64==0)progress.accept(row*100/height);});
    }
    public void flush()throws IOException{for(Map.Entry<String,Tile> e:cache.entrySet())save(e.getKey(),e.getValue());}
    @Override public void close()throws IOException{flush();cache.clear();}
}
