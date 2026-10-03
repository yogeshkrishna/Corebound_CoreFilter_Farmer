package com.corefilter.farmer;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.*;
import java.io.*;
import java.lang.ref.WeakReference;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit, user-initiated updates. No screenshots or gameplay data enter this path. */
public final class UpdateManager {
    static final String DEFAULT_REPO="yogeshkrishna/ceiling-scout";
    static final String PREFS="updates", CALLBACK="com.corefilter.farmer.UPDATE_RESULT";
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final AtomicBoolean BUSY=new AtomicBoolean();
    private UpdateManager(){}

    public static void show(Activity activity){
        if(BUSY.get()){Toast.makeText(activity,"An update check or download is already running.",Toast.LENGTH_SHORT).show();return;}
        check(activity,prefs(activity).getString("repository",DEFAULT_REPO));
    }
    private static void configure(Activity activity){
        LinearLayout content=Ui.column(activity);int pad=Ui.dp(activity,20);content.setPadding(pad,pad,pad,pad);
        content.addView(Ui.text(activity,"Updates come from your public GitHub Releases. Android asks you to confirm installation. Your build and calibration stay saved.",14,Ui.MUTED));
        EditText repo=new EditText(activity);repo.setSingleLine();repo.setText(prefs(activity).getString("repository",DEFAULT_REPO));repo.setHint("owner/repository");repo.setSelectAllOnFocus(true);content.addView(repo);
        content.addView(Ui.text(activity,"The updater contacts GitHub. Game screenshots stay on this phone.",12,Ui.MUTED));
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Update source").setView(content).setPositiveButton("Save & check",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{String slug=ReleasePolicy.repository(repo.getText().toString());prefs(activity).edit().putString("repository",slug).apply();dialog.dismiss();check(activity,slug);}
            catch(IllegalArgumentException ex){repo.setError(ex.getMessage());}
        }));dialog.show();
    }
    public static void onResume(Activity activity){
        SharedPreferences settings=prefs(activity);long waiting=settings.getLong("permissionWaitUntil",0);
        if(waiting==0)return;settings.edit().remove("permissionWaitUntil").apply();
        if(waiting<System.currentTimeMillis())return;
        if(activity.getPackageManager().canRequestPackageInstalls())install(activity);
        else Toast.makeText(activity,"Allow updates from Ceiling Scout, then check for updates again.",Toast.LENGTH_LONG).show();
    }
    private static SharedPreferences prefs(Context context){return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    private static File candidate(Context context){return new File(new File(context.getFilesDir(),"updates"),"update.apk");}
    private static void pauseFarmer(){if(FarmerService.instance!=null)FarmerService.instance.pause("Checking app update");}

    private static void check(Activity activity,String repo){
        if(!BUSY.compareAndSet(false,true))return;pauseFarmer();Operation op=new Operation(activity,"Checking GitHub…");
        WORKER.execute(()->{
            try{
                PackageInfo installed=activity.getPackageManager().getPackageInfo(activity.getPackageName(),0);
                String json=readText("https://api.github.com/repos/"+repo+"/releases/latest",2*1024*1024,op);
                ReleasePolicy.Release release=ReleasePolicy.parse(json,repo,installed.versionName);
                op.ui(a->{
                    if(release==null)new AlertDialog.Builder(a).setTitle("You’re up to date").setMessage("Ceiling Scout "+installed.versionName+" is the newest published version.").setPositiveButton("Done",null).setNeutralButton("Update source",(d,w)->configure(a)).show();
                    else{
                        String details=release.tag+" · "+String.format(Locale.ROOT,"%.1f MB",release.bytes/1048576.0)+"\n\n"+(release.notes.isEmpty()?"A newer Ceiling Scout build is available.":release.notes);
                        new AlertDialog.Builder(a).setTitle("Update available").setMessage(details).setPositiveButton("Download & install",(d,w)->download(a,release)).setNegativeButton("Later",null).setNeutralButton("Update source",(d,w)->configure(a)).show();
                    }
                });
            }catch(Exception ex){op.error(ex);}finally{op.finish();}
        });
    }
    private static void download(Activity activity,ReleasePolicy.Release release){
        if(!BUSY.compareAndSet(false,true))return;Operation op=new Operation(activity,"Downloading "+release.tag+"…");
        WORKER.execute(()->{
            File part=new File(candidate(activity).getParentFile(),"update.apk.part");
            try{
                File directory=part.getParentFile();if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot prepare the app’s update folder.");
                String expected=release.hash.isEmpty()?ReleasePolicy.checksum(readText(release.checksumUrl,65536,op),release.apkName):release.hash;
                MessageDigest digest=MessageDigest.getInstance("SHA-256");long received=0;long lastProgress=0;
                HttpURLConnection connection=open(release.apkUrl,op);
                try(InputStream input=connection.getInputStream();OutputStream output=new FileOutputStream(part)){
                    byte[] buffer=new byte[32768];int read;
                    while((read=input.read(buffer))!=-1){op.requireActive();received+=read;if(received>ReleasePolicy.MAX_APK_BYTES||received>release.bytes)throw new IOException("The download is larger than its release metadata.");output.write(buffer,0,read);digest.update(buffer,0,read);
                        long now=SystemClock.elapsedRealtime();if(now-lastProgress>250){lastProgress=now;int percent=(int)(received*100/release.bytes);op.progress("Downloading "+release.tag+" · "+percent+"%");}
                    }
                    output.flush();
                }finally{connection.disconnect();}
                if(received!=release.bytes)throw new IOException("The APK download was incomplete. Check your connection and retry.");
                if(!hex(digest.digest()).equals(expected))throw new IOException("The APK checksum did not match. Nothing was installed.");
                validateArchive(activity,part);
                op.requireActive();File ready=candidate(activity);
                if(ready.exists()&&!ready.delete())throw new IOException("Cannot replace the previous update download.");
                if(!part.renameTo(ready))throw new IOException("Cannot save the verified update.");
                prefs(activity).edit().putString("preparedHash",expected).putLong("preparedAt",System.currentTimeMillis()).apply();
                op.ui(UpdateManager::requestInstall);
            }catch(Exception ex){op.error(ex);}finally{if(part.exists())part.delete();op.finish();}
        });
    }
    private static void requestInstall(Activity activity){
        if(activity.getPackageManager().canRequestPackageInstalls()){install(activity);return;}
        new AlertDialog.Builder(activity).setTitle("Allow app updates once")
            .setMessage("Android needs permission for Ceiling Scout to open its update installer. Enable “Allow from this source”, then return here. Your downloaded APK is already verified.")
            .setPositiveButton("Open settings",(d,w)->{
                prefs(activity).edit().putLong("permissionWaitUntil",System.currentTimeMillis()+15*60*1000).apply();
                try{activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName())));}
                catch(ActivityNotFoundException ex){prefs(activity).edit().remove("permissionWaitUntil").apply();errorDialog(activity,"This phone has no installer permission screen. Open App info → Install unknown apps, then check again.");}
            }).setNegativeButton("Later",null).show();
    }
    private static void install(Activity activity){
        if(!BUSY.compareAndSet(false,true))return;Operation op=new Operation(activity,"Preparing Android installer…");
        WORKER.execute(()->{
            int sessionId=-1;PackageInstaller installer=activity.getPackageManager().getPackageInstaller();
            try{
                File apk=candidate(activity);String hash=prefs(activity).getString("preparedHash","");long age=System.currentTimeMillis()-prefs(activity).getLong("preparedAt",0);
                if(!apk.isFile()||hash.isEmpty()||age<0||age>24*60*60*1000)throw new IOException("The prepared download expired. Check for updates again.");
                if(!sha256(apk).equals(hash))throw new IOException("The prepared APK changed. Check for updates again.");validateArchive(activity,apk);op.requireActive();
                PackageInstaller.SessionParams params=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(activity.getPackageName());params.setSize(apk.length());params.setInstallReason(PackageManager.INSTALL_REASON_USER);
                if(Build.VERSION.SDK_INT>=31)params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
                sessionId=installer.createSession(params);
                try(PackageInstaller.Session session=installer.openSession(sessionId)){
                    try(InputStream input=new FileInputStream(apk);OutputStream output=session.openWrite("base.apk",0,apk.length())){
                        byte[] buffer=new byte[32768];int read;while((read=input.read(buffer))!=-1){op.requireActive();output.write(buffer,0,read);}session.fsync(output);
                    }
                    String nonce=UUID.randomUUID().toString();prefs(activity).edit().putString("installNonce",nonce).putInt("installSession",sessionId).apply();
                    Intent callback=new Intent(activity,UpdateInstallReceiver.class).setAction(CALLBACK).putExtra("nonce",nonce);
                    int flags=PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE;
                    PendingIntent pending=PendingIntent.getBroadcast(activity,sessionId,callback,flags);session.commit(pending.getIntentSender());
                }
                sessionId=-1;op.ui(a->Toast.makeText(a,"Confirm the update in Android’s installer.",Toast.LENGTH_LONG).show());
            }catch(Exception ex){if(sessionId>=0)installer.abandonSession(sessionId);op.error(ex);}finally{op.finish();}
        });
    }
    static void validateArchive(Context context,File apk) throws Exception{
        PackageManager pm=context.getPackageManager();PackageInfo installed=pm.getPackageInfo(context.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo archive=pm.getPackageArchiveInfo(apk.getAbsolutePath(),PackageManager.GET_SIGNING_CERTIFICATES);
        if(archive==null)throw new IOException("Android could not read the downloaded APK.");
        ReleasePolicy.validateIdentity(context.getPackageName(),installed.getLongVersionCode(),signers(installed),archive.packageName,archive.getLongVersionCode(),signers(archive));
    }
    private static List<String> signers(PackageInfo info)throws Exception{
        if(info.signingInfo==null)return Collections.emptyList();List<String> result=new ArrayList<>();
        for(Signature signature:info.signingInfo.getApkContentsSigners())result.add(hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));
        return result;
    }
    static String sha256(File file)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream input=new FileInputStream(file)){byte[] bytes=new byte[32768];int count;while((count=input.read(bytes))!=-1)digest.update(bytes,0,count);}return hex(digest.digest());
    }
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder(bytes.length*2);for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    private static HttpURLConnection open(String url,Operation op)throws Exception{
        URI uri=new URI(url);
        for(int redirect=0;redirect<=5;redirect++){
            op.requireActive();if(!ReleasePolicy.allowedNetworkUrl(uri))throw new IOException("The update link left GitHub’s secure download hosts.");
            HttpURLConnection connection=(HttpURLConnection)uri.toURL().openConnection();connection.setConnectTimeout(15000);connection.setReadTimeout(20000);connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent","Ceiling-Scout-Android");connection.setRequestProperty("Accept","application/vnd.github+json");connection.setRequestProperty("X-GitHub-Api-Version","2022-11-28");
            int status=connection.getResponseCode();
            if(status>=300&&status<400){String location=connection.getHeaderField("Location");connection.disconnect();if(location==null)throw new IOException("GitHub returned an empty redirect.");uri=uri.resolve(location);continue;}
            if(status!=200){connection.disconnect();if(status==404)throw new IOException("No published release was found. Check the repository name or publish its first release.");if(status==403||status==429)throw new IOException("GitHub is limiting update checks. Try again later.");throw new IOException("GitHub update request failed ("+status+").");}
            return connection;
        }
        throw new IOException("GitHub returned too many download redirects.");
    }
    private static String readText(String url,int limit,Operation op)throws Exception{
        HttpURLConnection connection=open(url,op);
        try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int read;while((read=input.read(buffer))!=-1){op.requireActive();if(output.size()+read>limit)throw new IOException("GitHub returned too much update metadata.");output.write(buffer,0,read);}return output.toString(StandardCharsets.UTF_8.name());
        }finally{connection.disconnect();}
    }
    private static void errorDialog(Activity activity,String message){new AlertDialog.Builder(activity).setTitle("Update not installed").setMessage(message).setPositiveButton("Done",null).setNeutralButton("Update source",(d,w)->configure(activity)).show();}
    private interface UiAction{void run(Activity activity);}
    private static final class Operation{
        final WeakReference<Activity> activity;final AlertDialog dialog;final TextView label;final AtomicBoolean canceled=new AtomicBoolean();private UiAction result;
        Operation(Activity owner,String message){activity=new WeakReference<>(owner);LinearLayout body=Ui.column(owner);int p=Ui.dp(owner,22);body.setPadding(p,p,p,p);label=Ui.text(owner,message,14,Ui.INK);body.addView(label);ProgressBar progress=new ProgressBar(owner);body.addView(progress,new LinearLayout.LayoutParams(-1,Ui.dp(owner,48)));dialog=new AlertDialog.Builder(owner).setTitle("Ceiling Scout update").setView(body).setNegativeButton("Cancel",(d,w)->canceled.set(true)).setCancelable(false).create();dialog.show();}
        void requireActive()throws InterruptedIOException{if(canceled.get()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Update canceled.");}
        void progress(String message){MAIN.post(()->{if(!canceled.get()&&dialog.isShowing())label.setText(message);});}
        void ui(UiAction action){result=action;}
        void error(Exception exception){ui(a->errorDialog(a,exception.getMessage()==null?"Could not complete the update. Check your connection and retry.":exception.getMessage()));}
        void finish(){MAIN.post(()->{BUSY.set(false);Activity owner=activity.get();if(owner!=null&&!owner.isDestroyed()&&dialog.isShowing())dialog.dismiss();if(!canceled.get()&&result!=null&&owner!=null&&!owner.isFinishing()&&!owner.isDestroyed())result.run(owner);});}
    }
}
