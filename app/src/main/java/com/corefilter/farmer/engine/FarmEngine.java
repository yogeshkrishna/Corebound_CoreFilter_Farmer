package com.corefilter.farmer.engine;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Screen-driven farming policy. All coordinates are normalized to the captured display.
 * Gameplay is delegated to a persistent, camera-registered terrain and target planner.
 * The caller must supply a new screenshot timestamp and dispatch only the returned action.
 */
public final class FarmEngine {
    public enum Kind { WAIT, MOVE, TAP, BACK, PAUSE }
    public enum State { IDLE, LEVEL_SELECT, GAMEPLAY, END_SCREEN, REWARD_AD, INTERSTITIAL, PAUSED, STOPPED }

    public static final class Token {
        public final String text;
        public final double left, top, right, bottom;
        public Token(String text, double left, double top, double right, double bottom) {
            this.text = text == null ? "" : text;
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }
        public double x() { return (left + right) / 2; }
        public double y() { return (top + bottom) / 2; }
    }

    public static final class Frame {
        public long now, capturedAt;
        public String packageName = "", text = "";
        public List<Token> tokens = Collections.emptyList();
        public boolean captureOk = true, gameplay, gate, filterLoot, filterOffer;
        public boolean observedAd, endScreen, crateScreen, targetSelected, uncertainFilterOffer;
        public boolean grounded, groundContactCandidate, ceilingReached, playButton, selectedPanel;
        public double playerConfidence, playX = -1, playY = -1;
        public int terrainCols, terrainRows, registrationEpoch, completedSector;
        /** Fresh HUD reading. Unknown is -1; zero applies to ordinary sector enemies only. */
        public int remainingEnemies = -1;
        public long remainingEnemiesCapturedAt = -1;
        public double remainingEnemiesConfidence;
        /** Normalized x/y units require this aspect ratio for faithful map rendering. */
        public double viewportAspectRatio = Double.NaN;
        /** Row-major occupancy: 0 = unobserved/occluded, 1 = free, 2 = solid. */
        public byte[] terrainCells = new byte[0];
        public double cameraDx, cameraDy, cameraX = Double.NaN, cameraY = Double.NaN, cameraConfidence;
        public boolean registrationReset, registrationLost, sceneChanged, wallLeft, wallRight;
        public double playerLeft = -1, playerTop = -1, playerRight = -1, playerBottom = -1;
        /** Candidate boxes [left, top, right, bottom, burning, dreadnought confidence]. */
        public double[][] enemyBoxes = new double[0][];
        public double healthFraction = -1, playerX = -1, playerY = -1, gateX = -1, gateY = -1, sceneSignature = Double.NaN;
        public Frame() { }
        public Frame(long now, String packageName, String text, List<Token> tokens) {
            this.now = now; this.capturedAt = now; this.packageName = packageName;
            this.text = text == null ? "" : text;
            this.tokens = tokens == null ? Collections.emptyList() : new ArrayList<>(tokens);
        }
    }

    public static final class Config {
        public String gamePackage = "com.Overcurve.Corebound";
        public long moveMs = 420, riseMs = 70, jumpTapMs = 70, settleMs = 180;
        public int jumpBudget = 7;
        public long jumpSpacingMs = 350;
        public long ceilingEveryMs = 3500, jumpIntervalMs = 850, ceilingScanMs = 2200;
        public long maxRunSeconds = 180, maxSessionMinutes = 30, maxRuns = 100;
        public long staleFrameMs = 1500, unknownTimeoutMs = 15000, stuckTimeoutMs = 12000;
        public long gateTimeoutMs = 25000, adTimeoutMs = 120000, adMinWatchMs = 30000;
        public int maxRecoveries = 3;
        public boolean watchFilterAds = true, allowStartInGameplay = true, continuousFarm = true;
    }

    public static final class Action {
        public final Kind kind;
        public final double x, y;
        public final int direction;
        /** Compatibility flag: at least one separate jump tap accompanies movement. */
        public final boolean rise;
        public final int jumpCount;
        public final long jumpSpacingMs;
        public final long durationMs;
        public final String reason;
        private Action(Kind kind, double x, double y, int direction, boolean rise, long duration, String reason) {
            this(kind, x, y, direction, rise ? 1 : 0, 190, duration, reason);
        }
        private Action(Kind kind, double x, double y, int direction, int jumpCount, long jumpSpacingMs, long duration, String reason) {
            this.kind = kind; this.x = x; this.y = y; this.direction = direction;
            this.jumpCount = jumpCount; this.jumpSpacingMs = jumpSpacingMs;
            this.rise = jumpCount > 0; this.durationMs = duration; this.reason = reason;
        }
        public static Action waitFor(String reason) { return new Action(Kind.WAIT, 0, 0, 0, false, 0, reason); }
    }

    /** Immutable end-of-run map, captured before another run can reset the navigator. */
    public static final class RunMap {
        public final MapNavigator.Snapshot snapshot;
        public final String outcome;
        public final long runNumber, endedAt;
        private RunMap(MapNavigator.Snapshot snapshot, String outcome, long runNumber, long endedAt) {
            this.snapshot = snapshot; this.outcome = outcome;
            this.runNumber = runNumber; this.endedAt = endedAt;
        }
    }

    private static final Pattern COUNTDOWN = Pattern.compile("(?:skip|close|reward|continue|ad ends?|remaining).{0,20}\\b\\d{1,3}\\s*(?:s|sec|seconds)?\\b|\\b\\d{1,3}\\s*(?:s|sec|seconds)\\b");
    private final Config config;
    private final MapNavigator navigator;
    private final ArrayDeque<RunMap> finishedMaps = new ArrayDeque<>();
    private State state = State.IDLE;
    private String status = "Ready";
    private long sessionStart = -1, runStart = -1, lastCapture = -1, lastNow = -1;
    private long busyUntil, unknownSince = -1, adSince = -1;

    private long completedRuns, deaths, adsWatched;

    private int storeBacks, animationTaps;
    private boolean inRun, resultCounted, rewardRequested, observedAd, stopAfterResult;
    private boolean verifiedSelection;
    private boolean runStartRequested;


    public FarmEngine(Config config) {
        this.config = config == null ? new Config() : config;
        this.navigator = new MapNavigator(this.config);
    }
    public synchronized MapNavigator.Snapshot navigationSnapshot() { return navigator.snapshot(); }
    public synchronized RunMap takeFinishedMap() { return finishedMaps.pollFirst(); }
    /** Capture adapter resets registration only for an actual authorized Play/Retry tap. */
    public synchronized boolean takeRunStartRequest() {
        boolean requested=runStartRequested;runStartRequested=false;return requested;
    }
    /** Read-only planning preview: no menu taps, hypothetical jumps or emitted action cooldowns. */
    public synchronized Action observe(Frame f) {
        if (f == null || !f.captureOk || !config.gamePackage.equals(f.packageName)) {
            status = "Observe: waiting for a readable Corebound screen";
            return Action.waitFor(status);
        }
        if (!f.gameplay) {
            status = "Observe: waiting for gameplay";
            return Action.waitFor(status);
        }
        if (!inRun) startRun(f.now);
        state = State.GAMEPLAY;
        MapNavigator.Decision d = navigator.observe(f);
        status = "Observe: " + d.reason;
        return Action.waitFor(status);
    }
    public synchronized State state() { return state; }
    public synchronized String status() { return status; }
    public synchronized int runs() { return (int) Math.min(Integer.MAX_VALUE, completedRuns); }
    public synchronized int completedRuns() { return runs(); }
    public synchronized int adsWatched() { return (int) Math.min(Integer.MAX_VALUE, adsWatched); }
    public synchronized int deaths() { return (int) Math.min(Integer.MAX_VALUE, deaths); }
    public synchronized void stop() {
        if(inRun&&!resultCounted){
            navigator.finish(false);
            MapNavigator.Snapshot snapshot=navigator.snapshot();
            if(snapshot.mapCells>0||snapshot.controlTrace.length>0)
                finishedMaps.addLast(new RunMap(snapshot,"interrupted",completedRuns+deaths+1,Math.max(0,lastNow)));
            resultCounted=true;inRun=false;
        }
        state = State.STOPPED; status = "Stopped"; busyUntil = 0;
    }

    public synchronized void reset(long now) {
        state = State.IDLE; status = "Ready"; sessionStart = now; runStart = -1;
        lastCapture = -1; lastNow = -1; busyUntil = 0; unknownSince = adSince = -1;

        completedRuns = deaths = adsWatched = 0; storeBacks=animationTaps=0;

        inRun = resultCounted = rewardRequested = observedAd = stopAfterResult = false;
        verifiedSelection = false; resetNavigation();
        runStartRequested = false;
    }

    public synchronized Action next(Frame f) {
        if (state == State.PAUSED || state == State.STOPPED) return Action.waitFor(status);
        if (f == null || !f.captureOk) return pause("Screen capture unavailable; no taps sent");
        if (f.now < 0 || f.capturedAt < 0 || f.capturedAt > f.now || (lastNow >= 0 && f.now < lastNow))
            return pause("Invalid screen clock; restart capture");
        lastNow = f.now;
        if (sessionStart < 0) sessionStart = f.now;
        if (!config.continuousFarm && f.now - sessionStart >= clamp(config.maxSessionMinutes, 1, 1440) * 60000)
            return pause("Session time limit reached");
        if (f.now - f.capturedAt > clamp(config.staleFrameMs, 250, 5000))
            return pause("Screen frame is stale; resume after capture recovers");
        if (f.capturedAt <= lastCapture) return Action.waitFor("Waiting for a fresh screenshot");
        lastCapture = f.capturedAt;

        String pkg = f.packageName == null ? "" : f.packageName;
        if (!pkg.equals(config.gamePackage)) {
            if (pkg.equals("com.android.vending") && isAdState() && observedAd) {
                if (f.now < busyUntil) return Action.waitFor("Returning from Google Play");
                if(++storeBacks>3 || f.now-adSince>config.adTimeoutMs) return pause("Google Play did not return to the ad; return to Corebound manually");
                return emit(new Action(Kind.BACK, 0, 0, 0, false, 0, "Return from ad-opened Google Play"), f.now, 1500);
            }
            return pause(pkg.isEmpty() ? "Cannot identify the foreground app" : "Another app is open; farming paused");
        }
        if (f.now < busyUntil) return Action.waitFor("Waiting for the last action to finish");
        String text = normalized(allText(f));
        if (isAdState()) return handleAd(f, text);

        boolean death = has(text, "you died", "defeated", "run failed", "robot destroyed", "you were destroyed");
        boolean end = f.endScreen || completeTitle(f) || death || has(text, "run complete", "level complete", "victory", "level cleared", "run finished");
        Token retry = button(f, "retry", "replay", "play again", "try again");
        boolean rewardScreen = f.filterOffer || button(f, "watch ad", "watch", "watch video", "watch reward") != null;
        boolean crate = f.crateScreen || has(text, "crate rewards", "crate opened", "crate contents", "crate cooldown");
        if (end || crate || (inRun && retry != null && !f.gameplay) || (state == State.END_SCREEN && rewardScreen))
            return handleEnd(f, text, death, crate);

        // Recognize results/crates before treating an unlabelled Close as an ad stage.
        // A crate Close was previously misclassified as an interstitial during repeat runs.
        if (f.observedAd || has(text, "advertisement", "sponsored", "skip video", "skip ad", "ad ends in")
                || (inRun && !f.gameplay && !selectedTarget(f) && adClose(f) != null)) {
            beginAd(f.now, false); observedAd = true; return handleAd(f, text);
        }

        if (f.gameplay) {
            if (!inRun) {
                if (state == State.IDLE && !config.allowStartInGameplay) return pause("Start from Lost Scrapyard Frozen five-star selection");
                startRun(f.now);
            }
            state = State.GAMEPLAY; unknownSince = -1;
            return navigate(f);
        }

        if (selectedTarget(f)) {
            state = State.LEVEL_SELECT; inRun = false; unknownSince = -1;
            if (limitReached()) return pause("Requested run limit reached");
            Token play = rightButton(f, "play", "start", "enter", "deploy");
            if (play == null && f.playButton && f.selectedPanel)
                play = new Token("Play", f.playX, f.playY, f.playX, f.playY);
            if (play != null) {
                resultCounted = false;
                return tapRun(play, f.now, "Start Lost Scrapyard at Frozen five stars", 800);
            }
            return waiting(f.now, "Target selected; waiting for Play button");
        }
        if ((rightButton(f, "play", "start", "enter", "deploy") != null || f.playButton) && !f.gameplay) {
            if (unreadableTargetTier(f)) return waiting(f.now, "Lost Scrapyard Frozen panel found; reading the selected star tier");
            return pause("Select Lost Scrapyard with Frozen five-star boost before starting");
        }
        return waiting(f.now, "Screen not recognized; waiting without tapping");
    }

    private Action navigate(Frame f) {
        if (f.now - runStart >= clamp(config.maxRunSeconds, 15, 3600) * 1000)
            return pause("Run time limit reached; inspect the current room");
        if (f.healthFraction >= 0 && f.healthFraction <= 0.01)
            return pause("Health is empty; inspect the death screen before resuming");
        MapNavigator.Decision d = navigator.next(f);
        // The room controller owns passage/turn progress. A visible gate is not
        // a reason to wait for its animation or start a second arbitrary timer.
        if (d.pause) return pause(d.reason);
        if (d.direction == 0 && d.jumps == 0) return waiting(f.now, d.reason);
        unknownSince = -1;
        return move(d.direction, d.jumps, d.durationMs, f.now, d.reason);
    }
    private Action handleEnd(Frame f, String text, boolean death, boolean crate) {
        if (state != State.END_SCREEN) unknownSince = -1;
        state = State.END_SCREEN;
        if (inRun && !resultCounted && !crate) {
            if (death) deaths++; else completedRuns++;
            navigator.finish(!death);
            finishedMaps.addLast(new RunMap(navigator.snapshot(), death ? "defeat" : "cleared",
                    completedRuns + deaths, f.now));
            resultCounted = true; stopAfterResult = limitReached();
        }
        Token watch = button(f, "watch ad", "watch", "watch video", "watch for bonus", "watch reward");
        if(config.watchFilterAds&&f.uncertainFilterOffer&&!rewardRequested)return pause("Possible rare filter reward; inspect the offer and choose the ad manually");
        // filterLoot and an inferred kill are deliberately insufficient authorization signals.
        if (config.watchFilterAds && f.filterOffer && watch != null && !rewardRequested) {
            beginAd(f.now, true);
            return tap(watch, f.now, "Watch the explicitly offered core-filter reward", 1500);
        }
        Token close = button(f, "close", "continue", "collect", "done", "no thanks");
        if (close != null) return tap(close, f.now, crate ? "Close collected crate rewards" : "Continue from run results", 800);
        Token retry = button(f, "retry", "replay", "play again", "try again");
        if (retry != null) {
            if (stopAfterResult || limitReached()) return pause("Requested run limit reached; rewards processed");
            inRun = false; resultCounted = false; rewardRequested = false;
            return tapRun(retry, f.now, death ? "Retry after defeat" : "Replay the same selected level", 1600);
        }
        if(!crate&&!f.filterOffer&&watch==null&&animationTaps<3){animationTaps++;return tap(new Token("Speed results",.48,.32,.52,.38),f.now,"Speed up the confirmed result animation",400);}
        return waiting(f.now, "Results recognized; waiting for a labeled reward, Close or Retry button");
    }

    private void beginAd(long now, boolean reward) {
        state = reward ? State.REWARD_AD : State.INTERSTITIAL;
        adSince = now; rewardRequested = reward; observedAd = false; unknownSince = -1;storeBacks=0;
    }

    private Action handleAd(Frame f, String text) {
        boolean gameReturned = f.gameplay || f.endScreen || f.crateScreen || selectedTarget(f)
                || (!f.observedAd && button(f, "retry", "replay", "play again") != null);
        boolean adSignal = f.observedAd || has(text, "advertisement", "sponsored", "skip video", "skip ad", "ad ends in");
        if(gameReturned&&!adSignal&&rewardRequested&&!observedAd){
            if(f.now-adSince>15000)return pause("Filter reward ad did not open; retry the offer manually");
            return Action.waitFor("Waiting for the filter reward ad to open");
        }
        if (gameReturned && !adSignal && (observedAd || f.now - adSince > 2500)) {
            if (rewardRequested && observedAd) adsWatched++;
            observedAd = false; adSince = -1;
            state = inRun ? State.END_SCREEN : State.IDLE;
            // Preserve rewardRequested until the next run to avoid buying another ad on the same offer.
            return Action.waitFor("Returned to the game; re-check rewards on a fresh screenshot");
        }
        if (f.now - adSince > clamp(config.adTimeoutMs, 10000, 600000))
            return pause("Ad timeout; close this ad manually, then resume");
        if (adSignal || !gameReturned) observedAd = true;
        if (hasCountdown(f, text)) return Action.waitFor("Ad countdown is still visible");
        if (rewardRequested && f.now - adSince < clamp(config.adMinWatchMs, 0, 120000))
            return Action.waitFor("Waiting for the rewarded ad to complete");
        Token close = adClose(f);
        if (close != null) return tap(close, f.now, "Close the current ad stage; verify the next screen", 1500);
        return Action.waitFor("Ad active; waiting for a recognized close control");
    }

    private boolean hasCountdown(Frame f, String text) {
        if (COUNTDOWN.matcher(text).find()) return true;
        for (Token t : tokens(f)) {
            String s = normalized(t.text);
            if (t.top < .22 && s.matches("\\d{1,2}")) {
                int value = Integer.parseInt(s);
                if (value > 0 && value <= 90) return true;
            }
        }
        return false;
    }

    private Token adClose(Frame f) {
        for (Token t : tokens(f)) {
            String s = normalized(t.text);
            if (s.equals("close ad") || s.equals("close") || s.equals("dismiss ad")) return t;
            if ((s.equals("x") || s.equals("×") || s.equals("✕") || s.equals("✖")
                    || s.equals("skip") || s.equals("skip ad") || s.equals("skip video"))
                    && (t.top < .25 || t.left > .75 || t.right < .25)) return t;
        }
        return null;
    }

    private boolean selectedTarget(Frame f) {
        if (f.targetSelected) { verifiedSelection = true; return true; }
        StringBuilder right = new StringBuilder();
        for (Token t : tokens(f)) if (t.x() >= .48 && t.top >= .52) right.append(' ').append(t.text);
        String s = normalized(right.toString());
        // OCR often attaches the tier arrows to the digit (5t/t5); a word boundary loses it.
        boolean five = Pattern.compile("(?<![0-9])5(?![0-9])").matcher(s).find()
                || s.contains("★★★★★") || s.contains("☆☆☆☆☆") || s.contains("five");
        boolean name = s.replace(" ", "").contains("lostscrapyard");
        boolean frozen = s.replace(" ", "").contains("frozen");
        if (name && frozen && five) { verifiedSelection = true; return true; }
        // After one verified selection, tolerate a missing digit only on the same named panel.
        // Any explicit other numeric tier invalidates it, and this trust is cleared on reset.
        if (name && frozen && Pattern.compile("[0-9]").matcher(s).find()) verifiedSelection = false;
        return verifiedSelection && name && frozen && f.selectedPanel && f.playButton;
    }

    private boolean unreadableTargetTier(Frame f) {
        if (!f.selectedPanel || !f.playButton) return false;
        StringBuilder panel = new StringBuilder();
        for (Token t : tokens(f)) if (t.x() >= .48 && t.top >= .52) panel.append(' ').append(t.text);
        String text = normalized(panel.toString()).replace(" ", "");
        return text.contains("lostscrapyard") && text.contains("frozen")
                && !Pattern.compile("[0-9]").matcher(text).find();
    }

    private boolean completeTitle(Frame f) {
        for (Token t : tokens(f)) if (t.y() < .25 && normalized(t.text).matches("complete!?")) return true;
        return false;
    }

    private boolean isAdState() { return state == State.REWARD_AD || state == State.INTERSTITIAL; }
    private boolean limitReached() { return !config.continuousFarm && completedRuns >= clamp(config.maxRuns, 1, 100000); }
    private void startRun(long now) {
        inRun = true; resultCounted = false; rewardRequested = false; stopAfterResult = false;
        animationTaps=0;
        runStart = now;
        resetNavigation();
    }
    private void resetNavigation() {
        navigator.reset();
    }
    private Action waiting(long now, String reason) {
        if (unknownSince < 0) unknownSince = now;
        if (now - unknownSince > clamp(config.unknownTimeoutMs, 3000, 120000)) return pause(reason + "; manual check needed");
        status = reason; return Action.waitFor(reason);
    }
    private Action pause(String reason) { state = State.PAUSED; status = reason; return new Action(Kind.PAUSE, 0, 0, 0, false, 0, reason); }
    private Action move(int direction, int jumps, long duration, long now, String reason) {
        long spacing = clamp(config.jumpSpacingMs, 250, 1200);
        long actualDuration = Math.min(700, Math.max(duration, jumps > 0
                ? (jumps - 1) * spacing + clamp(config.jumpTapMs, 30, 120) : 0));
        return emit(new Action(Kind.MOVE, 0, 0, direction, jumps, spacing, actualDuration, reason), now, actualDuration);
    }
    private Action tap(Token t, long now, String reason, long cooldown) {
        if (!Double.isFinite(t.x()) || !Double.isFinite(t.y()) || t.x() < 0 || t.x() > 1 || t.y() < 0 || t.y() > 1)
            return pause("Invalid target coordinates; recalibrate the screen");
        return emit(new Action(Kind.TAP, t.x(), t.y(), 0, false, 70, reason), now, cooldown);
    }
    private Action tapRun(Token t,long now,String reason,long cooldown) {
        Action action=tap(t,now,reason,cooldown);
        if(action.kind==Kind.TAP)runStartRequested=true;
        return action;
    }
    private Action emit(Action a, long now, long cooldown) { status = a.reason; busyUntil = now + cooldown; return a; }
    private Token button(Frame f, String... labels) {
        for (Token t : tokens(f)) for (String label : labels) if (normalized(t.text).equals(label)) return t;
        return null;
    }
    private Token rightButton(Frame f, String... labels) {
        for (Token t : tokens(f)) if (t.x() >= .48) for (String label : labels) if (normalized(t.text).equals(label)) return t;
        return null;
    }
    private static List<Token> tokens(Frame f) { return f.tokens == null ? Collections.emptyList() : f.tokens; }
    private static String allText(Frame f) {
        StringBuilder s = new StringBuilder(f.text == null ? "" : f.text);
        for (Token t : tokens(f)) s.append(' ').append(t.text);
        return s.toString();
    }
    private static String normalized(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " "); }
    private static boolean has(String s, String... values) { for (String value : values) if (s.contains(value)) return true; return false; }
    private static long clamp(long value, long min, long max) { return Math.max(min, Math.min(max, value)); }
}
