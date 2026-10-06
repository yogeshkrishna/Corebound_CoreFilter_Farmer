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
    static final String DEFAULT_REPO="yogeshkrishna/Corebound_CoreFilter_Farmer";
    static final String PREFS="updates", CALLBACK="com.corefilter.farmer.UPDATE_RESULT";
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final AtomicBoolean BUSY=new AtomicBoolean();
    private static final ExecutorService INTERRUPTS=Executors.newCachedThreadPool();
    private static volatile Operation active;
    private static Operation pending;
    private UpdateManager(){}

    public static void show(Activity activity){
        if(BUSY.get()){Operation op=active;if(op!=null)op.attach(activity);return;}
        if(pending!=null){Operation done=pending;pending=null;done.deliver(activity);return;}
        String repo=prefs(activity).getString("repository",DEFAULT_REPO);
        if("yogeshkrishna/ceiling-scout".equalsIgnoreCase(repo)){repo=DEFAULT_REPO;prefs(activity).edit().putString("repository",repo).apply();}
        check(activity,repo);
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
        if(active!=null&&BUSY.get())active.attach(activity);
        else if(pending!=null){Operation done=pending;pending=null;done.deliver(activity);}
        SharedPreferences settings=prefs(activity);long waiting=settings.getLong("permissionWaitUntil",0);
        if(waiting==0)return;settings.edit().remove("permissionWaitUntil").apply();
        if(waiting<System.currentTimeMillis())return;
        if(activity.getPackageManager().canRequestPackageInstalls())install(activity);
        else Toast.makeText(activity,"Allow updates from Ceiling Scout, then check for updates again.",Toast.LENGTH_LONG).show();
    }
    public static void onPause(Activity activity){Operation op=active;if(op!=null)op.detach(activity);}
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
        if(!BUSY.compareAndSet(false,true))return;Operation op=new Operation(activity,"Downloading "+release.tag+"…");op.downloading=true;
        Context context=activity.getApplicationContext();
        try{context.startForegroundService(new Intent(context,UpdateDownloadService.class));}
        catch(RuntimeException ex){op.error(new IOException("Android could not start the update download. Please retry with Ceiling Scout open.",ex));op.finish();return;}
        WORKER.execute(()->{
            op.thread=Thread.currentThread();
            File part=new File(candidate(context).getParentFile(),"update.apk.part");
            try{
                File directory=part.getParentFile();if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot prepare the app’s update folder.");
                String expected=release.hash.isEmpty()?ReleasePolicy.checksum(readText(release.checksumUrl,65536,op),release.apkName):release.hash;
                File prepared=candidate(context);
                if(prepared.isFile()&&prepared.length()==release.bytes&&sha256(prepared).equals(expected)){
                    op.requireActive();validateArchive(context,prepared);
                    prefs(context).edit().putString("preparedHash",expected).putLong("preparedAt",System.currentTimeMillis()).apply();
                    op.ui(UpdateManager::requestInstall);return;
                }
                String identity=release.apkUrl+"\n"+expected+"\n"+release.bytes;
                SharedPreferences settings=prefs(context);
                if(!identity.equals(settings.getString("partialIdentity",""))){
                    if(part.exists()&&!part.delete())throw new IOException("Cannot reset the previous partial update.");
                    if(!settings.edit().putString("partialIdentity",identity).commit())throw new IOException("Cannot save download resume information.");
                }
                ResumableDownload.fetch(part,release.bytes,offset->open(release.apkUrl,op,offset),op);
                op.requireActive();op.progress("Download complete · verifying APK…");
                if(!sha256(part).equals(expected)){
                    if(!part.delete())throw new IOException("The APK checksum did not match. Cannot reset this download.");
                    throw new IOException("The APK checksum did not match. Nothing was installed. Retry to download a fresh copy.");
                }
                validateArchive(context,part);
                op.requireActive();File ready=candidate(context);
                if(ready.exists()&&!ready.delete())throw new IOException("Cannot replace the previous update download.");
                if(!part.renameTo(ready))throw new IOException("Cannot save the verified update.");
                prefs(context).edit().remove("partialIdentity").putString("preparedHash",expected).putLong("preparedAt",System.currentTimeMillis()).apply();
                op.ui(UpdateManager::requestInstall);
            }catch(Exception ex){op.ui(a->new AlertDialog.Builder(a).setTitle("Download paused")
                    .setMessage((ex.getMessage()==null?"The connection interrupted the update.":ex.getMessage())+"\n\nSaved download progress will be reused for this version.")
                    .setPositiveButton("Retry",(d,w)->download(a,release)).setNegativeButton("Later",null).show());}
            finally{op.thread=null;Thread.interrupted();op.finish();}
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
        MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream input=new BufferedInputStream(new FileInputStream(file),131072)){byte[] bytes=new byte[131072];int count;while((count=input.read(bytes))!=-1)digest.update(bytes,0,count);}return hex(digest.digest());
    }
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder(bytes.length*2);for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    private static HttpURLConnection open(String url,Operation op)throws Exception{
        return open(url,op,-1);
    }
    private static HttpURLConnection open(String url,Operation op,long offset)throws Exception{
        URI uri=new URI(url);
        for(int redirect=0;redirect<=5;redirect++){
            op.requireActive();if(!ReleasePolicy.allowedNetworkUrl(uri))throw new IOException("The update link left GitHub’s secure download hosts.");
            HttpURLConnection connection=(HttpURLConnection)uri.toURL().openConnection();connection.setConnectTimeout(12000);connection.setReadTimeout(12000);connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent","Ceiling-Scout-Android");connection.setRequestProperty("Accept-Encoding","identity");
            if(offset>0)connection.setRequestProperty("Range","bytes="+offset+"-");
            if("api.github.com".equals(uri.getHost())){connection.setRequestProperty("Accept","application/vnd.github+json");connection.setRequestProperty("X-GitHub-Api-Version","2022-11-28");}
            op.connection=connection;
            int status;
            try{op.requireActive();status=connection.getResponseCode();op.requireActive();}
            catch(Exception ex){connection.disconnect();throw ex;}
            if(status>=300&&status<400){String location=connection.getHeaderField("Location");connection.disconnect();if(location==null)throw new IOException("GitHub returned an empty redirect.");uri=uri.resolve(location);continue;}
            if(offset>=0)return connection; // The downloader handles ranges and retryable status codes.
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
    static String downloadStatus(){Operation op=active;return op!=null&&op.downloading?op.message:null;}
    static void cancelDownload(){Operation op=active;if(op!=null&&op.downloading)op.cancel();}
    static void failDownload(String message){Operation op=active;if(op!=null&&op.downloading){op.failure=message;op.interrupt();}}
    private static final class Operation implements ResumableDownload.Monitor{
        final Context context;WeakReference<Activity> activity=new WeakReference<>(null);AlertDialog dialog;TextView label;
        final AtomicBoolean canceled=new AtomicBoolean();private UiAction result;volatile String message;
        volatile HttpURLConnection connection;volatile Thread thread;volatile String failure;boolean downloading;
        Operation(Activity owner,String text){context=owner.getApplicationContext();message=text;active=this;attach(owner);}
        void attach(Activity owner){
            if(owner.isFinishing()||owner.isDestroyed())return;
            if(activity.get()==owner&&dialog!=null&&dialog.isShowing())return;
            if(dialog!=null)try{dialog.dismiss();}catch(RuntimeException ignored){}
            activity=new WeakReference<>(owner);LinearLayout body=Ui.column(owner);int p=Ui.dp(owner,22);body.setPadding(p,p,p,p);label=Ui.text(owner,message,14,Ui.INK);body.addView(label);ProgressBar progress=new ProgressBar(owner);body.addView(progress,new LinearLayout.LayoutParams(-1,Ui.dp(owner,48)));
            dialog=new AlertDialog.Builder(owner).setTitle("Ceiling Scout update").setView(body).setNegativeButton("Cancel",(d,w)->cancel()).setCancelable(false).create();dialog.show();
        }
        void detach(Activity owner){if(activity.get()!=owner)return;if(dialog!=null)try{dialog.dismiss();}catch(RuntimeException ignored){}dialog=null;label=null;activity=new WeakReference<>(null);}
        void cancel(){canceled.set(true);message="Canceling · downloaded progress is saved";interrupt();}
        void interrupt(){Thread running=thread;if(running!=null)running.interrupt();HttpURLConnection socket=connection;if(socket!=null)INTERRUPTS.execute(socket::disconnect);}
        @Override public void check()throws InterruptedIOException{requireActive();}
        void requireActive()throws InterruptedIOException{if(failure!=null)throw new InterruptedIOException(failure);if(canceled.get()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Update canceled.");}
        @Override public void progress(String text){message=text;MAIN.post(()->{if(!canceled.get()&&dialog!=null&&dialog.isShowing())label.setText(text);});}
        void ui(UiAction action){result=action;}
        void error(Exception exception){ui(a->errorDialog(a,exception.getMessage()==null?"Could not complete the update. Check your connection and retry.":exception.getMessage()));}
        void deliver(Activity owner){if(!canceled.get()&&result!=null&&!owner.isFinishing()&&!owner.isDestroyed())result.run(owner);}
        void finish(){MAIN.post(()->{if(active==this){active=null;BUSY.set(false);}Activity owner=activity.get();if(dialog!=null)try{dialog.dismiss();}catch(RuntimeException ignored){}dialog=null;label=null;
            if(downloading)context.stopService(new Intent(context,UpdateDownloadService.class));
            if(!canceled.get()&&result!=null){if(owner!=null&&!owner.isFinishing()&&!owner.isDestroyed())deliver(owner);else pending=this;}
        });}
    }
}
