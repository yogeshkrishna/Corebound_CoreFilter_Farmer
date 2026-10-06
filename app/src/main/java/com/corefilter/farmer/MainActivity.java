package com.corefilter.farmer;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Build;
import android.media.projection.MediaProjectionManager;
import android.media.projection.MediaProjectionConfig;
import android.provider.Settings;
import android.text.*;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;

public final class MainActivity extends Activity {
    private TextView connection,buildSummary,modeSummary;
    private Button primary;
    private TextView liveSummary,offlineSummary;
    private boolean pendingOffline;
    private final android.os.Handler statusHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable statusRefresh=new Runnable(){public void run(){refreshStatus();statusHandler.postDelayed(this,1500);}};
    private static final int CAPTURE_PERMISSION=77;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);if(b!=null)pendingOffline=b.getBoolean("pendingOffline");
        LegacyCaptureCleanup.start(this);
        getWindow().setStatusBarColor(Ui.BG);getWindow().setNavigationBarColor(Ui.BG);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        home();
    }
    @Override public void onResume(){
        super.onResume();refreshStatus();statusHandler.post(statusRefresh);
        UpdateManager.onResume(this);
        if(getIntent().getBooleanExtra("editBuild",false)){getIntent().removeExtra("editBuild");editProfile();}
        if(getIntent().getData()!=null){String link=getIntent().getData().getQueryParameter("link");getIntent().setData(null);if(link!=null)pairLaptop(link);}
        if(getIntent().getBooleanExtra("startLive",false)){getIntent().removeExtra("startLive");if(getSharedPreferences("mode",0).getBoolean("laptopCapture",false))startLive();else startOffline();}
    }
    @Override public void onPause(){statusHandler.removeCallbacks(statusRefresh);super.onPause();}
    @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putBoolean("pendingOffline",pendingOffline);}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);}

    private void home(){
        FrameLayout root=new FrameLayout(this);root.setTag("dashboard-insets");root.setBackgroundColor(Ui.BG);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            Insets keyboard=insets.getInsets(WindowInsets.Type.ime());
            v.setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,keyboard.bottom));return insets;
        });
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);
        LinearLayout main=Ui.column(this);int p=Ui.dp(this,20);main.setPadding(p,Ui.dp(this,16),p,Ui.dp(this,24));scroll.addView(main);
        root.addView(scroll,new FrameLayout.LayoutParams(-1,-1));setContentView(root);root.requestApplyInsets();

        TextView eyebrow=Ui.text(this,"COREBOUND / FROZEN ★5",11,Ui.MINT);eyebrow.setLetterSpacing(.12f);main.addView(eyebrow);
        TextView title=Ui.text(this,"Ceiling Scout",28,Ui.INK);title.setTypeface(null,Typeface.BOLD);main.addView(title);
        main.addView(Ui.text(this,"Core filters. Ceiling to ceiling.",14,Ui.MUTED));

        LinearLayout setup=Ui.card(this);
        connection=Ui.text(this,"Controls not connected",15,Ui.AMBER);connection.setTypeface(null,Typeface.BOLD);setup.addView(connection);
        setup.addView(Ui.text(this,"Run farms the level. Record saves your manual play on this phone.",14,Ui.MUTED));
        primary=Ui.button(this,"Enable controls",()->{if(FarmerService.instance==null)enableControls();else FarmerService.instance.showOverlay();});primary.setTag("connect-controls");setup.addView(primary);
        Button open=Ui.secondaryButton(this,"Open Corebound",this::openGame);open.setTag("open-game");setup.addView(open);main.addView(setup);

        LinearLayout modes=Ui.card(this);Ui.title(modes,"Choose how to play");
        modeSummary=Ui.text(this,"",14,Ui.MINT);modes.addView(modeSummary);
        modes.addView(Ui.text(this,"Offline mapping records your manual play. Build the full-resolution map afterward, on this phone. Farmer runs the automation.",13,Ui.MUTED));
        Button mapMode=Ui.secondaryButton(this,"Use offline mapper",()->selectMode(true));mapMode.setTag("mode-manual");modes.addView(mapMode);
        Button farmMode=Ui.secondaryButton(this,"Use farmer",()->selectMode(false));farmMode.setTag("mode-farmer");modes.addView(farmMode);main.addView(modes);

        LinearLayout offline=Ui.card(this);Ui.title(offline,"Record now. Build afterward.");
        offlineSummary=Ui.text(this,"",14,Ui.MUTED);offline.addView(offlineSummary);
        offline.addView(Ui.text(this,"Original-resolution images stay on this phone. Cover every corridor and high ceiling, then stop recording and build. No laptop or Wi-Fi needed.",13,Ui.MUTED));
        Button record=Ui.button(this,"Record a map",this::startOffline);record.setTag("record-offline");offline.addView(record);
        offline.addView(Ui.secondaryButton(this,"Stop recording",()->{LiveCaptureService.stop(this);refreshStatus();}));
        Button maps=Ui.secondaryButton(this,"Recorded maps · build, preview & export",()->startActivity(new Intent(this,OfflineMapsActivity.class)));maps.setTag("offline-maps");offline.addView(maps);main.addView(offline);

        LinearLayout build=Ui.card(this);Ui.title(build,"Your farming build");
        buildSummary=Ui.text(this,"",14,Ui.MUTED);build.addView(buildSummary);
        Button edit=Ui.secondaryButton(this,"Edit build & farming settings",this::editProfile);edit.setTag("edit-build");build.addView(edit);main.addView(build);

        LinearLayout live=Ui.card(this);Ui.title(live,"Optional · live laptop connection");
        liveSummary=Ui.text(this,"",14,Ui.MUTED);live.addView(liveSummary);
        live.addView(Ui.text(this,"Open Ceiling Scout Studio on the laptop. Use the same Wi-Fi, then scan its QR with your camera or paste its connection link here. The laptop saves images and map data as you play.",13,Ui.MUTED));
        Button pair=Ui.secondaryButton(this,"Connect laptop",()->pairLaptop(getSharedPreferences("live",0).getString("endpoint","")));pair.setTag("pair-laptop");live.addView(pair);
        Button stream=Ui.button(this,"Start live capture",this::startLive);stream.setTag("start-live");live.addView(stream);
        live.addView(Ui.secondaryButton(this,"Stop live capture",()->{LiveCaptureService.stop(this);refreshStatus();}));main.addView(live);

        LinearLayout updates=Ui.card(this);Ui.title(updates,"App updates");
        updates.addView(Ui.text(this,"Download new versions from your GitHub releases, then confirm the update on this phone.",13,Ui.MUTED));
        Button check=Ui.secondaryButton(this,"Check for updates",()->UpdateManager.show(this));check.setTag("check-updates");updates.addView(check);main.addView(updates);

        LinearLayout guide=Ui.card(this);Ui.title(guide,"Ready in three steps");
        step(guide,"1","Choose Lost Scrapyard","Set the boost to Frozen ★5.");
        step(guide,"2","Calibrate once","On the bar: left, right, then jump area.");
        step(guide,"3","Run or Record","Run farms. Record asks for screen-capture permission. After your run, open Recorded maps and tap Build / Resume.");main.addView(guide);
        main.addView(Ui.text(this,"Offline capture saves landscape Corebound images only; other apps are skipped. The bar is masked. Processing, preview and PNG export work on this phone. Optional live streaming sends images to your paired laptop. App updates download from GitHub.",12,Ui.MUTED));
        main.addView(Ui.secondaryButton(this,"View session log",this::showLog));
        main.addView(Ui.text(this,"CEILING SCOUT 0.6.0 · OFFLINE MAPS",10,Ui.MUTED));refreshStatus();
    }

    private void step(LinearLayout parent,String number,String title,String detail){
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setPadding(0,Ui.dp(this,7),0,Ui.dp(this,7));
        TextView n=Ui.text(this,number,14,Ui.MINT);n.setTypeface(null,Typeface.BOLD);row.addView(n,new LinearLayout.LayoutParams(Ui.dp(this,28),-2));
        LinearLayout words=Ui.column(this);TextView heading=Ui.text(this,title,14,Ui.INK);heading.setPadding(0,Ui.dp(this,4),0,0);words.addView(heading);words.addView(Ui.text(this,detail,12,Ui.MUTED));row.addView(words,new LinearLayout.LayoutParams(0,-2,1));parent.addView(row);
    }
    private void refreshStatus(){
        if(connection==null)return;
        boolean connected=FarmerService.instance!=null;
        connection.setText(connected?"●  Controls connected":"○  Connect controls to begin");connection.setTextColor(connected?Ui.MINT:Ui.AMBER);
        primary.setText(connected?"Show floating bar":"Enable controls");
        if(modeSummary!=null)modeSummary.setText(getSharedPreferences("mode",0).getBoolean("manualMapping",false)?"Selected: Offline mapper · Record / Stop":"Selected: Farmer · Run / Pause");
        if(liveSummary!=null){String link=getSharedPreferences("live",0).getString("endpoint","");try{liveSummary.setText(LiveCaptureService.active()?LiveCaptureService.statusLine():"Paired: "+LiveEndpoint.parse(link).base);}catch(IllegalArgumentException e){liveSummary.setText("No laptop paired yet");}}
        if(offlineSummary!=null){String text="Ready to record. Your original images stay saved until you choose Delete this recording.";
            if(LiveCaptureService.active())text=LiveCaptureService.statusLine();else if(LiveCaptureService.finishing())text="Finishing the last saved image…";else if(OfflineMapService.active())text=OfflineMapService.statusLine();
            else try{java.io.File f=RecordingStore.directory(this,getSharedPreferences("offline",0).getString("latest",""));org.json.JSONObject r=RecordingStore.read(new java.io.File(f,"recording.json"));text=r.optInt("frames")+" images saved · "+r.optString("message");}catch(Exception ignored){}
            offlineSummary.setText(text);}
        Profile p=Profile.load(this);buildSummary.setText((p.weapons.trim().isEmpty()?"Current equipment":p.weapons)+"\n"+p.hookshotCount+" hookshots · "+p.totalJumpBudget()+" jumps per ascent\n"+(p.continuousFarm?"Keeps farming until you stop":p.maxSessionMinutes+" minute session"));
    }
    private void enableControls(){
        new AlertDialog.Builder(this).setTitle("Allow screen reading and touches")
          .setMessage("Ceiling Scout uses Android Accessibility to read the game, show floating controls and send touches after you press Run. It can close recognized ads and return from Google Play opened by an ad. Offline mapping separately asks for screen-capture permission and saves original game images on this device. Optional laptop streaming sends them only to your paired laptop.")
          .setPositiveButton("Open Accessibility",(d,w)->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))).setNegativeButton("Cancel",null).show();
    }
    private void openGame(){Intent i=getPackageManager().getLaunchIntentForPackage("com.Overcurve.Corebound");if(i!=null)startActivity(i);else Toast.makeText(this,"Corebound is not installed on this device",Toast.LENGTH_LONG).show();}
    private void selectMode(boolean manual){if(manual)getSharedPreferences("mode",0).edit().putBoolean("laptopCapture",false).apply();getSharedPreferences("mode",0).edit().putBoolean("manualMapping",manual).apply();if(FarmerService.instance!=null)FarmerService.instance.selectMode(manual);refreshStatus();Toast.makeText(this,manual?"Press Record a map, then play the level manually":"Open Corebound and press Run on the bar",Toast.LENGTH_LONG).show();}
    private void pairLaptop(String initial){
        EditText input=new EditText(this);input.setTag("laptop-link");input.setText(initial);input.setSingleLine(false);input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);input.setHint("http://192.168…:8767/connect/…");
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Connect your laptop").setMessage("Paste the connection link shown in Ceiling Scout Studio. Both devices must use the same Wi-Fi.").setView(input).setPositiveButton("Connect",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{try{LiveEndpoint endpoint=LiveEndpoint.parse(input.getText().toString());LiveCaptureService.stop(this);getSharedPreferences("live",0).edit().putString("endpoint",endpoint.link()).apply();refreshStatus();dialog.dismiss();}catch(IllegalArgumentException e){input.setError(e.getMessage());}}));dialog.show();
    }
    private void startLive(){
        if(LiveCaptureService.active()){openGame();return;}
        if(FarmerService.instance==null){enableControls();return;}
        try{LiveEndpoint.parse(getSharedPreferences("live",0).getString("endpoint",""));}catch(IllegalArgumentException e){pairLaptop("");return;}
        selectMode(true);getSharedPreferences("mode",0).edit().putBoolean("laptopCapture",true).apply();pendingOffline=false;
        MediaProjectionManager manager=getSystemService(MediaProjectionManager.class);
        Intent permission=Build.VERSION.SDK_INT>=34?manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()):manager.createScreenCaptureIntent();
        startActivityForResult(permission,CAPTURE_PERMISSION);
    }
    private void startOffline(){
        if(LiveCaptureService.active()){openGame();return;}
        if(LiveCaptureService.finishing()){Toast.makeText(this,"Finishing the last saved frame; try again shortly",Toast.LENGTH_SHORT).show();return;}
        if(OfflineMapService.active()){Toast.makeText(this,"Pause map processing before recording",Toast.LENGTH_LONG).show();return;}
        if(FarmerService.instance==null){enableControls();return;}
        selectMode(true);pendingOffline=true;
        MediaProjectionManager manager=getSystemService(MediaProjectionManager.class);
        Intent permission=Build.VERSION.SDK_INT>=34?manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()):manager.createScreenCaptureIntent();
        startActivityForResult(permission,CAPTURE_PERMISSION);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(request!=CAPTURE_PERMISSION)return;
        if(result!=RESULT_OK||data==null){Toast.makeText(this,"Screen sharing cancelled",Toast.LENGTH_SHORT).show();return;}
        startForegroundService(new Intent(this,LiveCaptureService.class).putExtra("result",result).putExtra("consent",data).putExtra("offline",pendingOffline));
        if(FarmerService.instance!=null)FarmerService.instance.showOverlay();openGame();
    }

    private EditText field(LinearLayout l,String key,String label,String value,boolean number){
        TextView caption=Ui.text(this,label,12,Ui.MUTED);l.addView(caption);
        EditText e=new EditText(this);e.setId(View.generateViewId());e.setTag(key);caption.setLabelFor(e.getId());
        e.setText(value);e.setTextSize(15);e.setTextColor(Ui.INK);e.setBackgroundTintList(ColorStateList.valueOf(Ui.MINT));e.setSelectAllOnFocus(number);
        e.setSingleLine(number||!key.equals("notes"));e.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        e.setInputType(number?InputType.TYPE_CLASS_NUMBER:InputType.TYPE_CLASS_TEXT|(key.equals("notes")?InputType.TYPE_TEXT_FLAG_MULTI_LINE:InputType.TYPE_TEXT_FLAG_CAP_SENTENCES));
        e.setMinHeight(Ui.dp(this,48));if(key.equals("notes")){e.setMinLines(2);e.setMaxLines(4);}
        l.addView(e,new LinearLayout.LayoutParams(-1,-2));return e;
    }
    private int val(EditText e,int min,int max){
        try{int v=Integer.parseInt(e.getText().toString().trim());if(v>=min&&v<=max){e.setError(null);return v;}}catch(NumberFormatException ignored){}
        String message="Enter "+min+"–"+max;e.setError(message);e.requestFocus();throw new IllegalArgumentException(message);
    }
    private Switch toggle(LinearLayout l,String key,String label,boolean checked){
        Switch s=new Switch(this);s.setTag(key);s.setText(label);s.setTextSize(14);s.setTextColor(Ui.INK);s.setChecked(checked);s.setMinHeight(Ui.dp(this,52));
        s.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{Ui.MINT,Ui.MUTED}));
        s.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{0x665FBA99,0x6644595A}));
        s.setPadding(0,Ui.dp(this,8),0,Ui.dp(this,8));s.setSwitchPadding(Ui.dp(this,14));l.addView(s,new LinearLayout.LayoutParams(-1,-2));return s;
    }
    private void section(LinearLayout l,String title){TextView t=Ui.text(this,title,17,Ui.MINT);t.setTypeface(null,Typeface.BOLD);t.setPadding(0,Ui.dp(this,20),0,Ui.dp(this,7));l.addView(t);}

    public void editProfile(){
        if(FarmerService.instance!=null)FarmerService.instance.pause("Editing build");
        Profile p=Profile.load(this);ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);scroll.setClipToPadding(false);
        LinearLayout l=Ui.column(this);int pad=Ui.dp(this,20);l.setPadding(pad,0,pad,Ui.dp(this,20));l.setBackgroundColor(Ui.BG);scroll.addView(l);
        section(l,"Equipment");
        EditText name=field(l,"name","Profile name",p.name,false),hull=field(l,"hull","Hull / movement type",p.hull,false),weapons=field(l,"weapons","Weapons & gilding",p.weapons,false),notes=field(l,"notes","Build notes / current stats",p.notes,false);
        section(l,"Ceiling reach");
        EditText hooks=field(l,"hookshotCount","Magmatic ★7+ hookshots · 0–10",String.valueOf(p.hookshotCount),true);
        EditText extra=field(l,"extraJumps","Extra jumps from other equipment · 0–20",String.valueOf(p.extraJumps),true);
        TextView budget=Ui.text(this,"",13,Ui.MINT);l.addView(budget);
        Runnable updateBudget=()->{try{int h=Integer.parseInt(hooks.getText().toString()),x=Integer.parseInt(extra.getText().toString());budget.setText("1 base + "+(Math.max(0,h)*2L)+" hookshot + "+Math.max(0,x)+" extra = "+Math.min(21,1L+Math.max(0,h)*2L+Math.max(0,x))+" jumps (max 21)");}catch(NumberFormatException e){budget.setText("Each Magmatic ★7+ hookshot adds 2 jumps.");}};
        TextWatcher budgetWatcher=new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){updateBudget.run();}public void afterTextChanged(Editable e){}};
        hooks.addTextChangedListener(budgetWatcher);extra.addTextChangedListener(budgetWatcher);updateBudget.run();
        l.addView(Ui.text(this,"Your build image has 3 hookshots: 7 total jumps. Update the count whenever your equipped build changes.",12,Ui.MUTED));
        section(l,"Movement & contact");
        EditText move=field(l,"moveMs","Horizontal hold · 150–700 ms",String.valueOf(p.moveMs),true);
        EditText spacing=field(l,"jumpSpacingMs","Minimum jump interval · 250–1200 ms",String.valueOf(p.jumpSpacingMs),true);
        EditText settle=field(l,"settleMs","Burn contact · 80–500 ms",String.valueOf(p.settleMs),true);
        l.addView(Ui.text(this,"Short contact ignites enemies; the route then moves on. Equipment notes do not predict damage.",12,Ui.MUTED));
        section(l,"Session & rewards");
        Switch continuous=toggle(l,"continuousFarm","Keep farming until I stop",p.continuousFarm);
        EditText session=field(l,"maxSessionMinutes","Timed session · 1–240 minutes",String.valueOf(p.maxSessionMinutes),true);
        session.setEnabled(!p.continuousFarm);session.setAlpha(p.continuousFarm?.45f:1f);
        continuous.setOnCheckedChangeListener((button,checked)->{session.setEnabled(!checked);session.setAlpha(checked?.45f:1f);});
        EditText run=field(l,"maxRunSeconds","Stuck-run watchdog · 45–600 seconds",String.valueOf(p.maxRunSeconds),true);
        l.addView(Ui.text(this,"Until stopped removes the session time limit. Recognition and stuck-run safeguards still apply.",12,Ui.MUTED));
        Switch watch=toggle(l,"watchFilterAds","Watch detected core-filter reward ads",p.watchFilterAds);
        Switch auto=toggle(l,"autoControls","Find movement controls automatically",p.autoControls);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Build & farming settings").setView(scroll).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        d.setOnShowListener(z->{
            d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            int width=Math.min(Ui.dp(this,560),getResources().getDisplayMetrics().widthPixels-Ui.dp(this,24));d.getWindow().setLayout(width,WindowManager.LayoutParams.WRAP_CONTENT);
            d.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Ui.MINT);d.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Ui.MUTED);
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
                int h=val(hooks,0,10),x=val(extra,0,20),m=val(move,150,700),s=val(spacing,250,1200),contact=val(settle,80,500),maxRun=val(run,45,600);
                int maxSession=continuous.isChecked()?p.maxSessionMinutes:val(session,1,240);
                p.name=name.getText().toString().trim();p.hull=hull.getText().toString().trim();p.weapons=weapons.getText().toString().trim();p.notes=notes.getText().toString().trim();
                p.hookshotCount=h;p.extraJumps=x;p.moveMs=m;p.jumpSpacingMs=s;p.settleMs=contact;p.maxRunSeconds=maxRun;p.maxSessionMinutes=maxSession;
                p.continuousFarm=continuous.isChecked();p.watchFilterAds=watch.isChecked();p.autoControls=auto.isChecked();p.save(this);
                if(FarmerService.instance!=null)FarmerService.instance.reloadProfile();refreshStatus();d.dismiss();
            }catch(IllegalArgumentException ex){Toast.makeText(this,"Check the highlighted setting",Toast.LENGTH_SHORT).show();}});
        });d.show();
    }

    private void showLog(){String log=FarmerService.instance==null?getSharedPreferences("session",0).getString("log","No session yet."):FarmerService.instance.logText();new AlertDialog.Builder(this).setTitle("Recent decisions").setMessage(log).setPositiveButton("Copy",(d,w)->{((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Ceiling Scout log",log));}).setNegativeButton("Close",null).show();}
}
