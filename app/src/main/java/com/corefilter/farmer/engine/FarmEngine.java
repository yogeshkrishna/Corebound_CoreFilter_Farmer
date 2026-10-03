package com.corefilter.farmer.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Screen-driven farming policy. All coordinates are normalized to the captured display.
 * This is a bounded navigation heuristic, not a map solver or a guaranteed Spectrum detector.
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
        public boolean grounded, ceilingReached, playButton, selectedPanel;
        public double playerConfidence, playX = -1, playY = -1;
        /** Candidate boxes [left, top, right, bottom, burning, dreadnought confidence]. */
        public double[][] enemyBoxes = new double[0][];
        public double healthFraction = -1, playerX = -1, playerY = -1, gateX = -1, sceneSignature = Double.NaN;
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
        public long jumpSpacingMs = 190;
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

    private static final Pattern COUNTDOWN = Pattern.compile("(?:skip|close|reward|continue|ad ends?|remaining).{0,20}\\b\\d{1,3}\\s*(?:s|sec|seconds)?\\b|\\b\\d{1,3}\\s*(?:s|sec|seconds)\\b");
    private final Config config;
    private State state = State.IDLE;
    private String status = "Ready";
    private long sessionStart = -1, runStart = -1, lastCapture = -1, lastNow = -1;
    private long busyUntil, unknownSince = -1, gateSince = -1, adSince = -1;
    private long lastJump = -100000, lastCeiling = -100000, scanUntil, lastProgress = -1;
    private long completedRuns, deaths, adsWatched;
    private double lastScene = Double.NaN, lastPlayerX = -1, lastPlayerY = -1;
    private int recoveries, recoveryStep, direction=1, storeBacks, animationTaps;
    private boolean inRun, resultCounted, rewardRequested, observedAd, stopAfterResult;
    private boolean verifiedSelection, sawAirborne, ceilingSweep, climbing;
    private int jumpsUsed, gateDirection = 1;
    private int groundedFrames, airborneFrames, ceilingFrames;
    private long lastGateSeen = -1;
    private long targetContactSince = -1, targetSeenAt = -1, targetTouchedAt = -1;
    private double targetX = -1, targetY = -1, touchedX = -1, touchedY = -1;

    public FarmEngine(Config config) { this.config = config == null ? new Config() : config; }
    public synchronized State state() { return state; }
    public synchronized String status() { return status; }
    public synchronized int runs() { return (int) Math.min(Integer.MAX_VALUE, completedRuns); }
    public synchronized int completedRuns() { return runs(); }
    public synchronized int adsWatched() { return (int) Math.min(Integer.MAX_VALUE, adsWatched); }
    public synchronized int deaths() { return (int) Math.min(Integer.MAX_VALUE, deaths); }
    public synchronized void stop() { state = State.STOPPED; status = "Stopped"; busyUntil = 0; }

    public synchronized void reset(long now) {
        state = State.IDLE; status = "Ready"; sessionStart = now; runStart = -1;
        lastCapture = -1; lastNow = -1; busyUntil = 0; unknownSince = gateSince = adSince = -1;
        lastJump = lastCeiling = -100000; scanUntil = 0; lastProgress = -1;
        completedRuns = deaths = adsWatched = 0; recoveries = recoveryStep = 0; direction=1;storeBacks=animationTaps=0;
        lastScene = Double.NaN; lastPlayerX = lastPlayerY = -1;
        inRun = resultCounted = rewardRequested = observedAd = stopAfterResult = false;
        verifiedSelection = false; resetNavigation();
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
                return tap(play, f.now, "Start Lost Scrapyard at Frozen five stars", 800);
            }
            return waiting(f.now, "Target selected; waiting for Play button");
        }
        if ((rightButton(f, "play", "start", "enter", "deploy") != null || f.playButton) && !f.gameplay)
            return pause("Select Lost Scrapyard with Frozen five-star boost before starting");
        return waiting(f.now, "Screen not recognized; waiting without tapping");
    }

    private Action navigate(Frame f) {
        if (f.now - runStart >= clamp(config.maxRunSeconds, 15, 3600) * 1000)
            return pause("Run time limit reached; inspect the current room");
        if (f.healthFraction >= 0 && f.healthFraction <= 0.01)
            return pause("Health is empty; inspect the death screen before resuming");
        long movement = clamp(config.moveMs, 120, 700);
        updateJumpEvidence(f);
        boolean changed = false;
        if (Double.isFinite(f.sceneSignature)) {
            changed = !Double.isFinite(lastScene) || Math.abs(f.sceneSignature - lastScene) > 0.008;
            lastScene = f.sceneSignature;
        } else if (f.playerX >= 0 && f.playerY >= 0) {
            changed = lastPlayerX < 0 || Math.abs(f.playerX - lastPlayerX) + Math.abs(f.playerY - lastPlayerY) > 0.012;
        }
        lastPlayerX = f.playerX; lastPlayerY = f.playerY;
        if (changed || lastProgress < 0) lastProgress = f.now;
        if (f.gate) {
            lastGateSeen = f.now;
            if (gateSince < 0) {
                gateSince = f.now;
                gateDirection = f.gateX >= 0 && f.playerX >= 0 && Math.abs(f.gateX - f.playerX) > .08
                        ? (f.gateX > f.playerX ? 1 : -1) : direction;
                direction = gateDirection;
            }
        }
        // Keep a short search memory when the gate scrolls off-screen during backtracking.
        if (gateSince >= 0 && f.now - lastGateSeen > 5000) { gateSince = -1; direction = gateDirection; }
        if (gateSince >= 0 && f.now - gateSince > clamp(config.gateTimeoutMs, 3000, 120000))
            return pause("Gate still visible after the search; inspect remaining enemies");

        double[] enemy = chooseEnemy(f);
        if (enemy != null && f.playerConfidence >= .30 && f.playerX >= 0 && f.playerY >= 0) {
            double ex = (enemy[0] + enemy[2]) / 2, ey = (enemy[1] + enemy[3]) / 2;
            targetX = ex; targetY = ey; targetSeenAt = f.now;
            int toward = Math.abs(ex - f.playerX) < .018 ? direction : (ex > f.playerX ? 1 : -1);
            boolean above = ey < f.playerY - .06;
            boolean overlap = enemy[0] <= f.playerX + .025 && enemy[2] >= f.playerX - .025
                    && enemy[1] <= f.playerY + .05 && enemy[3] >= f.playerY - .05;
            if (overlap) {
                // A short pass through the body ignites Ember targets; no stationary kill wait.
                targetTouchedAt = f.now; touchedX = ex; touchedY = ey; targetSeenAt = -1;
                long contact = clamp(config.settleMs, 80, 350);
                if (enemy.length > 5 && enemy[5] >= .6) contact = Math.max(260, contact);
                return move(toward, jumpBatch(f, above, contact), contact, f.now,
                        "Pass through the enemy, then keep moving while Ember burns");
            }
            long pursuit = Math.min(movement, Math.max(140, (long) (Math.abs(ex - f.playerX) * 1600)));
            return move(toward, jumpBatch(f, above || climbing, pursuit), pursuit, f.now,
                    above ? "Chain jumps toward the overhead enemy" : "Return to the visible missed enemy");
        }
        if (targetSeenAt >= 0 && f.now - targetSeenAt < 900 && f.playerX >= 0) {
            int toward = targetX > f.playerX ? 1 : -1;
            return move(toward, jumpBatch(f, targetY < f.playerY - .06 || climbing, movement), movement, f.now,
                    "Check the last-seen enemy position before moving on");
        }

        boolean haveProgressSignal = Double.isFinite(f.sceneSignature) || (f.playerX >= 0 && f.playerY >= 0);
        if (haveProgressSignal && f.now - lastProgress > clamp(config.stuckTimeoutMs, 3000, 60000)) {
            if (recoveries >= Math.max(0, config.maxRecoveries)) return pause("Stuck recovery limit reached; inspect the route");
            recoveries++; recoveryStep = 1; lastProgress = f.now; direction = -direction;
            return move(direction, jumpBatch(f, true, movement), movement, f.now, "Stuck recovery: reverse and try a fresh ascent");
        }
        if (recoveryStep == 1) {
            recoveryStep = 0;
            return move(direction, jumpBatch(f, true, movement), movement, f.now, "Continue the alternate route and re-check the room");
        }

        if (gateSince >= 0) {
            // Alternating expanding passes revisit the gate after burn time without waiting idle.
            long elapsed = f.now - gateSince;
            long period = elapsed < 6000 ? 3000 : 4800;
            int searchDirection = elapsed % period < period * .55 ? -gateDirection : gateDirection;
            return move(searchDirection, jumpBatch(f, true, movement), movement, f.now,
                    searchDirection == gateDirection ? "Re-check the gate after the Ember pass" : "Backtrack and scan above for missed enemies");
        }
        boolean scan = climbing || f.now - lastCeiling >= clamp(config.ceilingEveryMs, 500, 30000);
        int jumps = jumpBatch(f, scan, movement);
        return move(direction, jumps, movement, f.now, jumps > 0 ? "Chain Hookshot jumps to inspect the ceiling"
                : ceilingSweep ? "Sweep under the ceiling, then land to recharge jumps" : "Advance and re-check the room on the next frame");
    }

    private void updateJumpEvidence(Frame f) {
        if (f.playerConfidence < .30 || f.playerX < 0 || f.playerY < 0) return;
        if (f.grounded) {
            groundedFrames++; airborneFrames = 0;
            // Two visible ground observations after visible airtime, never a wall-clock reset.
            if (groundedFrames >= 2 && sawAirborne) {
                jumpsUsed = 0; sawAirborne = false; ceilingSweep = false; climbing = false;
                ceilingFrames = 0;
            }
        } else {
            groundedFrames = 0; airborneFrames++;
            if (airborneFrames >= 2 && jumpsUsed > 0) sawAirborne = true;
        }
        ceilingFrames = f.ceilingReached ? ceilingFrames + 1 : 0;
        if (ceilingFrames >= 2) { ceilingSweep = true; climbing = false; }
    }

    private int jumpBatch(Frame f, boolean requested, long duration) {
        int budget = (int) clamp(config.jumpBudget, 1, 21);
        if (!requested || ceilingSweep || jumpsUsed >= budget) return 0;
        long spacing = clamp(config.jumpSpacingMs, 100, 450), tap = clamp(config.jumpTapMs, 30, 120);
        int count = (int) Math.min(3, Math.max(1, (duration - tap) / spacing + 1));
        count = Math.min(count, budget - jumpsUsed);
        if (!climbing) { climbing = true; lastCeiling = f.now; }
        jumpsUsed += count; lastJump = f.now + (count - 1) * spacing;
        return count;
    }

    private double[] chooseEnemy(Frame f) {
        double[] best = null; double score = Double.NEGATIVE_INFINITY;
        if (f.enemyBoxes == null) return null;
        for (double[] box : f.enemyBoxes) {
            if (box == null || box.length < 4 || box[0] < 0 || box[1] < 0 || box[2] > 1 || box[3] > 1
                    || box[0] >= box[2] || box[1] >= box[3]) continue;
            if (box.length > 4 && box[4] >= .55) continue;
            double x = (box[0] + box[2]) / 2, y = (box[1] + box[3]) / 2;
            if (!Double.isFinite(x) || !Double.isFinite(y)) continue;
            if (targetTouchedAt >= 0 && f.now - targetTouchedAt < 1400
                    && Math.abs(x - touchedX) < .08 && Math.abs(y - touchedY) < .10) continue;
            double priority = (y < f.playerY - .1 ? .35 : 0) - Math.abs(x - f.playerX) - .45 * Math.abs(y - f.playerY);
            if (priority > score) { score = priority; best = box; }
        }
        return best;
    }

    private Action handleEnd(Frame f, String text, boolean death, boolean crate) {
        if (state != State.END_SCREEN) unknownSince = -1;
        state = State.END_SCREEN; gateSince = -1;
        if (inRun && !resultCounted && !crate) {
            if (death) deaths++; else completedRuns++;
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
            return tap(retry, f.now, death ? "Retry after defeat" : "Replay the same selected level", 1600);
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

    private boolean completeTitle(Frame f) {
        for (Token t : tokens(f)) if (t.y() < .25 && normalized(t.text).matches("complete!?")) return true;
        return false;
    }

    private boolean isAdState() { return state == State.REWARD_AD || state == State.INTERSTITIAL; }
    private boolean limitReached() { return !config.continuousFarm && completedRuns >= clamp(config.maxRuns, 1, 100000); }
    private void startRun(long now) {
        inRun = true; resultCounted = false; rewardRequested = false; stopAfterResult = false;
        direction=1;animationTaps=0;
        runStart = now; lastProgress = now; lastCeiling = now - clamp(config.ceilingEveryMs, 500, 30000);
        gateSince = -1; recoveries = recoveryStep = 0; lastScene = Double.NaN; lastPlayerX = lastPlayerY = -1;
        resetNavigation();
    }
    private void resetNavigation() {
        jumpsUsed = 0; groundedFrames = airborneFrames = ceilingFrames = 0;
        sawAirborne = ceilingSweep = climbing = false; lastGateSeen = -1;
        targetSeenAt = targetTouchedAt = targetContactSince = -1;
        targetX = targetY = touchedX = touchedY = -1;
    }
    private Action waiting(long now, String reason) {
        if (unknownSince < 0) unknownSince = now;
        if (now - unknownSince > clamp(config.unknownTimeoutMs, 3000, 120000)) return pause(reason + "; manual check needed");
        status = reason; return Action.waitFor(reason);
    }
    private Action pause(String reason) { state = State.PAUSED; status = reason; return new Action(Kind.PAUSE, 0, 0, 0, false, 0, reason); }
    private Action move(int direction, int jumps, long duration, long now, String reason) {
        long spacing = clamp(config.jumpSpacingMs, 100, 450);
        long actualDuration = Math.min(700, Math.max(duration, jumps > 0
                ? (jumps - 1) * spacing + clamp(config.jumpTapMs, 30, 120) : 0));
        return emit(new Action(Kind.MOVE, 0, 0, direction, jumps, spacing, actualDuration, reason), now, actualDuration + 25);
    }
    private Action tap(Token t, long now, String reason, long cooldown) {
        if (!Double.isFinite(t.x()) || !Double.isFinite(t.y()) || t.x() < 0 || t.x() > 1 || t.y() < 0 || t.y() > 1)
            return pause("Invalid target coordinates; recalibrate the screen");
        return emit(new Action(Kind.TAP, t.x(), t.y(), 0, false, 70, reason), now, cooldown);
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
