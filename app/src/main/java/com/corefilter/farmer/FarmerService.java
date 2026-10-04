package com.corefilter.farmer;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.*;
import android.graphics.*;
import android.hardware.HardwareBuffer;
import android.os.*;
import android.view.*;
import android.view.accessibility.*;
import android.widget.*;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.*;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.corefilter.farmer.engine.FarmEngine;
import com.corefilter.farmer.engine.ScreenInterpreter;
import com.corefilter.farmer.maps.MapArchiveStore;
import com.corefilter.farmer.vision.PixelVision;
import com.corefilter.farmer.vision.TemporalVision;
import java.util.*;
import java.util.concurrent.*;

/** Local-only Android adapter. The policy is independently tested plain Java. */
public final class FarmerService extends AccessibilityService {
    public static FarmerService instance;
    static final String GAME="com.Overcurve.Corebound",STORE="com.android.vending";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ExecutorService mapWriter=Executors.newSingleThreadExecutor();
    private final ArrayDeque<FarmEngine.RunMap> mapBacklog=new ArrayDeque<>();
    private boolean mapWriteBusy;
    private final ArrayDeque<String> logs=new ArrayDeque<>();
    private TextRecognizer recognizer;
    private Profile profile;private FarmEngine engine;private WindowManager wm;
    private TemporalVision temporal=new TemporalVision();
    private final FrameMailbox<Observation> pendingFrames=new FrameMailbox<>();
    private static final class Observation {
        final PixelVision.Result vision;final String pkg;final long capturedAt;final int ticket;
        final List<FarmEngine.Token> tokens;
        final long textCapturedAt;
        Observation(PixelVision.Result vision,String pkg,long capturedAt,int ticket,List<FarmEngine.Token> tokens,long textCapturedAt){this.vision=vision;this.pkg=pkg;this.capturedAt=capturedAt;this.ticket=ticket;this.tokens=new ArrayList<>(tokens);this.textCapturedAt=textCapturedAt;}
    }
    private LinearLayout bar;private TextView status;private Button runButton;private View captureMarker;private WindowManager.LayoutParams barParams;
    private boolean overlayWanted=true,compatibilityCapture=false,previewRequested=false;
    private long lastOcrAt=0,lastScreenshotAt=0,earliestGameplayObservationAt=0;private int intervalErrors=0,slowFrames=0;
    private boolean hudOcrBusy;
    private List<FarmEngine.Token> hudTokens=Collections.emptyList();
    private long hudCapturedAt=-1;
    private View calibration;private CapturePreview preview;private boolean running=false,observe=false,inFlight=false,gestureBusy=false,destroyed=false;
    private int generation=0,captureErrors=0,screenW=0,screenH=0;private long nextCapture=0;
    private String lastReason="Ready",lastPackage="";private long lastLogged=0;
    private final Runnable ticker=new Runnable(){public void run(){if(destroyed)return;if(running&&!inFlight&&SystemClock.elapsedRealtime()>=nextCapture)capture(false);handler.postDelayed(this,50);}};

    @Override protected void onServiceConnected(){instance=this;wm=(WindowManager)getSystemService(WINDOW_SERVICE);compatibilityCapture=getSharedPreferences("capture",0).getBoolean("compatibility",false);recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);reloadProfile();showOverlay();handler.post(ticker);log("Connected. No touches until Run.");}
    @Override public void onAccessibilityEvent(AccessibilityEvent e){}
    @Override public void onInterrupt(){pause("Android interrupted controls");}
    @Override public void onDestroy(){destroyed=true;pause("Service stopped");handler.removeCallbacksAndMessages(null);hideCalibration();hidePreview();if(bar!=null){wm.removeView(bar);bar=null;}if(recognizer!=null)recognizer.close();worker.shutdownNow();mapWriter.shutdown();instance=null;super.onDestroy();}
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration){super.onConfigurationChanged(configuration);if(bar!=null){Rect bounds=wm.getMaximumWindowMetrics().getBounds();barParams.x=Math.max(0,Math.min(bounds.width()-barParams.width,barParams.x));barParams.y=Math.max(0,Math.min(bounds.height()-barParams.height,barParams.y));wm.updateViewLayout(bar,barParams);}if(running){generation++;pendingFrames.clear();temporal=new TemporalVision();nextCapture=0;}}
    public void reloadProfile(){profile=Profile.load(this);engine=new FarmEngine(profile.config());pendingFrames.clear();temporal=new TemporalVision();earliestGameplayObservationAt=0;hudTokens=Collections.emptyList();hudCapturedAt=-1;setStatus("Ready · "+profile.name);}
    public void pause(String reason){running=false;observe=false;previewRequested=false;generation++;pendingFrames.clear();if(engine!=null){engine.stop();FarmEngine.RunMap report;while((report=engine.takeFinishedMap())!=null)mapBacklog.addLast(report);writeNextMap();}cancelTouches();log(reason);setStatus("Paused · "+reason);}
    private void cancelTouches(){/* Already-issued batches release in at most 700 ms; queued batches are cancelled by generation. */}
    public void showOverlay(){overlayWanted=true;if(bar!=null){restoreBar();return;}
        bar=new LinearLayout(this);bar.setOrientation(LinearLayout.HORIZONTAL);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(Ui.dp(this,10),0,Ui.dp(this,4),0);bar.setBackground(Ui.bg(Ui.BG,Ui.dp(this,24)));bar.setElevation(Ui.dp(this,8));
        captureMarker=new CaptureMarker(this);bar.addView(captureMarker,new LinearLayout.LayoutParams(Ui.dp(this,16),Ui.dp(this,16)));
        status=Ui.text(this,"Ready · 7 jumps",12,Ui.INK);status.setMaxLines(2);status.setEllipsize(android.text.TextUtils.TruncateAt.END);status.setPadding(Ui.dp(this,9),0,Ui.dp(this,5),0);bar.addView(status,new LinearLayout.LayoutParams(0,-1,1));
        runButton=overlayButton("Run",()->{if(running)pause("User paused");else start(false);});bar.addView(runButton,new LinearLayout.LayoutParams(Ui.dp(this,62),Ui.dp(this,44)));
        Button more=overlayButton("•••",()->{});more.setContentDescription("More controls");more.setOnClickListener(v->showMenu(more));bar.addView(more,new LinearLayout.LayoutParams(Ui.dp(this,44),Ui.dp(this,44)));
        int width=Ui.dp(this,294);Rect display=wm.getMaximumWindowMetrics().getBounds();
        barParams=new WindowManager.LayoutParams(width,Ui.dp(this,50),WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);barParams.gravity=Gravity.TOP|Gravity.LEFT;barParams.x=Math.max(0,display.width()-width-Ui.dp(this,54));barParams.y=Ui.dp(this,6);
        status.setGravity(Gravity.CENTER_VERTICAL);status.setOnTouchListener(new View.OnTouchListener(){float x,y;int sx,sy;public boolean onTouch(View v,MotionEvent e){if(e.getAction()==MotionEvent.ACTION_DOWN){x=e.getRawX();y=e.getRawY();sx=barParams.x;sy=barParams.y;return true;}if(e.getAction()==MotionEvent.ACTION_MOVE){Rect bounds=wm.getMaximumWindowMetrics().getBounds();barParams.x=Math.max(0,Math.min(bounds.width()-bar.getWidth(),sx+(int)(e.getRawX()-x)));barParams.y=Math.max(0,Math.min(bounds.height()-bar.getHeight(),sy+(int)(e.getRawY()-y)));wm.updateViewLayout(bar,barParams);return true;}if(e.getAction()==MotionEvent.ACTION_UP)v.performClick();return true;}});wm.addView(bar,barParams);
    }
    private Button overlayButton(String label,Runnable action){Button b="•••".equals(label)?Ui.secondaryButton(this,label,action):Ui.button(this,label,action);b.setTextSize(12);b.setPadding(0,0,0,0);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(0);b.setMinimumHeight(0);return b;}
    private void restoreBar(){if(bar!=null)bar.setVisibility(overlayWanted&&calibration==null&&preview==null?View.VISIBLE:View.GONE);}
    private void showMenu(View anchor){LinearLayout menu=Ui.column(this);menu.setPadding(12,8,12,8);menu.setBackground(Ui.bg(Ui.PANEL,Ui.dp(this,16)));PopupWindow popup=new PopupWindow(menu,Ui.dp(this,240),-2,true);popup.setBackgroundDrawable(Ui.bg(Ui.PANEL,Ui.dp(this,16)));popup.setOutsideTouchable(true);popup.setElevation(Ui.dp(this,10));
        menuAction(menu,popup,"Observe without touches",()->start(true));
        menuAction(menu,popup,"Build & Hookshots",()->{pause("Editing build");startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("editBuild",true));});
        menuAction(menu,popup,"Calibrate controls",this::calibrate);
        menuAction(menu,popup,"Preview captured game",this::previewCapture);
        menuAction(menu,popup,"Saved maps & laptop transfer",()->{pause("Opening saved maps");startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("openMaps",true));});
        menuAction(menu,popup,compatibilityCapture?"Capture: compatibility ✓":"Capture: clean window ✓",()->{pause("Capture mode changed");compatibilityCapture=!compatibilityCapture;getSharedPreferences("capture",0).edit().putBoolean("compatibility",compatibilityCapture).apply();setStatus(compatibilityCapture?"Compatibility capture enabled":"Clean window capture enabled");});
        menuAction(menu,popup,"Stop & hide",()->{pause("Stopped");overlayWanted=false;restoreBar();});popup.showAsDropDown(anchor,-Ui.dp(this,196),Ui.dp(this,4));
    }
    private void menuAction(LinearLayout menu,PopupWindow popup,String label,Runnable action){Button b=Ui.button(this,label,()->{popup.dismiss();handler.postDelayed(action,80);});menu.addView(b,new LinearLayout.LayoutParams(-1,Ui.dp(this,48)));}
    private void start(boolean dry){if(calibration!=null||preview!=null)return;if(!GAME.equals(foregroundPackage())){setStatus("Open Corebound first");return;}reloadProfile();generation++;engine.reset(SystemClock.elapsedRealtime());running=true;observe=dry;nextCapture=0;lastOcrAt=0;captureErrors=slowFrames=0;writeNextMap();log(dry?"Observe: predictions only, no touches":"Run started: "+profile.name);setStatus(dry?"Observing…":"Starting…");}
    private void setStatus(String s){lastReason=s;if(status!=null)status.setText(s);if(runButton!=null)runButton.setText(running?"Pause":"Run");}
    public String statusLine(){return lastReason;}
    private void log(String s){long now=SystemClock.elapsedRealtime();if(!logs.isEmpty()&&logs.peekLast().endsWith(s)&&now-lastLogged<3000)return;lastLogged=now;logs.addLast(android.text.format.DateFormat.format("HH:mm:ss",new java.util.Date())+"  "+s);while(logs.size()>80)logs.removeFirst();getSharedPreferences("session",0).edit().putString("log",logText()).apply();}
    public String logText(){StringBuilder b=new StringBuilder();for(String s:logs)b.append(s).append('\n');return b.toString();}
    private String foregroundPackage(){List<AccessibilityWindowInfo> windows=getWindows();for(AccessibilityWindowInfo w:windows){if(w.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION||(!w.isActive()&&!w.isFocused()))continue;AccessibilityNodeInfo n=w.getRoot();if(n==null)continue;CharSequence p=n.getPackageName();String s=p==null?"":p.toString();n.recycle();if(!s.equals(getPackageName()))return s;else return s;}AccessibilityNodeInfo n=getRootInActiveWindow();if(n==null)return "";String p=n.getPackageName()==null?"":n.getPackageName().toString();n.recycle();return p;}

    private void capture(boolean forCalibration){
        if(inFlight||destroyed)return;String pkg=foregroundPackage();
        if(!GAME.equals(pkg)&&!STORE.equals(pkg)){if(running)pause("Foreground changed · open Corebound");return;}
        if(((KeyguardManager)getSystemService(KEYGUARD_SERVICE)).isKeyguardLocked()){pause("Phone locked");return;}
        // Play Store commonly rotates to portrait. Its recovery needs only package provenance,
        // never an OCR screenshot or game coordinates.
        if(STORE.equals(pkg)){if(forCalibration||observe){pause("Return to Corebound");return;}long now=SystemClock.elapsedRealtime();FarmEngine.Action a=engine.next(new FarmEngine.Frame(now,pkg,"",Collections.emptyList()));setStatus(a.reason);log(a.kind+": "+a.reason);execute(a,new PixelVision.Result(),pkg,generation);nextCapture=now+1000;return;}
        final int ticket=generation;final long capturedAt=SystemClock.elapsedRealtime();final String capturePackage=pkg;final TemporalVision captureTemporal=temporal;
        if(capturedAt-lastScreenshotAt<350){nextCapture=lastScreenshotAt+350;if(forCalibration||previewRequested)handler.postDelayed(()->capture(forCalibration),350);return;}
        inFlight=true;lastScreenshotAt=capturedAt;nextCapture=capturedAt+350;
        int windowId=-1;Rect windowBounds=null;
        if(Build.VERSION.SDK_INT>=34&&!compatibilityCapture)for(AccessibilityWindowInfo w:getWindows())if(w.getType()==AccessibilityWindowInfo.TYPE_APPLICATION&&(w.isActive()||w.isFocused())){AccessibilityNodeInfo node=w.getRoot();if(node!=null){boolean match=capturePackage.contentEquals(node.getPackageName()==null?"":node.getPackageName());node.recycle();if(match){windowId=w.getId();windowBounds=new Rect();w.getBoundsInScreen(windowBounds);break;}}}
        final Rect capturedWindow=windowBounds;
        TakeScreenshotCallback callback=new TakeScreenshotCallback(){
            @Override public void onSuccess(ScreenshotResult result){
                HardwareBuffer buffer=result.getHardwareBuffer();Bitmap hardware=null,copy=null;
                try{hardware=Bitmap.wrapHardwareBuffer(buffer,result.getColorSpace());if(hardware==null)throw new IllegalStateException("Empty screenshot");copy=hardware.copy(Bitmap.Config.ARGB_8888,false);}catch(Exception ex){inFlight=false;restoreBar();pause("Cannot read screen");return;}finally{if(hardware!=null)hardware.recycle();buffer.close();}
                restoreBar();
                if(ticket!=generation||destroyed){copy.recycle();inFlight=false;return;}captureErrors=0;intervalErrors=0;
                if(capturedWindow!=null){Rect display=wm.getMaximumWindowMetrics().getBounds();Bitmap full=Bitmap.createBitmap(display.width(),display.height(),Bitmap.Config.ARGB_8888);new Canvas(full).drawBitmap(copy,null,capturedWindow,null);copy.recycle();copy=full;}
                if(capturedWindow!=null&&containsOurOverlay(copy)){copy.recycle();inFlight=false;compatibilityCapture=true;getSharedPreferences("capture",0).edit().putBoolean("compatibility",true).apply();log("Overlay detected in window capture; switching to hidden-overlay capture");nextCapture=0;if(forCalibration||previewRequested)handler.postDelayed(()->capture(forCalibration),350);return;}
                screenW=copy.getWidth();screenH=copy.getHeight();
                if(forCalibration&&screenW<=screenH){copy.recycle();inFlight=false;pause("Rotate Corebound to landscape");return;}
                if(forCalibration){inFlight=false;showCalibration(copy);return;}
                if(previewRequested){inFlight=false;previewRequested=false;showPreview(copy);return;}
                // Keep the boost's small star digit legible to OCR. Pixel measurements
                // still downsample internally, so navigation has bounded cost.
                final Bitmap original=copy;final int width=Math.min(1600,screenW);final Bitmap image=Bitmap.createScaledBitmap(original,width,Math.round((float)screenH*width/screenW),true);if(image!=original)original.recycle();
                worker.execute(()->{try{int[] pixels=new int[image.getWidth()*image.getHeight()];image.getPixels(pixels,0,image.getWidth(),0,0,image.getWidth(),image.getHeight());PixelVision.Result vision=PixelVision.analyse(pixels,image.getWidth(),image.getHeight());captureTemporal.update(pixels,image.getWidth(),image.getHeight(),vision,capturedAt);handler.post(()->recognise(image,vision,capturePackage,capturedAt,ticket));}catch(Exception ex){image.recycle();handler.post(()->{inFlight=false;if(ticket==generation)pause("Screen analysis failed");});}});
            }
            @Override public void onFailure(int error){restoreBar();inFlight=false;if(ticket!=generation)return;
                if(error==ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT&&intervalErrors++<5){nextCapture=SystemClock.elapsedRealtime()+450;if(forCalibration||previewRequested)handler.postDelayed(()->capture(forCalibration),500);return;}
                if(capturedWindow!=null&&(error==ERROR_TAKE_SCREENSHOT_INVALID_WINDOW||error==ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR)){compatibilityCapture=true;nextCapture=SystemClock.elapsedRealtime()+400;log("Window capture failed; retrying with hidden toolbar");if(forCalibration||previewRequested)handler.postDelayed(()->capture(forCalibration),450);return;}
                captureErrors++;nextCapture=SystemClock.elapsedRealtime()+800;if(captureErrors>=3||forCalibration||previewRequested){previewRequested=false;pause("Screen capture unavailable ("+error+")");}else setStatus("Retrying screen capture…");}
        };
        if(Build.VERSION.SDK_INT>=34&&windowId>=0)takeScreenshotOfWindow(windowId,getMainExecutor(),callback);
        else {if(bar!=null)bar.setVisibility(View.INVISIBLE);handler.postDelayed(()->{if(ticket!=generation){inFlight=false;restoreBar();return;}takeScreenshot(Display.DEFAULT_DISPLAY,getMainExecutor(),callback);},65);}
    }
    private void recognise(Bitmap bitmap,PixelVision.Result vision,String pkg,long capturedAt,int ticket){
        if(ticket!=generation||destroyed){bitmap.recycle();inFlight=false;return;}
        long now=SystemClock.elapsedRealtime();
        if(vision.gameplay&&engine.state()==FarmEngine.State.GAMEPLAY&&vision.playerConfidence>=.38){
            List<FarmEngine.Token> freshHud=now-hudCapturedAt<=1500?hudTokens:Collections.emptyList();
            // Drive from pixels immediately. HUD OCR is independent and cannot
            // hold the next movement observation behind its completion callback.
            processFrame(vision,pkg,capturedAt,ticket,freshHud,hudCapturedAt);
            inFlight=false;
            if(!hudOcrBusy&&now-lastOcrAt>=900&&ticket==generation&&running)readHud(bitmap,pkg,capturedAt,ticket);
            else bitmap.recycle();
            return;
        }
        if(vision.gameplay&&(vision.controlsDetected||vision.playerConfidence>.5)&&engine.state()==FarmEngine.State.GAMEPLAY&&now-lastOcrAt<900){processFrame(vision,pkg,capturedAt,ticket,Collections.emptyList());bitmap.recycle();inFlight=false;return;}
        lastOcrAt=now;
        // The menu's narrow tier line is the selection failure seen in both trials.
        // Magnify the selected right panel; map all OCR boxes back to the full image.
        final boolean hudOnly=vision.gameplay&&engine.state()==FarmEngine.State.GAMEPLAY
                &&(vision.controlsDetected||vision.playerConfidence>.5);
        final int textLeft=vision.selectedPanel?(int)(bitmap.getWidth()*.54):0;
        final int textTop=vision.selectedPanel?(int)(bitmap.getHeight()*.48):0;
        final int textScale=vision.selectedPanel||hudOnly?2:1;
        final Bitmap textImage;
        if(vision.selectedPanel||hudOnly){int textHeight=hudOnly?Math.max(1,(int)(bitmap.getHeight()*.50)):bitmap.getHeight()-textTop;Bitmap panel=Bitmap.createBitmap(bitmap,textLeft,textTop,bitmap.getWidth()-textLeft,textHeight);textImage=Bitmap.createScaledBitmap(panel,panel.getWidth()*textScale,panel.getHeight()*textScale,true);if(panel!=bitmap&&panel!=textImage)panel.recycle();}else textImage=bitmap;
        recognizer.process(InputImage.fromBitmap(textImage,0)).addOnSuccessListener(text->{
            if(ticket!=generation||!running||destroyed)return;
            List<FarmEngine.Token> tokens=new ArrayList<>();StringBuilder all=new StringBuilder();
            for(Text.TextBlock block:text.getTextBlocks())for(Text.Line line:block.getLines()){Rect r=line.getBoundingBox();if(r!=null){tokens.add(new FarmEngine.Token(line.getText(),(textLeft+r.left/(double)textScale)/bitmap.getWidth(),(textTop+r.top/(double)textScale)/bitmap.getHeight(),(textLeft+r.right/(double)textScale)/bitmap.getWidth(),(textTop+r.bottom/(double)textScale)/bitmap.getHeight()));all.append(line.getText()).append('\n');}}
            collectNodes(tokens,all);
            processFrame(vision,pkg,capturedAt,ticket,tokens);
        }).addOnFailureListener(ex->{if(ticket==generation)pause("Text recognition failed");}).addOnCompleteListener(task->{if(textImage!=bitmap)textImage.recycle();bitmap.recycle();inFlight=false;});
    }
    private void readHud(Bitmap bitmap,String pkg,long capturedAt,int ticket){
        hudOcrBusy=true;lastOcrAt=SystemClock.elapsedRealtime();
        // Sector-completed banners sit below the HUD. Retain them so a camera
        // gap cannot erase the evidence that the next cavern changes direction.
        Bitmap crop=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),Math.max(1,(int)(bitmap.getHeight()*.50)));
        Bitmap textImage=Bitmap.createScaledBitmap(crop,crop.getWidth()*2,crop.getHeight()*2,true);
        if(crop!=bitmap&&crop!=textImage)crop.recycle();
        recognizer.process(InputImage.fromBitmap(textImage,0)).addOnSuccessListener(text->{
            if(ticket!=generation||!running||destroyed||!GAME.equals(pkg))return;
            ArrayList<FarmEngine.Token> tokens=new ArrayList<>();
            for(Text.TextBlock block:text.getTextBlocks())for(Text.Line line:block.getLines()){
                Rect r=line.getBoundingBox();if(r!=null)tokens.add(new FarmEngine.Token(line.getText(),r.left/(2.*bitmap.getWidth()),r.top/(2.*bitmap.getHeight()),r.right/(2.*bitmap.getWidth()),r.bottom/(2.*bitmap.getHeight())));
            }
            hudTokens=tokens;hudCapturedAt=capturedAt;
        }).addOnFailureListener(ex->{if(ticket==generation)log("HUD unreadable this frame; continue visible gameplay");})
          .addOnCompleteListener(task->{if(textImage!=bitmap)textImage.recycle();bitmap.recycle();hudOcrBusy=false;});
    }
    private void processFrame(PixelVision.Result vision,String pkg,long capturedAt,int ticket,List<FarmEngine.Token> tokens){
        processFrame(vision,pkg,capturedAt,ticket,tokens,capturedAt);
    }
    private void processFrame(PixelVision.Result vision,String pkg,long capturedAt,int ticket,List<FarmEngine.Token> tokens,long textCapturedAt){
        if(ticket!=generation||!running||destroyed)return;
        // Capture continues during movement; only the newest frame survives. Camera
        // offsets are cumulative, so dropping an intermediate frame loses no motion.
        if(gestureBusy){pendingFrames.offer(new Observation(vision,pkg,capturedAt,ticket,tokens,textCapturedAt),capturedAt,ticket);return;}
        // A slow OCR callback must not spend the next charge using a picture taken
        // before the previous jump's release and initial physical response.
        if(vision.gameplay&&capturedAt<earliestGameplayObservationAt){nextCapture=0;return;}
        if(SystemClock.elapsedRealtime()-capturedAt>1400){if(++slowFrames>=4)pause("Screen analysis too slow; resume to retry");else{nextCapture=0;setStatus("Refreshing screen…");}return;}slowFrames=0;
        FarmEngine.Frame f=ScreenInterpreter.interpret(SystemClock.elapsedRealtime(),capturedAt,pkg,tokens,vision);
        f.remainingEnemiesCapturedAt=textCapturedAt;
        f.viewportAspectRatio=screenH>0?screenW/(double)screenH:Double.NaN;
        FarmEngine.Action action=observe?engine.observe(f):engine.next(f);setStatus((observe?"Observe · ":"")+engine.runs()+" runs · "+action.reason);log((observe?"Would ":"")+action.kind+": "+action.reason);
        if(engine.takeRunStartRequest()){
            temporal=new TemporalVision();pendingFrames.clear();earliestGameplayObservationAt=0;hudTokens=Collections.emptyList();hudCapturedAt=-1;
        }
        FarmEngine.RunMap finished;while((finished=engine.takeFinishedMap())!=null)mapBacklog.addLast(finished);
        writeNextMap();
        if(action.kind==FarmEngine.Kind.PAUSE&&vision.selectedPanel)log("Selection OCR: "+f.text.replace('\n',' '));
        if(observe){nextCapture=SystemClock.elapsedRealtime()+350;return;}execute(action,vision,pkg,ticket);
    }
    private void writeNextMap(){
        if(mapWriteBusy||mapBacklog.isEmpty()||destroyed)return;
        final FarmEngine.RunMap report=mapBacklog.peekFirst();mapWriteBusy=true;
        final Context storageContext=getApplicationContext();
        mapWriter.execute(()->{
            try{
                MapArchiveStore.save(storageContext,report.snapshot,report.outcome,report.runNumber);
                handler.post(()->{mapBacklog.remove(report);mapWriteBusy=false;if(!destroyed){log("Run map saved privately · use Saved maps to transfer");writeNextMap();}});
            }catch(Exception ex){
                handler.post(()->{mapWriteBusy=false;if(!destroyed)pause("Could not save the run map · check phone storage, then Run to retry");});
            }
        });
    }
    private void collectNodes(List<FarmEngine.Token> tokens,StringBuilder text){AccessibilityNodeInfo root=getRootInActiveWindow();if(root==null)return;try{if(!GAME.contentEquals(root.getPackageName()==null?"":root.getPackageName()))return;ArrayDeque<AccessibilityNodeInfo> queue=new ArrayDeque<>();queue.add(AccessibilityNodeInfo.obtain(root));int count=0;while(!queue.isEmpty()&&count++<180){AccessibilityNodeInfo n=queue.remove();CharSequence label=n.getText()!=null?n.getText():n.getContentDescription();if(label!=null&&n.isVisibleToUser()){String s=label.toString();Rect r=new Rect();n.getBoundsInScreen(r);if(!r.isEmpty()&&screenW>0){tokens.add(new FarmEngine.Token(s,r.left/(double)screenW,r.top/(double)screenH,r.right/(double)screenW,r.bottom/(double)screenH));text.append(s).append('\n');}}for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo child=n.getChild(i);if(child!=null)queue.add(child);}n.recycle();}while(!queue.isEmpty())queue.remove().recycle();}finally{root.recycle();}}

    private void execute(FarmEngine.Action a,PixelVision.Result v,String pkg,int ticket){
        if(ticket!=generation||!running)return;if(!pkg.equals(foregroundPackage())){pause("Screen changed before action");return;}
        Rect geometry=wm.getMaximumWindowMetrics().getBounds();
        if((a.kind==FarmEngine.Kind.TAP||a.kind==FarmEngine.Kind.MOVE)&&(geometry.width()!=screenW||geometry.height()!=screenH)){nextCapture=0;log("Display rotated; waiting for a new screenshot");return;}
        switch(a.kind){case PAUSE:pause(a.reason);break;case WAIT:nextCapture=SystemClock.elapsedRealtime()+Math.max(150,a.durationMs);break;case BACK:performGlobalAction(GLOBAL_ACTION_BACK);nextCapture=SystemClock.elapsedRealtime()+800;break;case TAP:dispatch(TouchPlan.tap(screenW,screenH,a.x,a.y),ticket);break;case MOVE:
            double x=a.direction<0?profile.leftX:profile.rightX,y=a.direction<0?profile.leftY:profile.rightY;
            if(profile.autoControls&&v.controlsDetected){if(a.direction<0&&v.leftX>0){x=v.leftX;y=v.leftY;}if(a.direction>0&&v.rightX>0){x=v.rightX;y=v.rightY;}}
            if(a.direction==0&&a.jumpCount==0){nextCapture=Math.max(lastScreenshotAt+350,SystemClock.elapsedRealtime()+50);break;}
            if(a.jumpCount>0)earliestGameplayObservationAt=SystemClock.elapsedRealtime()+Math.max(40,Math.min(140,profile.jumpMs))+75;
            dispatch(TouchPlan.build(screenW,screenH,x,y,a.direction!=0,profile.jumpX,profile.jumpY,a.jumpCount,a.jumpSpacingMs,profile.jumpMs,a.durationMs),ticket);break;default:break;}
    }
    private void dispatch(GestureDescription gesture,int ticket){
        gestureBusy=true;boolean accepted=dispatchGesture(gesture,new GestureResultCallback(){@Override public void onCompleted(GestureDescription g){gestureBusy=false;if(ticket!=generation||!running)return;Observation latest=pendingFrames.take(SystemClock.elapsedRealtime(),generation,1000,earliestGameplayObservationAt);nextCapture=Math.max(lastScreenshotAt+350,SystemClock.elapsedRealtime()+35);if(latest!=null)processFrame(latest.vision,latest.pkg,latest.capturedAt,latest.ticket,latest.tokens,latest.textCapturedAt);}@Override public void onCancelled(GestureDescription g){gestureBusy=false;pendingFrames.clear();if(ticket==generation&&running)pause("Touch interrupted · press Run when ready");}},handler);if(!accepted){gestureBusy=false;pendingFrames.clear();pause("Android rejected touch input");}
    }
    private void previewCapture(){pause("Previewing game capture");if(inFlight){handler.postDelayed(this::previewCapture,300);return;}previewRequested=true;capture(false);}
    private void showPreview(Bitmap screenshot){hidePreview();preview=new CapturePreview(this,screenshot);restoreBar();WindowManager.LayoutParams p=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);p.gravity=Gravity.TOP|Gravity.LEFT;p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;wm.addView(preview,p);}
    private void hidePreview(){if(preview!=null){wm.removeView(preview);preview.screenshot.recycle();preview=null;restoreBar();}}
    private final class CapturePreview extends View {
        final Bitmap screenshot;final Paint paint=new Paint(3);final Rect imageRect=new Rect();
        CapturePreview(Context c,Bitmap b){super(c);screenshot=b;setFocusableInTouchMode(true);requestFocus();setContentDescription("Captured game preview. Tap or press Back to close.");}
        @Override protected void onDraw(Canvas c){imageRect.set(0,0,getWidth(),getHeight());c.drawBitmap(screenshot,null,imageRect,paint);paint.setColor(0xee0c171b);c.drawRect(0,0,getWidth(),Ui.dp(getContext(),70),paint);paint.setColor(Ui.INK);paint.setTextSize(Ui.dp(getContext(),17));c.drawText("This is what Ceiling Scout sees",Ui.dp(getContext(),20),Ui.dp(getContext(),28),paint);paint.setColor(Ui.MINT);paint.setTextSize(Ui.dp(getContext(),12));c.drawText("The floating bar should be absent · tap anywhere to close",Ui.dp(getContext(),20),Ui.dp(getContext(),51),paint);}
        @Override public boolean onTouchEvent(MotionEvent e){if(e.getAction()==MotionEvent.ACTION_UP)performClick();return true;}
        @Override public boolean performClick(){super.performClick();hidePreview();setStatus("Preview closed · press Run");return true;}
        @Override public boolean onKeyUp(int code,KeyEvent e){if(code==KeyEvent.KEYCODE_BACK){performClick();return true;}return super.onKeyUp(code,e);}
    }
    /** A small scout emblem also verifies that clean-window capture really excludes our overlay. */
    private static final class CaptureMarker extends View {
        private static final int[] COLORS={0xff8fe5c7,0xff477667,0xff477667,0xff8fe5c7};
        final Paint paint=new Paint();
        CaptureMarker(Context c){super(c);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas c){for(int i=0;i<4;i++){paint.setColor(COLORS[i]);float x=(i%2)*getWidth()/2f,y=(i/2)*getHeight()/2f;c.drawRect(x+1,y+1,x+getWidth()/2f-1,y+getHeight()/2f-1,paint);}}
    }
    private boolean containsOurOverlay(Bitmap screenshot){
        if(bar==null||captureMarker==null||barParams==null)return false;
        int mx=barParams.x+bar.getPaddingLeft(),my=barParams.y+(barParams.height-captureMarker.getHeight())/2;
        int size=captureMarker.getWidth();if(size<4)return false;
        for(int i=0;i<4;i++){int x=mx+(i%2)*size/2+size/4,y=my+(i/2)*size/2+size/4;if(x<0||y<0||x>=screenshot.getWidth()||y>=screenshot.getHeight())return false;int c=screenshot.getPixel(x,y),want=CaptureMarker.COLORS[i];if(Math.abs(Color.red(c)-Color.red(want))+Math.abs(Color.green(c)-Color.green(want))+Math.abs(Color.blue(c)-Color.blue(want))>35)return false;}return true;
    }
    private void calibrate(){pause("Calibrating");if(!GAME.equals(foregroundPackage())){setStatus("Open Corebound gameplay, then Calibrate");return;}if(inFlight){handler.postDelayed(this::calibrate,300);return;}capture(true);}
    private void hideCalibration(){if(calibration!=null){wm.removeView(calibration);if(calibration instanceof CalibrationView)((CalibrationView)calibration).screenshot.recycle();calibration=null;}}
    private void showCalibration(Bitmap screenshot){if(bar!=null)bar.setVisibility(View.GONE);CalibrationView view=new CalibrationView(this,screenshot);calibration=view;WindowManager.LayoutParams p=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);p.gravity=Gravity.TOP|Gravity.LEFT;p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;wm.addView(view,p);}
    private final class CalibrationView extends View {
        final Bitmap screenshot;final Paint paint=new Paint(3);int step=0;final float[] points=new float[6];
        CalibrationView(Context c,Bitmap b){super(c);screenshot=b;setFocusableInTouchMode(true);requestFocus();}
        @Override protected void onDraw(Canvas c){c.drawBitmap(screenshot,null,new Rect(0,0,getWidth(),getHeight()),paint);paint.setColor(0xdd0c171b);c.drawRect(0,0,getWidth(),Ui.dp(getContext(),74),paint);paint.setColor(Ui.MINT);paint.setTextSize(Ui.dp(getContext(),18));c.drawText(new String[]{"Tap the LEFT movement button","Tap the RIGHT movement button","Tap your JUMP area (right half)"}[step],Ui.dp(getContext(),20),Ui.dp(getContext(),29),paint);paint.setColor(Ui.INK);paint.setTextSize(Ui.dp(getContext(),12));c.drawText("Frozen screenshot · these taps do not touch the game · Back cancels",Ui.dp(getContext(),20),Ui.dp(getContext(),54),paint);}
        @Override public boolean onTouchEvent(MotionEvent e){if(e.getAction()!=MotionEvent.ACTION_UP)return true;if(e.getY()<Ui.dp(getContext(),74))return true;points[step*2]=e.getX()/getWidth();points[step*2+1]=e.getY()/getHeight();step++;if(step==3){profile.leftX=points[0];profile.leftY=points[1];profile.rightX=points[2];profile.rightY=points[3];profile.jumpX=points[4];profile.jumpY=points[5];profile.autoControls=false;profile.save(FarmerService.this);finish("Controls saved · press Run");}else invalidate();return true;}
        @Override public boolean onKeyUp(int code,android.view.KeyEvent e){if(code==android.view.KeyEvent.KEYCODE_BACK){finish("Calibration cancelled");return true;}return super.onKeyUp(code,e);}
        void finish(String message){hideCalibration();showOverlay();setStatus(message);}
    }
}
