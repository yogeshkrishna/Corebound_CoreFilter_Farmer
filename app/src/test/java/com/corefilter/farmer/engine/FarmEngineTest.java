package com.corefilter.farmer.engine;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;
import static com.corefilter.farmer.engine.FarmEngine.*;

public class FarmEngineTest {
    private static final String GAME = "com.Overcurve.Corebound";
    private Token token(String text, double x, double y) { return new Token(text, x-.04, y-.02, x+.04, y+.02); }
    private Frame frame(long now, String text, Token... tokens) { return new Frame(now, GAME, text, Arrays.asList(tokens)); }
    private Frame game(long now) {
        Frame f = frame(now, ""); f.gameplay = true; f.playerX=.5;f.playerY=.60;f.playerConfidence=.9;
        f.cameraConfidence=.9;f.cameraX=now/50000.;f.cameraY=0;f.terrainCols=48;f.terrainRows=24;
        f.terrainCells=new byte[48*24];
        for(int y=4;y<=20;y++)for(int x=1;x<47;x++)f.terrainCells[y*48+x]=1;
        for(int x=1;x<47;x++)f.terrainCells[21*48+x]=2;
        f.enemyBoxes=new double[][]{{.615,.245,.665,.315,0,0}};
        return f;
    }
    private Frame end(long now, Token... tokens) { Frame f = frame(now, "Complete!", tokens); f.endScreen = true; return f; }

    @Test public void refusesUnrelatedForegroundApps() {
        FarmEngine e = new FarmEngine(new Config());
        Frame f = game(0); f.packageName = "com.bank.app";
        assertEquals(Kind.PAUSE, e.next(f).kind);
        assertEquals(Kind.WAIT, e.next(game(1000)).kind);
    }

    @Test public void onlySelectedRightPanelCanAuthorizeFrozenFive() {
        FarmEngine e = new FarmEngine(new Config());
        Frame f = frame(0, "Lost Scrapyard Frozen 5", token("Lost Scrapyard", .2, .3),
                token("Frozen 5", .2, .4), token("Crystal Quarry", .7, .5), token("Play", .7, .8));
        assertEquals(Kind.PAUSE, e.next(f).kind);
        e.reset(1000);
        f = frame(1000, "", token("Lost Scrapyard", .7, .6), token("Frozen ★5", .7, .65), token("Play", .7, .85));
        Action a = e.next(f);
        assertEquals(Kind.TAP, a.kind); assertEquals(.7, a.x, .001);
    }

    @Test public void rejectsStaleCapturesAndDuplicateFrames() {
        FarmEngine e = new FarmEngine(new Config());
        Frame f = game(0);
        assertEquals(Kind.MOVE, e.next(f).kind);
        f.now = 1000;
        assertEquals(Kind.WAIT, e.next(f).kind);
        f.now = 2000;
        assertEquals(Kind.PAUSE, e.next(f).kind);
    }

    @Test public void captureFailureStopsActions() {
        FarmEngine e = new FarmEngine(new Config());
        Frame f = game(0); f.captureOk = false;
        assertEquals(Kind.PAUSE, e.next(f).kind);
    }

    @Test public void freshFrameDoesNotInterruptOutstandingGesture() {
        Config c = new Config(); c.moveMs = 700;
        FarmEngine e = new FarmEngine(c);
        assertEquals(Kind.MOVE, e.next(game(0)).kind);
        assertEquals(Kind.WAIT, e.next(game(100)).kind);
    }

    @Test public void ceilingCheckUsesOneJumpThenFreshObservedMotion() {
        FarmEngine e = new FarmEngine(new Config());
        Action a = e.next(game(0));
        assertEquals(Kind.MOVE, a.kind); assertEquals(1, a.direction); assertTrue(a.rise);
        assertEquals(1, a.jumpCount); assertEquals(500, a.jumpSpacingMs);
        Frame rising=game(700);rising.playerY=.50;
        assertTrue(e.next(rising).jumpCount<=1);
        Frame next=game(1400);next.playerY=.53;
        assertTrue(e.next(next).jumpCount<=1);
    }

    @Test public void killingOrLootingFilterDoesNotAuthorizeRewardAd() {
        FarmEngine e = new FarmEngine(new Config()); e.next(game(0));
        Frame f = end(1000, token("Watch reward", .7, .88), token("Continue", .34, .88));
        f.filterLoot = true;
        Action a = e.next(f);
        assertEquals(Kind.TAP, a.kind); assertEquals(.34, a.x, .001);
        assertEquals(1, e.completedRuns()); assertEquals(0, e.adsWatched());
    }

    @Test public void explicitFilterOfferPlusVisibleWatchButtonAuthorizesAd() {
        FarmEngine e = new FarmEngine(new Config()); e.next(game(0));
        Frame f = end(1000, token("Watch reward", .7, .88), token("Continue", .34, .88)); f.filterOffer = true;
        Action a = e.next(f);
        assertEquals(.7, a.x, .001); assertEquals(State.REWARD_AD, e.state());
    }

    @Test public void disabledRewardAdsSkipEvenExplicitOffer() {
        Config c = new Config(); c.watchFilterAds = false;
        FarmEngine e = new FarmEngine(c); e.next(game(0));
        Frame f = end(1000, token("Watch reward", .7, .88), token("Continue", .34, .88)); f.filterOffer = true;
        assertEquals(.34, e.next(f).x, .001);
    }

    @Test public void rewardOfferWithoutButtonCannotCauseBlindTap() {
        FarmEngine e = new FarmEngine(new Config()); e.next(game(0));
        Frame f = end(1000); f.filterOffer = true;
        assertEquals(Kind.WAIT, e.next(f).kind);
    }

    @Test public void unknownScreenNeverTriggersCornerTapAndEventuallyPauses() {
        FarmEngine e = new FarmEngine(new Config());
        assertEquals(Kind.WAIT, e.next(frame(0, "", token("X", .94, .08))).kind);
        assertEquals(Kind.PAUSE, e.next(frame(16000, "", token("X", .94, .08))).kind);
    }

    @Test public void adCountdownBlocksCloseAndSupportsTwoStageClose() {
        FarmEngine e = new FarmEngine(new Config());
        Frame f = frame(0, "Advertisement Reward in 5 seconds", token("X", .95, .06)); f.observedAd = true;
        assertEquals(Kind.WAIT, e.next(f).kind);
        f = frame(6000, "Advertisement", token("X", .95, .06)); f.observedAd = true;
        assertEquals(Kind.TAP, e.next(f).kind);
        f = frame(8000, "", token("Close", .94, .07));
        assertEquals(Kind.TAP, e.next(f).kind);
        assertEquals(State.INTERSTITIAL, e.state());
    }

    @Test public void countdownBareNumberAtTopAlsoBlocksAdClose() {
        FarmEngine e = new FarmEngine(new Config());
        Frame f = frame(0, "", token("5", .1, .06), token("X", .95, .06)); f.observedAd = true;
        assertEquals(Kind.WAIT, e.next(f).kind);
    }

    @Test public void storeBackRequiresObservedAdProvenance() {
        FarmEngine e = new FarmEngine(new Config());
        Frame store = frame(0, "Install"); store.packageName = "com.android.vending";
        assertEquals(Kind.PAUSE, e.next(store).kind);
        e.reset(1000);
        Frame ad = frame(1000, "Advertisement"); ad.observedAd = true;
        e.next(ad);
        store = frame(2000, "Install"); store.packageName = "com.android.vending";
        assertEquals(Kind.BACK, e.next(store).kind);
    }

    @Test public void rewardAdCountRequiresObservedAdAndReturnToGame() {
        Config c = new Config(); c.adMinWatchMs = 0;
        FarmEngine e = new FarmEngine(c); e.next(game(0));
        Frame f = end(1000, token("Watch reward", .7, .88)); f.filterOffer = true;
        e.next(f);
        f = frame(3000, "Advertisement"); f.observedAd = true; e.next(f);
        assertEquals(0, e.adsWatched());
        f = frame(4000, "", token("X", .94, .08)); e.next(f);
        e.next(end(6000, token("Continue", .34, .88)));
        assertEquals(1, e.adsWatched());
        e.next(end(7000, token("Continue", .34, .88)));
        assertEquals(1, e.completedRuns()); assertEquals(1, e.adsWatched());
    }

    @Test public void rewardedAdWithoutRecognizedCloseTimesOutWithoutTap() {
        Config c = new Config(); c.adTimeoutMs = 10000;
        FarmEngine e = new FarmEngine(c);
        Frame f = frame(0, "Advertisement"); f.observedAd = true; e.next(f);
        assertEquals(Kind.PAUSE, e.next(frame(11000, "Install now")).kind);
    }

    @Test public void closeCrateModalDoesNotInventACompletedRun() {
        FarmEngine e = new FarmEngine(new Config()); e.next(game(0));
        Action a = e.next(frame(1000, "Area on Crate Cooldown", token("Close", .52, .82)));
        assertEquals(Kind.TAP, a.kind); assertEquals(0, e.completedRuns());
    }

    @Test public void runAndSessionLimitsStopFurtherMovement() {
        Config c = new Config(); c.maxRunSeconds = 15;
        FarmEngine e = new FarmEngine(c); e.next(game(0));
        assertEquals(Kind.PAUSE, e.next(game(15000)).kind);
        c = new Config(); c.continuousFarm = false; c.maxSessionMinutes = 1;
        e = new FarmEngine(c); e.next(game(0));
        assertEquals(Kind.PAUSE, e.next(game(60000)).kind);
    }

    @Test public void maximumCompletedRunsAllowsRewardThenStopsReplay() {
        Config c = new Config(); c.continuousFarm = false; c.maxRuns = 1;
        FarmEngine e = new FarmEngine(c); e.next(game(0));
        assertEquals(Kind.TAP, e.next(end(1000, token("Continue", .34, .88))).kind);
        assertEquals(Kind.PAUSE, e.next(end(2000, token("Retry", .7, .88))).kind);
        assertEquals(1, e.completedRuns());
    }

    @Test public void gatesSearchImmediatelyButTimeoutIsBounded() {
        Config c = new Config(); c.gateTimeoutMs = 3000;
        FarmEngine e = new FarmEngine(c);
        Frame f = game(0); f.gate = true;
        assertEquals(Kind.MOVE, e.next(f).kind);
        f = game(1000); f.gate = true;
        Action a = e.next(f); assertEquals(Kind.MOVE, a.kind); assertTrue(a.jumpCount<=1);
        f = game(4000); f.gate = true;
        assertEquals(Kind.PAUSE, e.next(f).kind);
    }

    @Test public void stationaryBodyRecoveryIgnoresAnimatedSceneBrightnessAndIsBounded() {
        Config c = new Config(); c.stuckTimeoutMs = 3000; c.maxRecoveries = 1;
        FarmEngine e = new FarmEngine(c);
        Action a=null;
        for(int i=0;i<35;i++) {
            Frame f=game(i*350);f.cameraX=0;f.sceneSignature=i%2==0?.1:.8;
            a=e.next(f);if(a.kind==Kind.PAUSE)break;
        }
        assertEquals(Kind.PAUSE,a.kind);
    }

    @Test public void deathRetriesWithoutCountingASuccess() {
        FarmEngine e = new FarmEngine(new Config()); e.next(game(0));
        Action a = e.next(frame(1000, "Defeated", token("Retry", .7, .88)));
        assertEquals(Kind.TAP, a.kind); assertEquals(0, e.completedRuns()); assertEquals(1, e.deaths());
    }

    @Test public void stopIsImmediateAndResetStartsANewSession() {
        FarmEngine e = new FarmEngine(new Config()); e.next(game(0)); e.stop();
        assertEquals(Kind.WAIT, e.next(game(1000)).kind);
        e.reset(2000); assertEquals(Kind.MOVE, e.next(game(2000)).kind);
    }

    @Test public void unrelatedBoostPanelCannotAuthorizeWrongSelectedTier(){
        FarmEngine e=new FarmEngine(new Config());
        Frame f=frame(0,"",token("Frozen 5",.7,.4),token("Lost Scrapyard",.7,.6),token("Pure 3",.7,.65),token("Play",.7,.85));
        assertEquals(Kind.PAUSE,e.next(f).kind);
    }

    @Test public void visibleLeftGateChangesTraversalDirection(){
        FarmEngine e=new FarmEngine(new Config());Frame f=game(0);f.gate=true;f.gateX=.2;f.playerX=.6;f.enemyBoxes=new double[0][];e.next(f);
        f=game(2500);f.gate=true;f.gateX=.2;f.playerX=.6;
        f.enemyBoxes=new double[][]{{.2,.3,.25,.36,0,0}};
        assertEquals(-1,e.next(f).direction);
    }

    @Test public void resultAnimationTapsRequireRecognizedResults(){
        FarmEngine e=new FarmEngine(new Config());assertEquals(Kind.WAIT,e.next(frame(0,"Loading")).kind);
        Action a=e.next(end(1000));assertEquals(Kind.TAP,a.kind);assertTrue(a.y<.5);
    }

    @Test public void storeRecoveryCannotLoopForever(){
        FarmEngine e=new FarmEngine(new Config());Frame ad=frame(0,"Advertisement");ad.observedAd=true;e.next(ad);
        for(int i=1;i<=3;i++){Frame f=frame(i*2000,"Install");f.packageName="com.android.vending";assertEquals(Kind.BACK,e.next(f).kind);}
        Frame f=frame(8000,"Install");f.packageName="com.android.vending";assertEquals(Kind.PAUSE,e.next(f).kind);
    }

    @Test public void failedAdLaunchDoesNotDiscardTheFilterOffer(){
        FarmEngine e=new FarmEngine(new Config());e.next(game(0));Frame offer=end(1000,token("Watch reward",.7,.88),token("Continue",.34,.88));offer.filterOffer=true;e.next(offer);
        offer=end(4000,token("Watch reward",.7,.88),token("Continue",.34,.88));offer.filterOffer=true;assertEquals(Kind.WAIT,e.next(offer).kind);
        offer=end(17000,token("Watch reward",.7,.88),token("Continue",.34,.88));offer.filterOffer=true;assertEquals(Kind.PAUSE,e.next(offer).kind);assertEquals(0,e.adsWatched());
    }
}
