package com.corefilter.farmer;

import android.content.Context;
import java.io.File;
import java.io.IOException;

/** One-time removal of obsolete archives inside this app's own private directory. */
final class LegacyCaptureCleanup {
    static void start(Context context){
        if(context.getSharedPreferences("migration",0).getBoolean("liveStudio",false))return;
        Context app=context.getApplicationContext();
        new Thread(()->{try{
            File root=app.getFilesDir().getCanonicalFile();
            for(String name:new String[]{"map-queue","mapping-work"})remove(new File(root,name),root);
            app.getSharedPreferences("migration",0).edit().putBoolean("liveStudio",true).apply();
        }catch(IOException ignored){/* Retry the bounded migration on next launch. */}},"obsolete-private-map-cleanup").start();
    }
    private static void remove(File file,File root)throws IOException{
        if(!file.exists())return;
        File resolved=file.getCanonicalFile();
        if(!resolved.toPath().startsWith(root.toPath())||resolved.equals(root))throw new IOException("Outside private archive storage");
        if(file.isDirectory()){File[] files=file.listFiles();if(files==null)throw new IOException("Cannot list private archive");for(File child:files)remove(child,root);}
        if(!file.delete())throw new IOException("Cannot remove obsolete private archive");
    }
}
