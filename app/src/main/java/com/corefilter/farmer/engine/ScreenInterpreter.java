package com.corefilter.farmer.engine;

import com.corefilter.farmer.vision.PixelVision;
import java.util.List;
import java.util.Locale;
import java.util.Collections;

/** Converts fresh OCR and pixel evidence together; never treat ordinary loot as an ad offer. */
public final class ScreenInterpreter {
    private ScreenInterpreter(){}
    public static FarmEngine.Frame interpret(long now,long capturedAt,String pkg,List<FarmEngine.Token> tokens,PixelVision.Result v){
        if(tokens==null)tokens=Collections.emptyList();
        StringBuilder text=new StringBuilder();for(FarmEngine.Token t:tokens)text.append(t.text).append('\n');
        FarmEngine.Frame f=new FarmEngine.Frame(now,pkg,text.toString(),tokens);f.capturedAt=capturedAt;f.gameplay=v.gameplay;f.gate=v.gate;f.gateX=v.gateX;f.gateY=v.gateY;f.playerX=v.playerX;f.playerY=v.playerY;f.sceneSignature=v.sceneSignature;
        f.playerConfidence=v.playerConfidence;f.grounded=v.grounded;f.ceilingReached=v.ceilingReached;
        f.playerLeft=v.playerLeft;f.playerTop=v.playerTop;f.playerRight=v.playerRight;f.playerBottom=v.playerBottom;f.wallLeft=v.wallLeft;f.wallRight=v.wallRight;
        f.terrainCols=v.terrainCols;f.terrainRows=v.terrainRows;f.terrainCells=v.terrainCells;
        f.cameraDx=v.cameraDx;f.cameraDy=v.cameraDy;f.cameraX=v.cameraX;f.cameraY=v.cameraY;f.cameraConfidence=v.cameraConfidence;
        f.registrationEpoch=v.registrationEpoch;f.registrationReset=v.registrationReset;f.sceneChanged=v.sceneChanged;
        f.enemyBoxes=v.enemyBoxes;f.playButton=v.playButton;f.playX=v.playX;f.playY=v.playY;f.selectedPanel=v.selectedPanel;
        String lower=text.toString().toLowerCase(Locale.ROOT);
        if(f.gameplay){java.util.regex.Matcher sector=java.util.regex.Pattern.compile("sector\\s*([1-4])\\s*/\\s*4\\s*completed").matcher(lower);if(sector.find())f.completedSector=Integer.parseInt(sector.group(1));}
        for(FarmEngine.Token t:tokens){String s=t.text.toLowerCase(Locale.ROOT).trim();if(t.top<.24&&(s.equals("complete!")||s.equals("complete")||s.equals("completed!")))f.endScreen=true;}
        f.crateScreen=lower.contains("crate cooldown")||lower.contains("crate rewards")||lower.contains("crate contents")||lower.contains("crate opened");
        if(f.endScreen&&v.rewardButton){f.tokens.add(new FarmEngine.Token("Watch reward",.52,.814,.819,.95));f.filterOffer=v.filterLoot;f.uncertainFilterOffer=v.uncertainFilterOffer;}
        f.filterLoot=f.endScreen&&v.filterLoot;
        f.observedAd=!f.gameplay&&!f.endScreen&&!f.crateScreen&&(lower.contains("advertisement")||lower.contains("reward in")||lower.contains("ad ends")||lower.contains("close ad")||lower.contains("install now")||lower.contains("google play")||lower.contains("skip video"));
        return f;
    }
}
