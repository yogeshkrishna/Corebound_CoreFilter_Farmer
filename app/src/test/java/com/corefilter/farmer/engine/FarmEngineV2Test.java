package com.corefilter.farmer.engine;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.corefilter.farmer.engine.FarmEngine.*;

/** Retained v2 menu/replay fixes. Gameplay regressions live in MapNavigatorTest. */
public class FarmEngineV2Test {
    private static final String GAME="com.Overcurve.Corebound";
    private Token token(String text,double x,double y){return new Token(text,x-.03,y-.015,x+.03,y+.015);}
    private Frame frame(long now,Token...tokens){return new Frame(now,GAME,"",Arrays.asList(tokens));}
    private Frame game(long now){Frame f=frame(now);f.gameplay=true;f.playerX=.5;f.playerY=.60;f.playerConfidence=.8;return f;}
    private Frame selected(long now,String tier){return frame(now,token("Lost Scrapyard",.7,.62),token(tier,.7,.67),token("Play",.7,.84));}
    private Frame result(long now){Frame f=frame(now,token("Continue",.5,.89));f.endScreen=true;return f;}

    @Test public void frozenTierArrowsAndSplitNameDoNotBlockPlay() {
        FarmEngine e=new FarmEngine(new Config());
        Frame f=frame(0,token("Lost",.7,.58),token("Scrapyard",.77,.62),token("tFrozen ★5t",.7,.66),token("PLAY",.7,.84));
        assertEquals(Kind.TAP,e.next(f).kind);
    }
    @Test public void numericFifteenAndWrongTierCannotAuthorizePlay() {
        assertEquals(Kind.PAUSE,new FarmEngine(new Config()).next(selected(0,"Frozen 15")).kind);
        assertEquals(Kind.PAUSE,new FarmEngine(new Config()).next(selected(0,"Frozen 6")).kind);
    }
    @Test public void pixelPlayRequiresVerifiedTargetAndKnownSelectedPanel() {
        FarmEngine e=new FarmEngine(new Config());
        Frame f=frame(0,token("Lost Scrapyard",.7,.62),token("Frozen ★5",.7,.66));
        f.playButton=f.selectedPanel=true;f.playX=.70;f.playY=.84;
        Action a=e.next(f);assertEquals(Kind.TAP,a.kind);assertEquals(.70,a.x,.001);
        e.reset(1000);f=frame(1000);f.playButton=f.selectedPanel=true;f.playX=.7;f.playY=.84;
        assertEquals(Kind.PAUSE,e.next(f).kind);
    }
    @Test public void missingTierFallbackIsSessionScopedAndRejectsChangedTier() {
        FarmEngine e=new FarmEngine(new Config());e.next(selected(0,"Frozen 5"));
        Frame f=selected(1000,"Frozen");f.selectedPanel=f.playButton=true;f.playX=.7;f.playY=.84;
        assertEquals(Kind.TAP,e.next(f).kind);assertEquals(Kind.PAUSE,e.next(selected(2000,"Frozen 6")).kind);
        e.reset(3000);f=selected(3000,"Frozen");f.selectedPanel=f.playButton=true;
        assertEquals(Kind.WAIT,e.next(f).kind);
    }
    @Test public void initialUnreadableTierWaitsForFreshOcrRatherThanPausingCorrectSelection() {
        FarmEngine e=new FarmEngine(new Config());Frame f=selected(0,"Frozen ★S");
        f.selectedPanel=f.playButton=true;assertEquals(Kind.WAIT,e.next(f).kind);
        assertEquals(Kind.TAP,e.next(selected(700,"Frozen ★5")).kind);
    }
    @Test public void resultsCrateAndSelectionRepeatBeyondOldRunAndTimeCaps() {
        Config c=new Config();c.maxRuns=1;c.maxSessionMinutes=1;FarmEngine e=new FarmEngine(c);
        for(int i=0;i<105;i++) {
            long now=i*20000L;assertEquals(Kind.MOVE,e.next(game(now)).kind);
            assertEquals(Kind.TAP,e.next(result(now+1000)).kind);
            Frame crate=frame(now+2000,token("Area on crate cooldown",.5,.3),token("Close",.5,.84));
            assertEquals(Kind.TAP,e.next(crate).kind);assertEquals(State.END_SCREEN,e.state());
            assertEquals(Kind.TAP,e.next(selected(now+3000,"Frozen 5t")).kind);
        }
        assertEquals(105,e.completedRuns());assertEquals(State.LEVEL_SELECT,e.state());
        e.stop();assertEquals(Kind.WAIT,e.next(game(2200000)).kind);
    }
    @Test public void observeKeepsOneMapWithoutDispatchingOrConsumingJumps() {
        FarmEngine e=new FarmEngine(new Config());
        for(int i=0;i<8;i++) {
            Frame f=game(i*500);f.enemyBoxes=new double[][]{{.6,.25,.65,.30,0,0}};
            assertEquals(Kind.WAIT,e.observe(f).kind);
        }
        assertEquals(7,e.navigationSnapshot().remainingJumps);assertEquals(1,e.navigationSnapshot().unresolvedEnemies);
    }
}