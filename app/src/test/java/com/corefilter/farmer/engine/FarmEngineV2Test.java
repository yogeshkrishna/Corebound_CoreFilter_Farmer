package com.corefilter.farmer.engine;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.corefilter.farmer.engine.FarmEngine.*;

public class FarmEngineV2Test {
    private static final String GAME = "com.Overcurve.Corebound";
    private Token token(String text, double x, double y) { return new Token(text,x-.03,y-.015,x+.03,y+.015); }
    private Frame frame(long now, Token... tokens) { return new Frame(now,GAME,"",Arrays.asList(tokens)); }
    private Frame game(long now) {
        Frame f=frame(now);f.gameplay=true;f.playerX=.50;f.playerY=.60;f.playerConfidence=.8;
        f.sceneSignature=(now % 1700)/2000.;return f;
    }
    private Frame selected(long now, String tier) {
        return frame(now,token("Lost Scrapyard",.7,.62),token(tier,.7,.67),token("Play",.7,.84));
    }
    private Frame result(long now) { Frame f=frame(now,token("Continue",.5,.89));f.endScreen=true;return f; }

    @Test public void sevenJumpsAreIssuedOnceAcrossAirborneFrames() {
        Config c=new Config();c.jumpBudget=7;
        FarmEngine e=new FarmEngine(c);int total=0;
        for(int i=0;i<12;i++) {
            Action a=e.next(game(i*500));
            assertEquals(Kind.MOVE,a.kind);assertTrue(a.durationMs<=700);
            total+=a.jumpCount;
        }
        assertEquals(7,total);
    }

    @Test public void timerAloneCannotRefillSpentHookshots() {
        FarmEngine e=new FarmEngine(new Config());
        for(int i=0;i<5;i++)e.next(game(i*500));
        assertEquals(0,e.next(game(15000)).jumpCount);
    }

    @Test public void twoGroundObservationsAfterAirtimeRefillBudget() {
        FarmEngine e=new FarmEngine(new Config());
        for(int i=0;i<5;i++)e.next(game(i*500));
        Frame ground=game(3500);ground.grounded=true;
        assertEquals(0,e.next(ground).jumpCount);
        ground=game(4000);ground.grounded=true;
        assertEquals(2,e.next(ground).jumpCount);
    }

    @Test public void groundWithoutObservedAirtimeCannotInventRefill() {
        FarmEngine e=new FarmEngine(new Config());int total=0;
        for(int i=0;i<15;i++) {
            Frame ground=game(i*500);ground.grounded=true;
            total+=e.next(ground).jumpCount;
        }
        assertEquals(7,total);
    }

    @Test public void lowConfidenceGroundDoesNotRefillBudget() {
        FarmEngine e=new FarmEngine(new Config());
        for(int i=0;i<5;i++)e.next(game(i*500));
        for(int i=5;i<10;i++) {
            Frame ground=game(i*500);ground.grounded=true;ground.playerConfidence=.1;
            assertEquals(0,e.next(ground).jumpCount);
        }
    }

    @Test public void confirmedCeilingStopsJumpingAndKeepsSweeping() {
        FarmEngine e=new FarmEngine(new Config());e.next(game(0));
        Frame f=game(500);f.ceilingReached=true;assertTrue(e.next(f).rise);
        f=game(1000);f.ceilingReached=true;
        Action sweep=e.next(f);assertEquals(Kind.MOVE,sweep.kind);assertEquals(0,sweep.jumpCount);
        assertTrue(sweep.reason.contains("Sweep"));
    }

    @Test public void buildWithMoreHookshotsChangesActualJumpBudget() {
        Config c=new Config();c.jumpBudget=11;FarmEngine e=new FarmEngine(c);int total=0;
        for(int i=0;i<10;i++)total+=e.next(game(i*500)).jumpCount;
        assertEquals(11,total);
    }

    @Test public void pursuesVisibleOverheadEnemyBehindThePlayer() {
        FarmEngine e=new FarmEngine(new Config());Frame f=game(0);
        f.enemyBoxes=new double[][]{{.2,.24,.27,.31,0,0}};
        Action a=e.next(f);assertEquals(-1,a.direction);assertTrue(a.jumpCount>0);
    }

    @Test public void missesAreRecheckedBrieflyWhenEnemyDisappears() {
        FarmEngine e=new FarmEngine(new Config());Frame f=game(0);
        f.enemyBoxes=new double[][]{{.2,.24,.27,.31,0,0}};e.next(f);
        Action a=e.next(game(500));assertEquals(-1,a.direction);assertTrue(a.reason.contains("last-seen"));
        a=e.next(game(1500));assertEquals(1,a.direction);
    }

    @Test public void burningEnemiesAreLeftToBurnWhileTraversing() {
        FarmEngine e=new FarmEngine(new Config());Frame f=game(0);
        f.enemyBoxes=new double[][]{{.2,.24,.27,.31,.9,0}};
        Action a=e.next(f);assertEquals(Kind.MOVE,a.kind);assertEquals(1,a.direction);
    }

    @Test public void contactPassMovesOnAndDreadnoughtPassLastsLonger() {
        Frame ordinary=game(0);ordinary.enemyBoxes=new double[][]{{.47,.56,.53,.64,0,0}};
        Action a=new FarmEngine(new Config()).next(ordinary);
        assertEquals(Kind.MOVE,a.kind);assertEquals(180,a.durationMs);
        Frame heavy=game(0);heavy.enemyBoxes=new double[][]{{.47,.56,.53,.64,0,.8}};
        Action b=new FarmEngine(new Config()).next(heavy);assertEquals(260,b.durationMs);
    }

    @Test public void gateSearchBacktracksThenRechecksWithoutIdleBurnWait() {
        FarmEngine e=new FarmEngine(new Config());Frame f=game(0);f.gate=true;f.gateX=.8;
        assertEquals(-1,e.next(f).direction);
        f=game(2000);f.gate=true;f.gateX=.8;assertEquals(1,e.next(f).direction);
        f=game(3500);f.gate=true;f.gateX=.8;assertEquals(-1,e.next(f).direction);
    }

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
        assertEquals(Kind.TAP,e.next(f).kind);
        assertEquals(Kind.PAUSE,e.next(selected(2000,"Frozen 6")).kind);
        e.reset(3000);f=selected(3000,"Frozen");f.selectedPanel=f.playButton=true;
        assertEquals(Kind.PAUSE,e.next(f).kind);
    }

    @Test public void resultsCrateAndSelectionRepeatBeyondOldRunAndTimeCaps() {
        Config c=new Config();c.maxRuns=1;c.maxSessionMinutes=1;
        FarmEngine e=new FarmEngine(c);
        for(int i=0;i<105;i++) {
            long now=i*20000L;
            assertEquals(Kind.MOVE,e.next(game(now)).kind);
            assertEquals(Kind.TAP,e.next(result(now+1000)).kind);
            Frame crate=frame(now+2000,token("Area on crate cooldown",.5,.3),token("Close",.5,.84));
            assertEquals(Kind.TAP,e.next(crate).kind);assertEquals(State.END_SCREEN,e.state());
            assertEquals(Kind.TAP,e.next(selected(now+3000,"Frozen 5t")).kind);
        }
        assertEquals(105,e.completedRuns());assertEquals(State.LEVEL_SELECT,e.state());
        e.stop();assertEquals(Kind.WAIT,e.next(game(2200000)).kind);
    }
}
