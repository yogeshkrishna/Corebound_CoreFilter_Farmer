package com.corefilter.farmer;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.*;

/** Saved runs and native exports. Viewing never loads a full-size atlas into memory. */
public final class OfflineMapsActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Map<String,TextView> statuses=new HashMap<>();private final Map<String,Button> builds=new HashMap<>(),previews=new HashMap<>();
    private final ExecutorService images=Executors.newSingleThreadExecutor();private String exportRecording;private int exportSection;
    private final Runnable refresh=new Runnable(){public void run(){refreshStatuses();handler.postDelayed(this,1000);}};
    @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().setStatusBarColor(Ui.BG);getWindow().setNavigationBarColor(Ui.BG);
        if(state!=null){exportRecording=state.getString("exportRecording");exportSection=state.getInt("exportSection");}home();
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED&&!getSharedPreferences("offline",0).getBoolean("notificationsAsked",false)){
            getSharedPreferences("offline",0).edit().putBoolean("notificationsAsked",true).apply();requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},96);}}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("exportRecording",exportRecording);state.putInt("exportSection",exportSection);}
    @Override public void onResume(){super.onResume();handler.post(refresh);}
    @Override public void onPause(){handler.removeCallbacks(refresh);super.onPause();}
    @Override public void onDestroy(){images.shutdown();super.onDestroy();}
    private void home(){FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Ui.BG);getWindow().setDecorFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener((view,insets)->{Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
        ScrollView scroll=new ScrollView(this);LinearLayout list=Ui.column(this);int pad=Ui.dp(this,20);list.setPadding(pad,pad,pad,pad);scroll.addView(list);root.addView(scroll,new FrameLayout.LayoutParams(-1,-1));setContentView(root);root.requestApplyInsets();
        list.addView(Ui.text(this,"ON THIS PHONE / OFFLINE",11,Ui.MINT));TextView title=Ui.text(this,"Your recorded maps",26,Ui.INK);title.setTypeface(null,Typeface.BOLD);list.addView(title);
        list.addView(Ui.text(this,"Stop recording, then Build. Processing can continue while you use other apps. Pause and resume whenever needed. No Wi-Fi or laptop required.",14,Ui.MUTED));
        list.addView(Ui.secondaryButton(this,"Pause processing",()->OfflineMapService.pause(this)));
        List<File> recordings=RecordingStore.recordings(this);if(recordings.isEmpty())list.addView(Ui.text(this,"No recordings yet. Return home and tap Record a map, then play through the entire level.",16,Ui.MUTED));
        for(File f:recordings)try{JSONObject record=RecordingStore.read(new File(f,"recording.json"));LinearLayout card=Ui.card(this);
            Ui.title(card,DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(new Date(record.optLong("created"))));
            TextView status=Ui.text(this,"",13,Ui.MUTED);statuses.put(f.getName(),status);card.addView(status);
            Button build=Ui.button(this,"Build / Resume map",()->build(f));build.setTag("build-"+f.getName());builds.put(f.getName(),build);card.addView(build);
            Button preview=Ui.secondaryButton(this,"View map & save PNG",()->sections(f));previews.put(f.getName(),preview);card.addView(preview);
            card.addView(Ui.secondaryButton(this,"Share original recording",()->share(f)));list.addView(card);
        }catch(Exception ignored){}
        list.addView(Ui.secondaryButton(this,"Back to Ceiling Scout",this::finish));refreshStatuses();}
    private void refreshStatuses(){for(File f:RecordingStore.recordings(this))try{JSONObject record=RecordingStore.read(new File(f,"recording.json"));String state=record.optString("state");
        if(state.equals("recording")&&!LiveCaptureService.active()&&!LiveCaptureService.finishing()){
            record.put("state","recorded").put("message","Recording ended. Saved images are ready to build.").put("frames",RecordingStore.frames(f).size());RecordingStore.write(new File(f,"recording.json"),record);state="recorded";}
        if(state.equals("processing")&&!OfflineMapService.active()){record.put("state",new File(f,"map.json").isFile()?"complete":"paused").put("message","Processing interrupted. Build / Resume continues from saved analysis.");RecordingStore.write(new File(f,"recording.json"),record);}
        TextView status=statuses.get(f.getName());if(status!=null)status.setText(record.optInt("frames")+" original images · "+String.format(Locale.ROOT,"%.1f MB",record.optLong("bytes")/1048576.0)+"\n"+record.optString("message"));
        Button build=builds.get(f.getName());if(build!=null)build.setEnabled(!LiveCaptureService.active()&&!LiveCaptureService.finishing()&&!OfflineMapService.active());
        Button preview=previews.get(f.getName());if(preview!=null)preview.setEnabled(new File(f,"map.json").isFile()&&!OfflineMapService.active());
    }catch(Exception ignored){}}
    private void build(File f){if(LiveCaptureService.active()||LiveCaptureService.finishing()||OfflineMapService.active()){Toast.makeText(this,"Stop recording or pause the current job first",Toast.LENGTH_LONG).show();return;}
        try{startForegroundService(new Intent(this,OfflineMapService.class).putExtra("recording",f.getName()));refreshStatuses();}catch(RuntimeException e){error(e);}}
    private void sections(File f){try{JSONObject map=RecordingStore.read(new File(f,"map.json"));JSONArray sections=map.getJSONArray("sections");String[] labels=new String[sections.length()];
        for(int i=0;i<labels.length;i++){JSONObject s=sections.getJSONObject(i);JSONArray b=s.optJSONArray("bounds");labels[i]="Section "+(i+1)+(b==null?" · no pixels":" · "+(b.getInt(2)-b.getInt(0))+" × "+(b.getInt(3)-b.getInt(1))+" pixels");}
        new AlertDialog.Builder(this).setTitle(labels.length>1?"Separate sections · uncertain joins":"Your native map").setItems(labels,(d,which)->preview(f,which)).setNegativeButton("Close",null).show();
    }catch(Exception e){error(e);}}
    private void preview(File f,int section){LinearLayout body=Ui.column(this);int pad=Ui.dp(this,16);body.setPadding(pad,pad,pad,pad);body.setBackgroundColor(Ui.BG);
        body.addView(Ui.text(this,"Drag to pan · pinch to zoom. This display preview is fitted; PNG export keeps every original pixel. Transparent areas were not observed.",13,Ui.MUTED));
        MapPreview view=new MapPreview(this);int height=Math.max(Ui.dp(this,80),Math.min(Ui.dp(this,350),getResources().getDisplayMetrics().heightPixels-Ui.dp(this,190)));body.addView(view,new LinearLayout.LayoutParams(-1,height));
        ScrollView scroll=new ScrollView(this);scroll.addView(body);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Section "+(section+1)).setView(scroll).setPositiveButton("Save native PNG",(d,w)->export(f,section)).setNegativeButton("Close",null).create();dialog.setOnDismissListener(d->view.dispose());dialog.show();
        images.execute(()->{Bitmap bitmap=BitmapFactory.decodeFile(new File(f,"map-v1/preview-"+section+".png").getPath());handler.post(()->{if(dialog.isShowing())view.image(bitmap);else if(bitmap!=null)bitmap.recycle();});});}
    private void export(File f,int section){if(OfflineMapService.active()){Toast.makeText(this,"Pause the current job first",Toast.LENGTH_LONG).show();return;}exportRecording=f.getName();exportSection=section;
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/png").putExtra(Intent.EXTRA_TITLE,"Ceiling-Scout-"+f.getName()+"-section-"+(section+1)+".png");startActivityForResult(i,91);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=91||result!=RESULT_OK||data==null||exportRecording==null)return;
        Uri uri=data.getData();if(uri==null)return;try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_WRITE_URI_PERMISSION);}catch(SecurityException ignored){}
        try{startForegroundService(new Intent(this,OfflineMapService.class).setAction("export").putExtra("recording",exportRecording).putExtra("section",exportSection).putExtra("destination",uri.toString()));}catch(RuntimeException e){error(e);}}
    private void share(File f){if(LiveCaptureService.active()||OfflineMapService.active()){Toast.makeText(this,"Stop recording and pause processing before sharing",Toast.LENGTH_LONG).show();return;}
        new AlertDialog.Builder(this).setTitle("Share original recording").setMessage("This includes original game PNGs, capture metadata and alignment data. It can be large. Choose where to send it after the ZIP is prepared.")
                .setPositiveButton("Prepare ZIP",(d,w)->{Toast.makeText(this,"Preparing recording…",Toast.LENGTH_SHORT).show();images.execute(()->{try{File zip=new File(getCacheDir(),"recording-"+f.getName()+".zip");
                    try(java.util.zip.ZipOutputStream out=new java.util.zip.ZipOutputStream(new FileOutputStream(zip))){pack(f,f,out);}
                    handler.post(()->{if(isFinishing()||isDestroyed())return;Uri uri=OfflineFileProvider.uri(this,zip);startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"Share recording"));});
                }catch(Exception e){handler.post(()->error(e));}});}).setNegativeButton("Cancel",null).show();}
    private void pack(File root,File file,java.util.zip.ZipOutputStream out)throws IOException{if(file.isDirectory()){File[] children=file.listFiles();if(children!=null)for(File child:children)pack(root,child,out);}else{
        String name=root.toPath().relativize(file.toPath()).toString().replace('\\','/');out.putNextEntry(new java.util.zip.ZipEntry(name));try(InputStream in=new FileInputStream(file)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}out.closeEntry();}}
    private void error(Exception e){new AlertDialog.Builder(this).setTitle("Map action unavailable").setMessage(e.getMessage()).setPositiveButton("Close",null).show();}
    private static final class MapPreview extends View {
        private Bitmap bitmap;private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);private final Matrix matrix=new Matrix();private final ScaleGestureDetector scale;
        private float x,y;private boolean disposed;
        MapPreview(Context c){super(c);setBackgroundColor(0xff18252a);scale=new ScaleGestureDetector(c,new ScaleGestureDetector.SimpleOnScaleGestureListener(){public boolean onScale(ScaleGestureDetector d){matrix.postScale(d.getScaleFactor(),d.getScaleFactor(),d.getFocusX(),d.getFocusY());invalidate();return true;}});}
        void image(Bitmap b){if(disposed){if(b!=null)b.recycle();return;}bitmap=b;fit();}
        void fit(){if(bitmap==null||getWidth()==0)return;float s=Math.min((float)getWidth()/bitmap.getWidth(),(float)getHeight()/bitmap.getHeight());matrix.setScale(s,s);matrix.postTranslate((getWidth()-bitmap.getWidth()*s)/2,(getHeight()-bitmap.getHeight()*s)/2);invalidate();}
        @Override protected void onSizeChanged(int w,int h,int ow,int oh){fit();}
        @Override protected void onDraw(Canvas c){super.onDraw(c);paint.setColor(0xff24373c);for(int y=0;y<getHeight();y+=24)for(int x=0;x<getWidth();x+=24)if((x/24+y/24)%2==0)c.drawRect(x,y,x+24,y+24,paint);if(bitmap!=null)c.drawBitmap(bitmap,matrix,paint);}
        @Override public boolean onTouchEvent(MotionEvent event){scale.onTouchEvent(event);if(event.getActionMasked()==MotionEvent.ACTION_DOWN){x=event.getX();y=event.getY();return true;}if(event.getActionMasked()==MotionEvent.ACTION_MOVE&&!scale.isInProgress()&&event.getPointerCount()==1){matrix.postTranslate(event.getX()-x,event.getY()-y);x=event.getX();y=event.getY();invalidate();}if(event.getActionMasked()==MotionEvent.ACTION_UP)performClick();return true;}
        @Override public boolean performClick(){super.performClick();return true;}
        void dispose(){disposed=true;if(bitmap!=null){bitmap.recycle();bitmap=null;}}
    }
}
