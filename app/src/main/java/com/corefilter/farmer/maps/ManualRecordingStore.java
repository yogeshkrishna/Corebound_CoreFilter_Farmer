package com.corefilter.farmer.maps;

import android.content.Context;
import com.corefilter.farmer.engine.FarmEngine;
import com.corefilter.farmer.engine.MapNavigator;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** Durable, bounded clean-game keyframes. Staging survives a process interruption. */
public final class ManualRecordingStore {
    private static final int MAX_FRAMES=900,MAX_BYTES=32*1024*1024;
    private static final Map<String,long[]> counters=new HashMap<>();
    private ManualRecordingStore(){}
    private static File directory(Context c,String id)throws IOException{
        if(id==null||!id.matches("[a-f0-9-]{36}"))throw new IOException("Invalid manual recording identifier");
        File d=new File(new File(c.getFilesDir(),"mapping-work"),id);
        if(!d.isDirectory()&&!d.mkdirs())throw new IOException("Cannot store manual mapping frames");return d;
    }
    public static synchronized boolean record(Context c,String id,FarmEngine.Frame f,MapNavigator.Snapshot pose,byte[] jpeg)throws IOException{
        if(jpeg==null||jpeg.length==0)return false;File d=directory(c,id);
        long[] stats=counters.get(id);if(stats==null){stats=new long[2];File[] old=d.listFiles((dir,name)->name.endsWith(".jpg"));if(old!=null)for(File p:old){stats[0]++;stats[1]+=p.length();}counters.put(id,stats);}
        if(stats[0]>=MAX_FRAMES||stats[1]+jpeg.length>MAX_BYTES){new File(d,"keyframes-truncated").createNewFile();return false;}
        String filename=String.format(Locale.US,"frame-%06d-%d.jpg",stats[0],f.capturedAt);
        try(FileOutputStream out=new FileOutputStream(new File(d,filename))){out.write(jpeg);out.getFD().sync();}
        try{
            JSONObject row=new JSONObject().put("file",filename).put("capturedAtMs",f.capturedAt).put("section",pose.room)
                .put("cameraConfidence",pose.cameraConfidence).put("cameraX",pose.cameraConfidence>=.55?pose.cameraX:JSONObject.NULL)
                .put("cameraY",pose.cameraConfidence>=.55?pose.cameraY:JSONObject.NULL).put("playerX",finite(f.playerX)).put("playerY",finite(f.playerY))
                .put("playerConfidence",f.playerConfidence).put("roofVisible",pose.screenPoses.length>0?pose.screenPoses[pose.screenPoses.length-1][11]>0:false);
            try(FileOutputStream out=new FileOutputStream(new File(d,"frames.jsonl"),true)){out.write((row.toString()+"\n").getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        }catch(JSONException e){throw new IOException("Cannot store mapping pose",e);}
        stats[0]++;stats[1]+=jpeg.length;return true;
    }
    private static Object finite(double v){return Double.isFinite(v)?v:JSONObject.NULL;}
    static void append(Context c,String id,ZipOutputStream zip)throws IOException{
        if(id==null)return;File d=directory(c,id);File[] files=d.listFiles();if(files==null)return;Arrays.sort(files,Comparator.comparing(File::getName));
        for(File f:files)if(f.isFile()&&(f.getName().endsWith(".jpg")||f.getName().equals("frames.jsonl")||f.getName().equals("keyframes-truncated"))){
            zip.putNextEntry(new ZipEntry("manual-frames/"+f.getName()));try(InputStream in=new FileInputStream(f)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)zip.write(b,0,n);}zip.closeEntry();
        }
    }
    static void committed(Context c,String id)throws IOException{
        if(id==null)return;File d=directory(c,id);File[] files=d.listFiles();if(files!=null)for(File f:files)if(f.isFile()&&!f.delete())return;
        d.delete();counters.remove(id);
    }
    public static int stagedCount(Context c){File[] dirs=new File(c.getFilesDir(),"mapping-work").listFiles(File::isDirectory);return dirs==null?0:dirs.length;}
}
