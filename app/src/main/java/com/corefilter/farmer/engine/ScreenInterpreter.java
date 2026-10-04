package com.corefilter.farmer.engine;

import com.corefilter.farmer.vision.PixelVision;
import java.util.List;
import java.util.Locale;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts fresh OCR and pixel evidence together; never treat ordinary loot as an ad offer. */
public final class ScreenInterpreter {
    private ScreenInterpreter(){}
    private static final Pattern COUNTER = Pattern.compile("^(?:(?:enemies|bots)\\s*(?:remaining|left)?|remaining\\s*(?:enemies|bots))\\s*[:=-]?\\s*(\\d{1,2})$");
    private static final Pattern COUNT_FIRST = Pattern.compile("^(\\d{1,2})\\s*(?:(?:enemies|bots)\\s*(?:remaining|left)|remaining\\s*(?:enemies|bots))$");
    private static final Pattern COUNTER_LABEL = Pattern.compile("^(?:(?:enemies|bots)\\s*(?:remaining|left)?|remaining\\s*(?:enemies|bots))\\s*[:=-]?$");
    public static FarmEngine.Frame interpret(long now,long capturedAt,String pkg,List<FarmEngine.Token> tokens,PixelVision.Result v){
        if(tokens==null)tokens=Collections.emptyList();
        StringBuilder text=new StringBuilder();for(FarmEngine.Token t:tokens)text.append(t.text).append('\n');
        FarmEngine.Frame f=new FarmEngine.Frame(now,pkg,text.toString(),tokens);f.capturedAt=capturedAt;f.gameplay=v.gameplay;f.gate=v.gate;f.gateX=v.gateX;f.gateY=v.gateY;f.playerX=v.playerX;f.playerY=v.playerY;f.sceneSignature=v.sceneSignature;
        f.playerConfidence=v.playerConfidence;f.grounded=v.grounded;f.groundContactCandidate=v.groundContactCandidate;f.ceilingReached=v.ceilingReached;
        f.playerLeft=v.playerLeft;f.playerTop=v.playerTop;f.playerRight=v.playerRight;f.playerBottom=v.playerBottom;f.wallLeft=v.wallLeft;f.wallRight=v.wallRight;
        f.terrainCols=v.terrainCols;f.terrainRows=v.terrainRows;f.terrainCells=v.terrainCells;
        f.cameraDx=v.cameraDx;f.cameraDy=v.cameraDy;f.cameraX=v.cameraX;f.cameraY=v.cameraY;f.cameraConfidence=v.cameraConfidence;
        f.registrationEpoch=v.registrationEpoch;f.registrationReset=v.registrationReset;f.registrationLost=v.registrationLost;f.sceneChanged=v.sceneChanged;
        f.enemyBoxes=v.enemyBoxes;f.playButton=v.playButton;f.playX=v.playX;f.playY=v.playY;f.selectedPanel=v.selectedPanel;
        String lower=text.toString().toLowerCase(Locale.ROOT);
        f.controlsDetected=v.controlsDetected;
        boolean adText=lower.contains("advertisement")||lower.contains("reward in")||lower.contains("ad ends")||lower.contains("close ad")||lower.contains("install now")||lower.contains("google play")||lower.contains("skip video")||lower.contains("skip ad");
        // Install cards can contain colours/outlines that resemble the game HUD.
        if(adText){f.observedAd=true;f.gameplay=false;f.playerConfidence=0;}
        if(f.gameplay){java.util.regex.Matcher sector=java.util.regex.Pattern.compile("sector\\s*([1-4])\\s*/\\s*4\\s*completed").matcher(lower);if(sector.find())f.completedSector=Integer.parseInt(sector.group(1));}
        if(f.gameplay)readRemainingEnemies(f,tokens);
        for(FarmEngine.Token t:tokens){String s=t.text.toLowerCase(Locale.ROOT).trim();if(t.top<.24&&(s.equals("complete!")||s.equals("complete")||s.equals("completed!")))f.endScreen=true;}
        f.crateScreen=lower.contains("crate cooldown")||lower.contains("crate rewards")||lower.contains("crate contents")||lower.contains("crate opened");
        if(f.endScreen&&v.rewardButton){f.tokens.add(new FarmEngine.Token("Watch reward",.52,.814,.819,.95));f.filterOffer=v.filterLoot;f.uncertainFilterOffer=v.uncertainFilterOffer;}
        f.filterLoot=f.endScreen&&v.filterLoot;
        f.observedAd=adText;
        if(adText){f.endScreen=f.crateScreen=f.filterLoot=f.filterOffer=f.uncertainFilterOffer=false;}
        return f;
    }

    /** Only a labelled HUD observation can establish the ordinary-enemy count.
     * Every Frame starts unknown; skipped OCR, unrelated numbers and a new sector
     * cannot inherit an earlier zero. Spectrum coverage is a separate concern.
     */
    private static void readRemainingEnemies(FarmEngine.Frame f,List<FarmEngine.Token> tokens) {
        int found=-1;double confidence=0;
        for(FarmEngine.Token label:tokens) {
            if(!hud(label))continue;
            String s=clean(label.text);Matcher m=COUNTER.matcher(s);
            int value=-1;
            if(m.matches())value=Integer.parseInt(m.group(1));
            else {m=COUNT_FIRST.matcher(s);if(m.matches())value=Integer.parseInt(m.group(1));}
            double candidateConfidence=.95;
            if(value<0&&COUNTER_LABEL.matcher(s).matches()) {
                // OCR may split the label and number into adjacent tokens. Require
                // one unambiguous number on the same HUD line and to its right.
                int adjacent=-1;
                for(FarmEngine.Token number:tokens) {
                    String numeric=clean(number.text).replaceFirst("^(?:remaining|left)\\s*[:=-]?\\s*","");
                    if(number==label||!hud(number)||!numeric.matches("\\d{1,2}"))continue;
                    if(Math.abs(number.y()-label.y())>.025||number.left<label.right-.015||number.left-label.right>.16)continue;
                    if(adjacent>=0){adjacent=-2;break;}
                    adjacent=Integer.parseInt(numeric);
                }
                if(adjacent>=0){value=adjacent;candidateConfidence=.85;}
            }
            if(value<0)continue;
            if(found>=0&&value!=found){f.remainingEnemies=-1;f.remainingEnemiesConfidence=0;return;}
            found=value;confidence=Math.max(confidence,candidateConfidence);
        }
        f.remainingEnemies=found;f.remainingEnemiesConfidence=confidence;
    }
    private static boolean hud(FarmEngine.Token t) {
        return Double.isFinite(t.top)&&Double.isFinite(t.bottom)&&Double.isFinite(t.left)&&Double.isFinite(t.right)
            &&t.top>=0&&t.bottom<=.28&&t.left>=0&&t.right<=1&&t.right>t.left&&t.bottom>t.top;
    }
    private static String clean(String s){return s==null?"":s.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+"," ");}
}
