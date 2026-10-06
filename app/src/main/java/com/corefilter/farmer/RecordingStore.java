package com.corefilter.farmer;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Each frame commits pixels first, then metadata. Incomplete writes are never inputs. */
final class RecordingStore {
    static File root(Context c) { return new File(c.getFilesDir(), "offline-recordings"); }
    static File directory(Context c, String id) throws IOException {
        if(id==null || !id.matches("[0-9]{13}-[a-f0-9]{8}")) throw new IOException("Invalid recording");
        File f=new File(root(c),id); if(!f.isDirectory()) throw new IOException("Recording no longer exists"); return f;
    }
    static File create(Context c) throws Exception {
        File f=new File(root(c), System.currentTimeMillis()+"-"+UUID.randomUUID().toString().substring(0,8));
        if(!new File(f,"frames").mkdirs()) throw new IOException("Could not create recording");
        write(new File(f,"recording.json"),new JSONObject().put("schema",1).put("id",f.getName())
                .put("created",System.currentTimeMillis()).put("state","recording").put("frames",0)
                .put("bytes",0).put("message","Recording original game images"));
        c.getSharedPreferences("offline",0).edit().putString("latest",f.getName()).apply(); return f;
    }
    static void saveFrame(File f,long number,Bitmap image,JSONObject meta) throws Exception {
        if(f.getUsableSpace()<256L*1024*1024) throw new IOException("Storage nearly full. Recording stopped; saved images are safe.");
        String name=String.format(Locale.ROOT,"%08d",number); File png=new File(f,"frames/"+name+".png");
        AtomicFile atom=new AtomicFile(png); FileOutputStream stream=atom.startWrite();
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try { if(!image.compress(Bitmap.CompressFormat.PNG,100,new DigestOutputStream(stream,digest)))throw new IOException("Could not save PNG"); atom.finishWrite(stream); }
        catch(Exception e){atom.failWrite(stream);throw e;}
        StringBuilder hash=new StringBuilder();for(byte value:digest.digest())hash.append(String.format(Locale.ROOT,"%02x",value&255));
        meta.put("file",name+".png").put("bytes",png.length()).put("sha256",hash.toString());
        write(new File(f,"frames/"+name+".json"),meta);
        JSONObject record=read(new File(f,"recording.json"));
        record.put("frames",record.optInt("frames")+1).put("bytes",record.optLong("bytes")+png.length());
        write(new File(f,"recording.json"),record);
    }
    static void finish(File f,String message) throws Exception {
        update(f,"recorded",message); // An accepted final frame has finished before this runs.
    }
    static synchronized void update(File f,String state,String message) throws Exception {
        JSONObject record=read(new File(f,"recording.json")); record.put("state",state).put("message",message);
        write(new File(f,"recording.json"),record);
    }
    static List<File> frames(File f) {
        File[] files=new File(f,"frames").listFiles((d,n)->n.matches("[0-9]{8}\\.json"));
        List<File> result=new ArrayList<>(); if(files!=null) for(File file:files)
            if(new File(file.getParentFile(),file.getName().replace(".json",".png")).isFile()) result.add(file);
        result.sort(Comparator.comparing(File::getName)); return result;
    }
    static List<File> recordings(Context c) {
        File[] files=root(c).listFiles(File::isDirectory); List<File> result=new ArrayList<>();
        if(files!=null)for(File f:files)if(new File(f,"recording.json").isFile())result.add(f);
        result.sort((a,b)->b.getName().compareTo(a.getName()));return result;
    }
    static JSONObject read(File file) throws Exception {
        try(InputStream in=new FileInputStream(file);ByteArrayOutputStream data=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)data.write(buffer,0,n);return new JSONObject(data.toString(StandardCharsets.UTF_8.name()));}
    }
    static synchronized void write(File file,JSONObject json) throws IOException {
        File pending=new File(file.getPath()+".pending");
        try(FileOutputStream out=new FileOutputStream(pending)){out.write(json.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        try{Files.move(pending.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException e){Files.move(pending.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING);}
    }
}
