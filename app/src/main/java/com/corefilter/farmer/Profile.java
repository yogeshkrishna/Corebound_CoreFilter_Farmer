package com.corefilter.farmer;

import android.content.Context;
import android.content.SharedPreferences;
import com.corefilter.farmer.engine.FarmEngine;

/** Build notes remain descriptive; jump equipment explicitly controls the navigation budget. */
public final class Profile {
    private static final int SCHEMA_VERSION=2;
    public String name="Frozen 5 · Gilded Ember", hull="Current crawler", weapons="Ember (Gilded)", notes="Screenshot before gilding: 411k HP, speed 8, 81.9k DPS. Recheck current stats in game.";
    public int moveMs=420, jumpMs=70, jumpEveryMs=1300, settleMs=180, maxRunSeconds=180, maxSessionMinutes=30;
    public int hookshotCount=3, extraJumps=0, jumpSpacingMs=190;
    public boolean continuousFarm=true, watchFilterAds=true, autoControls=true;
    public float leftX=.166f,leftY=.81f,rightX=.282f,rightY=.81f,jumpX=.84f,jumpY=.55f;

    public int totalJumpBudget(){return (int)Math.max(1,Math.min(21,1L+2L*Math.max(0,hookshotCount)+Math.max(0,extraJumps)));}

    public static Profile load(Context c) {
        SharedPreferences s=c.getSharedPreferences("profile",0);Profile p=new Profile();
        p.name=s.getString("name",p.name);p.hull=s.getString("hull",p.hull);p.weapons=s.getString("weapons",p.weapons);p.notes=s.getString("notes",p.notes);
        boolean old=s.getInt("schemaVersion",0)<SCHEMA_VERSION;
        p.moveMs=s.getInt("moveMs",p.moveMs);p.settleMs=s.getInt("settleMs",p.settleMs);
        // Replace only v1 defaults. Custom timing, build notes and calibrated points survive updates.
        if(old&&p.moveMs==600)p.moveMs=420;
        if(old&&p.settleMs==700)p.settleMs=180;
        p.moveMs=Math.max(150,Math.min(700,p.moveMs));
        p.jumpMs=s.getInt("jumpMs",p.jumpMs);p.jumpEveryMs=s.getInt("jumpEveryMs",p.jumpEveryMs);
        p.maxRunSeconds=s.getInt("maxRunSeconds",p.maxRunSeconds);p.maxSessionMinutes=s.getInt("maxSessionMinutes",p.maxSessionMinutes);
        p.hookshotCount=s.getInt("hookshotCount",p.hookshotCount);p.extraJumps=s.getInt("extraJumps",p.extraJumps);p.jumpSpacingMs=s.getInt("jumpSpacingMs",p.jumpSpacingMs);
        p.continuousFarm=s.getBoolean("continuousFarm",true);p.watchFilterAds=s.getBoolean("watchFilterAds",true);p.autoControls=s.getBoolean("autoControls",true);
        p.leftX=s.getFloat("leftX",p.leftX);p.leftY=s.getFloat("leftY",p.leftY);p.rightX=s.getFloat("rightX",p.rightX);p.rightY=s.getFloat("rightY",p.rightY);p.jumpX=s.getFloat("jumpX",p.jumpX);p.jumpY=s.getFloat("jumpY",p.jumpY);
        if(old)p.save(c);
        return p;
    }
    public void save(Context c) {
        c.getSharedPreferences("profile",0).edit().putInt("schemaVersion",SCHEMA_VERSION)
          .putString("name",name).putString("hull",hull).putString("weapons",weapons).putString("notes",notes)
          .putInt("moveMs",moveMs).putInt("jumpMs",jumpMs).putInt("jumpEveryMs",jumpEveryMs).putInt("settleMs",settleMs).putInt("maxRunSeconds",maxRunSeconds).putInt("maxSessionMinutes",maxSessionMinutes)
          .putInt("hookshotCount",hookshotCount).putInt("extraJumps",extraJumps).putInt("jumpSpacingMs",jumpSpacingMs).putBoolean("continuousFarm",continuousFarm)
          .putBoolean("watchFilterAds",watchFilterAds).putBoolean("autoControls",autoControls)
          .putFloat("leftX",leftX).putFloat("leftY",leftY).putFloat("rightX",rightX).putFloat("rightY",rightY).putFloat("jumpX",jumpX).putFloat("jumpY",jumpY).apply();
    }
    public FarmEngine.Config config(){
        FarmEngine.Config c=new FarmEngine.Config();c.moveMs=Math.max(150,Math.min(700,moveMs));c.riseMs=jumpMs;c.jumpTapMs=jumpMs;c.jumpIntervalMs=jumpEveryMs;c.settleMs=settleMs;
        c.jumpBudget=totalJumpBudget();c.jumpSpacingMs=jumpSpacingMs;c.continuousFarm=continuousFarm;
        c.maxRunSeconds=maxRunSeconds;c.maxSessionMinutes=maxSessionMinutes;c.watchFilterAds=watchFilterAds;return c;
    }
}
