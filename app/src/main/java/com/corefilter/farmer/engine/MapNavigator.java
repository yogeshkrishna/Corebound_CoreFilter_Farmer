package com.corefilter.farmer.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.TreeSet;

/** A bounded, on-device room atlas. It maps geometry, not brightness or projectile motion.
 * Camera registration makes off-screen targets and inspected ceiling sections persist.
 * This is partial observation: a contact is only a burn attempt, never a confirmed kill.
 */
public final class MapNavigator {
    private static final int COLS = 48, ROWS = 24, MAX_CELLS = 20000, MAX_PATH = 6000;
    private static final double SECTION_GAP = 1000;
    private static final double CAMERA_MIN = .55, PLAYER_MIN = .38;
    private static final long BURN_GRACE_MS = 1800;

    public static final class Decision {
        public final int direction, jumps;
        public final long durationMs;
        public final boolean pause;
        public final String reason;
        private Decision(int direction, int jumps, long durationMs, boolean pause, String reason) {
            this.direction = direction; this.jumps = jumps; this.durationMs = durationMs;
            this.pause = pause; this.reason = reason;
        }
    }

    public static final class Snapshot {
        public final int room, mapCells, unresolvedEnemies, remainingJumps, inspectedCeilings, ceilingSections;
        public final double cameraX, cameraY, playerX, playerY, goalX, goalY;
        public final double learnedJumpRise, verticalVelocity;
        public final double cameraConfidence, viewportAspectRatio;
        public final int terrainCols=COLS,terrainRows=ROWS,corridorDirection;
        public final boolean complete,runEnded,runSucceeded;
        public final String phase;
        public final String goal, reason;
        public final long blockedForMs;
        /** [world x, world y, occupancy, visited] and [enemy x, enemy y, burn attempt, last seen]. */
        public final double[][] cells, enemies;
        public final double[][] borders,path,coverage,enemyHistory;
        private Snapshot(MapNavigator n) {
            room = n.room; mapCells = n.tiles.size(); unresolvedEnemies = n.tracks.size();
            remainingJumps = Math.max(0, n.jumpBudget() - n.usedJumps);
            cameraX = n.cameraX-n.room*SECTION_GAP; cameraY = n.cameraY; playerX = n.px-n.room*SECTION_GAP; playerY = n.py;
            cameraConfidence=n.registered?n.frame.cameraConfidence:0;
            viewportAspectRatio=n.frame==null?Double.NaN:n.frame.viewportAspectRatio;
            corridorDirection=n.corridorDirection;phase=n.phase;runEnded=n.runEnded;runSucceeded=n.runSucceeded;
            complete=n.completeEvidence();
            learnedJumpRise=n.jumpRise;verticalVelocity=n.velocityY;
            goalX = n.goal == null ? Double.NaN : n.goal.x-n.room*SECTION_GAP; goalY = n.goal == null ? Double.NaN : n.goal.y;
            goal = n.goal == null ? "observe" : n.goal.kind; reason = n.reason;
            blockedForMs = n.blockedSince < 0 || n.now < 0 ? 0 : n.now - n.blockedSince;
            int done = 0; for (Ceiling c : n.ceilings.values()) if (c.checked) done++;
            inspectedCeilings = done; ceilingSections = n.ceilings.size();
            cells = new double[n.tiles.size()][5]; int i = 0;
            for (Map.Entry<Long, Tile> e : n.tiles.entrySet()) {
                cells[i++] = new double[]{wx(x(e.getKey()))-e.getValue().section*SECTION_GAP, wy(y(e.getKey())), e.getValue().solid ? 2 : 1, e.getValue().visited ? 1 : 0,e.getValue().section};
            }
            enemies = new double[n.tracks.size()][5]; i = 0;
            for (Track t : n.tracks) enemies[i++] = new double[]{t.x-t.section*SECTION_GAP, t.y, t.touchedAt < 0 ? 0 : 1, t.seenAt,t.section};
            borders=n.exportBorders();path=n.pathRows.toArray(new double[0][]);coverage=n.exportCoverage();
            enemyHistory=n.exportEnemyHistory();
        }
    }

    private static final class Tile { boolean solid, visited; int evidence,section,observations; long seenAt; }
    private static final class Ceiling { double x, y; boolean checked; int section,clearViews;long lastView=-1; Ceiling(double x, double y,int section) { this.x=x;this.y=y;this.section=section; } }
    private static final class Track {
        int id, missingFrames, sector,section,corridor; double x, y, width, height, heavy;boolean ceilingCandidate;
        long firstSeen,seenAt, touchedAt = -1, absentSince = -1, deferredUntil,clearedAt=-1; boolean matched;
    }
    private static final class Goal {
        final double x, y; final String kind; final int track;
        Goal(double x, double y, String kind, int track) { this.x=x;this.y=y;this.kind=kind;this.track=track; }
    }

    private final FarmEngine.Config config;
    private final HashMap<Long, Tile> tiles = new HashMap<>();
    private final HashMap<Long, Ceiling> ceilings = new HashMap<>();
    private final ArrayList<Track> tracks = new ArrayList<>();
    private final ArrayList<Track> history = new ArrayList<>();
    private final HashSet<Long> unseenRoofColumns=new HashSet<>();
    private final ArrayList<double[]> borderRows=new ArrayList<>(),pathRows=new ArrayList<>();
    private int corridorDirection=1,corridor=1,returnTrack=-1,remainingEnemies=-1;
    private double corridorFloor=Double.NaN,returnX=Double.NaN,scoutX=Double.NaN,dropX=Double.NaN;
    private long returnAt=-1,returnDeadline=-1,dropAt=-1,releaseUntil=-1,lastTurn=-100000;
    private boolean pendingDrop,runEnded,runSucceeded,scoutDone,highRoofPending,entered,orientationPending;
    private double entryX=Double.NaN,velocityX,gravity=1.8,horizontalSpeed=.20;
    private long countAt=-1;
    private int countSector=-1;
    private double corridorStartX=Double.NaN;
    private long remainingSweepAt=-1;
    private int emptySweeps;
    private String phase="ENTER";
    private double cameraX, cameraY, biasX, biasY, px = Double.NaN, py = Double.NaN;
    private double previousX = Double.NaN, previousY = Double.NaN, velocityY;
    private long now=-1, previousAt=-1, lastJump=-100000, blockedSince=-1, lastProgress=-1, roofSince=-1;
    private long gateAt=-1, uncertainSince=-1, discontinuityAt=-1, cameraGapAt=-1;
    private int epoch=Integer.MIN_VALUE, room, nextTrack=1, usedJumps, groundedFrames, airFrames;
    private int lastDirection=1, failedEscapes;
    private int completedSector, activeSector=1;
    private boolean pendingAnchor, sectorCleared;
    private double gateX=Double.NaN;
    private boolean sawAir, registered, previousCommand, pendingSceneChange, dispatch=true;
    private double jumpRise=.16, jumpOriginY, jumpMinimumY;
    private long jumpObservedAfter=-1;
    private int learnedJumps;
    private boolean learningJump;
    private Goal goal;
    private String reason="Waiting for the player and terrain";
    private FarmEngine.Frame frame;

    public MapNavigator(FarmEngine.Config config) { this.config=config; }
    public void reset() {
        borderRows.clear();pathRows.clear();corridorDirection=1;corridor=1;returnTrack=remainingEnemies=-1;
        corridorFloor=returnX=scoutX=dropX=Double.NaN;returnAt=returnDeadline=dropAt=releaseUntil=-1;lastTurn=-100000;
        pendingDrop=runEnded=runSucceeded=scoutDone=highRoofPending=entered=orientationPending=false;phase="ENTER";
        entryX=corridorStartX=Double.NaN;velocityX=0;countAt=remainingSweepAt=-1;countSector=-1;emptySweeps=0;
        tiles.clear();ceilings.clear();tracks.clear();history.clear();unseenRoofColumns.clear(); cameraX=cameraY=biasX=biasY=0;
        px=py=previousX=previousY=Double.NaN;velocityY=0;now=previousAt=-1;lastJump=-100000;
        blockedSince=roofSince=gateAt=uncertainSince=discontinuityAt=cameraGapAt=-1;lastProgress=-1;
        epoch=Integer.MIN_VALUE;room=0;nextTrack=1;usedJumps=groundedFrames=airFrames=0;
        lastDirection=1;failedEscapes=0;sawAir=previousCommand=false;
        gateX=Double.NaN;goal=null;registered=false;
        reason="Waiting for the player and terrain";frame=null;pendingSceneChange=false;
        learningJump=false;jumpObservedAfter=-1;
        completedSector=0;activeSector=1;pendingAnchor=sectorCleared=false;
    }
    public Snapshot snapshot() { return new Snapshot(this); }
    public int room() { return room; }
    public boolean sectorCleared() { return sectorCleared; }
    public void finish(boolean success) { runEnded=true;runSucceeded=success;phase="COMPLETE";previousCommand=false;if(success)resolveOrdinary(); }
    public Decision observe(FarmEngine.Frame f) { return next(f,false); }

    public Decision next(FarmEngine.Frame f) {
        return next(f,true);
    }
    private Decision next(FarmEngine.Frame f,boolean dispatchActions) {
        dispatch=dispatchActions;frame=f;now=f.now;
        if(runEnded)return decision(0,0,0,false,"Run finished; preserve its map for export");
        if(f.playerConfidence<PLAYER_MIN||!valid(f.playerX,f.playerY)) {
            previousCommand=false;if(uncertainSince<0)uncertainSince=now;
            return decision(0,0,0,now-uncertainSince>5000,"Player position uncertain; observe before moving");
        }
        uncertainSince=-1;registerCamera(f);if(registered)cameraGapAt=-1;else if(cameraGapAt<0)cameraGapAt=now;
        px=f.playerX+cameraX;py=f.playerY+cameraY;
        if(!Double.isFinite(entryX))entryX=corridorStartX=px;
        if((px-entryX)*corridorDirection>=.16)entered=true;
        boolean anchored=registered||previousAt<0&&!pendingAnchor;
        if(anchored) {
            if(validTerrain(f))integrateTerrain(f);
            fillPlayerMask(f);buildBorders();updateMotionAndJumps(f);updateTracks(f);updateCeilingCoverage();recordPath();
        } else updateGroundEvidence(f);
        if(f.remainingEnemies>=0&&f.remainingEnemiesConfidence>=.55) {
            remainingEnemies=f.remainingEnemies;countAt=f.capturedAt;countSector=activeSector;
            sectorCleared=remainingEnemies==0;if(sectorCleared)resolveOrdinary();
        }
        if(f.completedSector>completedSector) {
            completedSector=f.completedSector;resolveOrdinary();activeSector=Math.max(activeSector,completedSector+1);
            remainingEnemies=-1;countAt=f.capturedAt;countSector=activeSector;sectorCleared=true;
        }
        if(countSector!=activeSector||f.capturedAt-countAt>2000){remainingEnemies=-1;sectorCleared=false;}
        if(anchored&&f.gate&&validCoordinate(f.gateX)){gateX=f.gateX+cameraX;gateAt=now;}
        if(tiles.size()>MAX_CELLS)return decision(0,0,0,true,"Atlas size limit reached; preserve this partial map");
        if(!anchored)return unregisteredPass();
        if(entered&&phase.equals("ENTER"))phase="GROUND_SWEEP";
        double[] support=supportFloor();
        if(!Double.isFinite(corridorFloor)&&support!=null)corridorFloor=support[1]-bodyHalfH();
        commitObservedDrop(support);
        double[] opening=findOpening(corridorDirection,support);
        double[] wall=wallAhead(corridorDirection);
        boolean blocked=wallContact(corridorDirection)||(wall!=null&&wallGap(wall,corridorDirection)<predictionDistance());
        highRoofPending=roofStillOutsideView();
        if(scoutDone&&Double.isFinite(scoutX)&&Math.abs(px-scoutX)>.35)scoutDone=false;
        if(frame.ceilingReached||roofGap()<.055) {
            learningJump=false;if(phase.equals("SCOUT_HIGH_CEILING"))finishScout();
            return act(blocked?0:corridorDirection,0,350,"Roof clearance: release jump and descend to the ground pass");
        }
        if(phase.equals("DROP_TO_CORRIDOR")&&Double.isFinite(dropX))return followDrop();
        if(phase.equals("REMAINING_ENEMY_SWEEP")){Decision sweep=remainingSweep();if(sweep!=null)return sweep;}
        Track earlyReturning=track(returnTrack);
        if(earlyReturning!=null){Decision revisit=returnToEnemy(earlyReturning);if(revisit!=null)return revisit;}
        if(phase.equals("RETURN_GROUND")) {
            if(f.grounded&&Math.abs(px-returnX)<.13){phase="GROUND_SWEEP";returnX=Double.NaN;}
            else {int side=Double.isFinite(returnX)&&Math.abs(px-returnX)>.07?(returnX>px?1:-1):0;
                return act(wallContact(side)?0:side,0,350,"Return from the high-roof scout to the ground sweep; no extra jump");}
        }
        boolean atExit=blocked||opening!=null&&(opening[0]-px)*corridorDirection<.16
                ||gateAt>=0&&now-gateAt<1500&&Double.isFinite(gateX)&&(gateX-px)*corridorDirection>=-.04&&Math.abs(gateX-px)<.20;
        if(entered&&!phase.equals("SCOUT_HIGH_CEILING")) {
            Track missed=missedEnemy();
            if(missed!=null){startEnemyReturn(missed);Decision revisit=returnToEnemy(missed);if(revisit!=null)return revisit;}
            if(atExit&&remainingEnemies>0){
                if(emptySweeps>=2)return decision(0,0,0,true,"Enemy counter remains positive after two complete return sweeps; inspect detection");
                remainingSweepAt=now;phase="REMAINING_ENEMY_SWEEP";Decision sweep=remainingSweep();if(sweep!=null)return sweep;
            }
            if(atExit&&highRoofPending&&!scoutDone&&usedJumps<jumpBudget()) {
                scoutX=px;phase="SCOUT_HIGH_CEILING";
            }
        }
        if(!phase.equals("SCOUT_HIGH_CEILING")&&opening!=null&&(opening[0]-px)*corridorDirection<.16) {
            dropX=opening[0];dropAt=now;pendingDrop=true;phase="DROP_TO_CORRIDOR";return followDrop();
        }
        if(!phase.equals("SCOUT_HIGH_CEILING")&&(blocked||dispatch&&blockedSince>=0&&now-blockedSince>=650&&(previousCommand||phase.equals("RECOVERY"))))return blockedPrimitive(wall,opening,support);
        if(now<releaseUntil)return act(0,0,350,"Keep the recovery primitive released; do not alternate directions");
        Track returning=track(returnTrack);
        if(returning!=null){Decision d=returnToEnemy(returning);if(d!=null)return d;}
        else returnTrack=-1;
        Track forward=forwardEnemy();
        if(forward!=null&&touching(forward,f)) {
            markContact(forward);return act(corridorDirection,0,forward.heavy>.6?260:180,"Ember contact attempted; sweep onward while the target burns");
        }
        if(highRoofPending&&entered&&sawAir)unseenRoofColumns.add(key(cellX(px+corridorDirection*.12),room));
        if(phase.equals("SCOUT_HIGH_CEILING")&&!highRoofPending){finishScout();return act(0,0,350,"Roof search now visible: descend before completing the ground return");}
        if(phase.equals("SCOUT_HIGH_CEILING")) {
            if(usedJumps>=jumpBudget()){finishScout();return act(corridorDirection,0,350,"Scout charges spent: descend and recharge on observed ground");}
            int pulse=measuredAirPulse()?1:0;
            return act(blocked||opening!=null&&Math.abs(opening[0]-px)<.10?0:corridorDirection,pulse,350,pulse>0?"High roof still outside the view: one measured Hookshot":"High-roof scout: coast through the current ascent before the next pulse");
        }
        if(highRoofPending&&entered&&sawAir&&!scoutDone&&now-lastJump>=250) {
            scoutX=px;phase="SCOUT_HIGH_CEILING";int pulse=measuredAirPulse()?1:0;
            return act(corridorDirection,pulse,350,pulse>0?"Normal traversal has not revealed the roof: scout with one Hookshot":"Normal jump is still rising; observe it before spending a Hookshot");
        }
        if(forward!=null&&forward.ceilingCandidate&&forward.y<py-.13&&Math.abs(forward.x-px)<.24&&measuredAirPulse())
            return act(corridorDirection,1,350,"Intercept the visible overhead enemy with one measured Hookshot");
        Track missed=entered?missedEnemy():null;
        if(missed!=null){startEnemyReturn(missed);Decision revisit=returnToEnemy(missed);if(revisit!=null)return revisit;}
        if(!entered){goal=new Goal(entryX+.16,py,"enter corridor",-1);return act(corridorDirection,groundJumpReady()?1:0,normalDuration(),"Enter the first corridor with forward jump movement; retain rear targets for later");}
        phase=sectorCleared&&!highRoofPending?"CONTINUE":"GROUND_SWEEP";
        int jump=groundJumpReady()?1:0;goal=new Goal(px+corridorDirection*.20,py,phase.toLowerCase(Locale.ROOT),-1);
        return act(corridorDirection,jump,normalDuration(),jump>0?"Sweep forward with the normal ground jump; visible ceiling counts as inspected":
                phase.equals("CONTINUE")?"Ordinary enemies cleared: advance without waiting for the gate animation":"Keep the corridor direction and coast or walk through the ground enemies");
    }
    private void registerCamera(FarmEngine.Frame f) {
        double retainedX=cameraX,retainedY=cameraY;
        registered=f.cameraConfidence>=CAMERA_MIN;
        boolean absolute=Double.isFinite(f.cameraX)&&Double.isFinite(f.cameraY);
        if(epoch==Integer.MIN_VALUE){epoch=f.registrationEpoch;biasX=biasY=0;}
        else if(epoch!=f.registrationEpoch) {
            // Registration can restart after occlusion. Keep its world origin until
            // a later reliable terrain frame contradicts the old room geometry.
            pendingSceneChange=f.sceneChanged;discontinuityAt=now;
            pendingAnchor=true;
            if(absolute) {biasX=cameraX-f.cameraX;biasY=cameraY-f.cameraY;}
            epoch=f.registrationEpoch;
        }
        if(registered) {
            if(absolute){cameraX=biasX+f.cameraX;cameraY=biasY+f.cameraY;}
            else {cameraX+=f.cameraDx;cameraY+=f.cameraDy;}
        }
        if(pendingAnchor&&registered&&f.cameraConfidence>=.7&&validTerrain(f)) {
            double[] alignment=alignAtlas(f);
            if(alignment!=null) {
                cameraX=alignment[0];cameraY=alignment[1];biasX=cameraX-f.cameraX;biasY=cameraY-f.cameraY;
                pendingAnchor=pendingSceneChange=false;
            }
            int compared=0,mismatch=0;
            for(int yy=4;yy<f.terrainRows-4;yy++)for(int xx=2;xx<f.terrainCols-2;xx++) {
                int sample=f.terrainCells[yy*f.terrainCols+xx];if(sample!=1&&sample!=2)continue;
                Tile old=tiles.get(key(cellX((xx+.5)/f.terrainCols+cameraX),cellY((yy+.5)/f.terrainRows+cameraY)));
                if(old==null)continue;compared++;if(old.solid!=(sample==2))mismatch++;
            }
            if(pendingAnchor&&(pendingSceneChange&&compared>=80&&mismatch/(double)compared>.48
                    ||now-discontinuityAt>1800)) {
                // Geometry cannot establish the missing offset. Open an unlinked
                // map section while preserving the previous atlas, rather than
                // falsely joining two screen origins or deleting off-screen targets.
                room++;biasX=room*SECTION_GAP-f.cameraX;biasY=-f.cameraY;cameraX=room*SECTION_GAP;cameraY=0;
                orientationPending=pendingDrop;pendingDrop=false;dropX=returnX=scoutX=Double.NaN;
                corridorStartX=entryX=cameraX+f.playerX;corridorFloor=Double.NaN;returnTrack=-1;remainingSweepAt=-1;phase="GROUND_SWEEP";
                previousX=previousY=Double.NaN;previousAt=-1;goal=null;blockedSince=-1;
                gateX=Double.NaN;gateAt=-1;failedEscapes=0;
                pendingAnchor=pendingSceneChange=false;
            } else if(pendingAnchor) {
                registered=false;
            }
        } else if(pendingAnchor)registered=false;
        if(pendingAnchor){cameraX=retainedX;cameraY=retainedY;}
    }

    private double[] alignAtlas(FarmEngine.Frame f) {
        double best=Double.NEGATIVE_INFINITY,bx=0,by=0,runner=Double.NEGATIVE_INFINITY;
        ArrayList<double[]> candidates=new ArrayList<>();
        for(int ox=-12;ox<=12;ox++)for(int oy=-6;oy<=6;oy++) {
            double cx=cameraX+ox/(double)COLS,cy=cameraY+oy/(double)ROWS;
            int compared=0,solidMatches=0;double total=0,mismatch=0;
            for(int yy=4;yy<f.terrainRows-2;yy++)for(int xx=1;xx<f.terrainCols-1;xx++) {
                int sample=f.terrainCells[yy*f.terrainCols+xx];
                if(sample==0||sample==1&&(xx+yy)%3!=0)continue;
                Tile old=tiles.get(key(cellX((xx+.5)/f.terrainCols+cx),cellY((yy+.5)/f.terrainRows+cy)));
                if(old==null)continue;compared++;
                double weight=sample==2||old.solid?3:1;total+=weight;
                if(old.solid!=(sample==2))mismatch+=weight;
                else if(old.solid)solidMatches++;
            }
            if(compared<100||solidMatches<12||total==0||mismatch/total>.16)continue;
            double score=1-mismatch/total+Math.min(300,compared)*.00005;
            candidates.add(new double[]{score,cx,cy,ox,oy});
            if(score>best){best=score;bx=cx;by=cy;}
        }
        if(!Double.isFinite(best))return null;
        for(double[]c:candidates)
            if(Math.abs(c[1]-bx)>1.1/COLS||Math.abs(c[2]-by)>1.1/ROWS)runner=Math.max(runner,c[0]);
        // Repetitive floor strips do not establish a horizontal world offset.
        if(Double.isFinite(runner)&&best-runner<.025)return null;
        return new double[]{bx,by};
    }

    private void integrateTerrain(FarmEngine.Frame f) {
        for(int yy=0;yy<f.terrainRows;yy++)for(int xx=0;xx<f.terrainCols;xx++) {
            byte sample=f.terrainCells[yy*f.terrainCols+xx];if(sample!=1&&sample!=2)continue;
            double sx=(xx+.5)/f.terrainCols,sy=(yy+.5)/f.terrainRows;
            int mx=cellX(sx+cameraX),my=cellY(sy+cameraY);
            long key=key(mx,my);Tile t=tiles.get(key);
            if(t==null){t=new Tile();t.section=room;t.evidence=sample==2?2:-2;tiles.put(key,t);}
            else t.evidence=Math.max(-4,Math.min(4,t.evidence+(sample==2?2:-2)));
            t.solid=t.evidence>0;t.seenAt=now;t.observations++;
        }
        // Ceiling goals are below the underside by a body clearance, not inside the roof.
        for(int yy=1;yy<f.terrainRows-4;yy++)for(int xx=1;xx<f.terrainCols-1;xx++) {
            if(f.terrainCells[yy*f.terrainCols+xx]!=2)continue;
            if(f.terrainCells[(yy+1)*f.terrainCols+xx]!=1||f.terrainCells[(yy+2)*f.terrainCols+xx]!=1||f.terrainCells[(yy+3)*f.terrainCols+xx]!=1)continue;
            double cx=(xx+.5)/f.terrainCols+cameraX,cy=(yy+1.)/f.terrainRows+cameraY;
            long section=key(cellX(cx),cellY(cy));
            if(!ceilings.containsKey(section))ceilings.put(section,new Ceiling(cx,cy,room));
        }
    }

    private void observeFree(int x,int y,boolean visit) {
        Tile t=tiles.get(key(x,y));if(t==null){t=new Tile();t.section=room;t.evidence=-2;tiles.put(key(x,y),t);}
        if(t.solid)return; // Gold aura/body bounds must never cut a hole into an observed wall.
        t.solid=false;t.evidence=Math.min(-1,t.evidence);t.visited|=visit;t.seenAt=now;
    }

    private void fillPlayerMask(FarmEngine.Frame f) {
        double left=validCoordinate(f.playerLeft)?f.playerLeft:f.playerX-.025;
        double right=validCoordinate(f.playerRight)?f.playerRight:f.playerX+.025;
        double top=validCoordinate(f.playerTop)?f.playerTop:f.playerY-.035;
        double bottom=validCoordinate(f.playerBottom)?f.playerBottom:f.playerY+.035;
        for(int yy=cellY(top+cameraY);yy<=cellY(bottom+cameraY);yy++)
            for(int xx=cellX(left+cameraX);xx<=cellX(right+cameraX);xx++)observeFree(xx,yy,true);
    }

    private void updateMotionAndJumps(FarmEngine.Frame f) {
        long measuredAt=f.capturedAt;
        if(previousAt>=0&&measuredAt>previousAt) {
            double dt=(measuredAt-previousAt)/1000.;double dx=px-previousX,dy=py-previousY;
            double previousVelocity=velocityY;
            velocityY=dy/Math.max(.08,dt);velocityX=dx/Math.max(.08,dt);
            boolean measurable=registered||(!Double.isFinite(f.cameraX)&&Math.abs(f.cameraDx)+Math.abs(f.cameraDy)<.001);
            if(measurable&&Math.abs(dx)+Math.abs(dy)>.010) {
                lastProgress=now;
                if(Math.abs(dx)>.08)failedEscapes=Math.max(0,failedEscapes-1);
            }
            if(measurable&&dx*lastDirection>.012){blockedSince=-1;if(phase.equals("RECOVERY"))phase="GROUND_SWEEP";}
            else if(dispatch&&measurable&&previousCommand&&lastDirection!=0&&measuredAt-previousAt>=100&&blockedSince<0)blockedSince=now-(measuredAt-previousAt);
            if(!measurable)velocityY=0;
            if(measurable&&previousCommand&&Math.abs(velocityX)>.04&&Math.abs(velocityX)<1.2)
                horizontalSpeed=.8*horizontalSpeed+.2*Math.abs(velocityX);
            if(measurable&&!f.grounded&&!f.ceilingReached&&lastJump<previousAt&&dt<.8){
                double acceleration=(velocityY-previousVelocity)/dt;
                if(acceleration>.35&&acceleration<4.5)gravity=.85*gravity+.15*acceleration;
            }
            if(!f.grounded&&(measurable&&Math.abs(dy)>.008||airFrames>=1))sawAir=true;
            if(learningJump&&measurable&&measuredAt>jumpObservedAfter) {
                if(f.ceilingReached)learningJump=false;
                else {
                    jumpMinimumY=Math.min(jumpMinimumY,py);
                    if(velocityY>=.02||f.grounded) {
                        double rise=jumpOriginY-jumpMinimumY;
                        if(rise>.045&&rise<.40){jumpRise=learnedJumps++==0?rise:jumpRise*.75+rise*.25;}
                        jumpRise=Math.max(.06,Math.min(.30,jumpRise));learningJump=false;
                    }
                }
            }
        }
        if(lastProgress<0)lastProgress=now;
        updateGroundEvidence(f);
        previousX=px;previousY=py;previousAt=measuredAt;
    }
    private void updateGroundEvidence(FarmEngine.Frame f) {
        if(f.grounded) {
            groundedFrames++;airFrames=0;
            if(groundedFrames>=2&&sawAir){usedJumps=0;sawAir=false;}
            // Independent support geometry plus a genuine airborne phase allows
            // immediate repeat jumping without imposing a second stationary frame.
            if(sawAir&&frame==f){double[] floor=supportFloor();if(floor!=null&&Math.abs(floor[1]-py-bodyHalfH())<.035){usedJumps=0;sawAir=false;}}
        } else {groundedFrames=0;airFrames++;if(airFrames>=2&&usedJumps>0)sawAir=true;}
    }

    private void updateTracks(FarmEngine.Frame f) {
        for(Track t:tracks)t.matched=false;
        if(f.enemyBoxes!=null)for(double[] b:f.enemyBoxes) {
            if(b==null||b.length<4||!valid(b[0],b[1])||!valid(b[2],b[3])||b[2]<=b[0]||b[3]<=b[1])continue;
            double ex=(b[0]+b[2])/2+cameraX,ey=(b[1]+b[3])/2+cameraY;
            Track best=null;double closest=.14;
            for(Track t:tracks)if(t.section==room&&!t.matched) {
                double d=distance(ex,ey,t.x,t.y);if(d<closest){closest=d;best=t;}
            }
            if(best==null){best=new Track();best.id=nextTrack++;best.firstSeen=now;best.sector=activeSector;best.section=room;best.corridor=corridor;tracks.add(best);history.add(best);}
            best.x=ex;best.y=ey;best.width=b[2]-b[0];best.height=b[3]-b[1];
            best.heavy=b.length>5?b[5]:0;best.seenAt=now;best.matched=true;
            best.ceilingCandidate|=ey<py-.14;
            for(Ceiling c:ceilings.values())if(c.section==room&&Math.abs(c.x-ex)<.07&&ey>=c.y&&ey-c.y<.16)best.ceilingCandidate=true;
            best.absentSince=-1;best.missingFrames=0;
            if(b.length>4&&b[4]>=.6&&best.touchedAt<0)best.touchedAt=now;
        }
        for(int i=tracks.size()-1;i>=0;i--) {
            Track t=tracks.get(i);if(t.section!=room||t.matched)continue;
            double sx=t.x-cameraX,sy=t.y-cameraY;
            // Off-screen or occluded absence cannot clear an enemy from memory.
            boolean revisited=Math.abs(t.x-px)<.15&&Math.abs(t.y-py)<.16;
            boolean visible=sx>.08&&sx<.93&&sy>.18&&sy<.70;
            boolean clear=localFree(sx,sy)&&registered;
            if(revisited&&visible&&clear&&now-t.seenAt>=BURN_GRACE_MS) {
                if(t.absentSince<0)t.absentSince=now;t.missingFrames++;
                if(t.missingFrames>=3&&now-t.absentSince>=500) {
                    if(t.touchedAt>=0)retireTrack(i);
                    else {t.deferredUntil=now+5000;t.absentSince=-1;t.missingFrames=0;}
                }
            } else {t.missingFrames=0;t.absentSince=-1;}
        }
    }

    private void updateCeilingCoverage() {
        for(Ceiling c:ceilings.values()) {
            if(c.section!=room)continue;
            double sx=c.x-cameraX,sy=c.y-cameraY;
            // Seeing an underside is distinct from moving the hull close to it.
            // Three clear rows contain the hanging-enemy search area; HUD, aura,
            // player and other occlusions remain unknown in the fresh terrain grid.
            boolean readable=sx>.035&&sx<.965&&sy>.17&&sy<.71
                    &&localFree(sx,sy+.025)&&localFree(sx,sy+.065)&&localFree(sx,sy+.105);
            if(readable&&frame.capturedAt>c.lastView){c.clearViews++;c.lastView=frame.capturedAt;if(c.clearViews>=2)c.checked=true;}
            if(c.checked)unseenRoofColumns.remove(key(cellX(c.x),room));
        }
        if(goal!=null&&!goal.kind.equals("enemy")&&distance(px,py,goal.x,goal.y)<.065) {
            Tile t=tiles.get(key(cellX(goal.x),cellY(goal.y)));if(t!=null)t.visited=true;
            goal=null;
        }
    }

    private void buildBorders() {
        HashMap<String,TreeSet<Integer>> lines=new HashMap<>();
        for(Map.Entry<Long,Tile> e:tiles.entrySet())if(e.getValue().solid) {
            int xx=x(e.getKey()),yy=y(e.getKey()),section=e.getValue().section;
            if(mapValue(xx,yy-1)==1)edge(lines,1,section,yy,xx);
            if(mapValue(xx,yy+1)==1)edge(lines,2,section,yy+1,xx);
            if(mapValue(xx-1,yy)==1)edge(lines,3,section,xx,yy);
            if(mapValue(xx+1,yy)==1)edge(lines,3,section,xx+1,yy);
        }
        borderRows.clear();
        for(Map.Entry<String,TreeSet<Integer>> e:lines.entrySet()) {
            String[] parts=e.getKey().split(":");int type=Integer.parseInt(parts[0]),section=Integer.parseInt(parts[1]),line=Integer.parseInt(parts[2]);
            int first=Integer.MIN_VALUE,last=first;
            for(int p:e.getValue()) {
                if(first==Integer.MIN_VALUE)first=last=p;
                else if(p==last+1)last=p;
                else {border(type,section,line,first,last);first=last=p;}
            }
            if(first!=Integer.MIN_VALUE)border(type,section,line,first,last);
        }
    }
    private void edge(HashMap<String,TreeSet<Integer>> lines,int type,int section,int line,int p) {
        lines.computeIfAbsent(type+":"+section+":"+line,k->new TreeSet<>()).add(p);
    }
    private void border(int type,int section,int line,int first,int last) {
        if(last-first+1<(type==3?4:3))return;
        borderRows.add(type==3?new double[]{line/(double)COLS,first/(double)ROWS,line/(double)COLS,(last+1)/(double)ROWS,type,section,.8}:
                new double[]{first/(double)COLS,line/(double)ROWS,(last+1)/(double)COLS,line/(double)ROWS,type,section,.8});
    }
    private double[][] exportBorders() {
        double[][] rows=new double[borderRows.size()][];int i=0;
        for(double[] b:borderRows){rows[i]=b.clone();rows[i][0]-=b[5]*SECTION_GAP;rows[i][2]-=b[5]*SECTION_GAP;i++;}return rows;
    }
    private double[][] exportCoverage() {
        double[][] rows=new double[ceilings.size()+unseenRoofColumns.size()][];int i=0;
        for(Ceiling c:ceilings.values())rows[i++]=new double[]{c.x-c.section*SECTION_GAP-.5/COLS,c.y,c.x-c.section*SECTION_GAP+.5/COLS,c.section,c.checked?1:0};
        for(long column:unseenRoofColumns){int section=y(column);double cx=wx(x(column))-section*SECTION_GAP;rows[i++]=new double[]{cx-.5/COLS,Double.NaN,cx+.5/COLS,section,0};}
        return rows;
    }
    private void retireTrack(int index){Track t=tracks.remove(index);t.clearedAt=now;if(returnTrack==t.id)returnTrack=-1;}
    private double[][] exportEnemyHistory(){
        int start=Math.max(0,history.size()-1000);double[][] rows=new double[history.size()-start][];
        for(int i=start;i<history.size();i++){Track t=history.get(i);rows[i-start]=new double[]{t.id,t.x-t.section*SECTION_GAP,t.y,t.section,t.firstSeen,t.seenAt,t.touchedAt,t.clearedAt<0?Double.NaN:t.clearedAt,.6};}
        return rows;
    }
    private void recordPath() {
        double localX=px-room*SECTION_GAP;
        if(!pathRows.isEmpty()){double[] p=pathRows.get(pathRows.size()-1);if((int)p[2]==room&&Math.abs(p[0]-localX)+Math.abs(p[1]-py)<.005&&frame.capturedAt-p[3]<1000)return;}
        if(pathRows.size()>=MAX_PATH)for(int i=pathRows.size()-2;i>0;i-=2)pathRows.remove(i);
        pathRows.add(new double[]{localX,py,room,frame.capturedAt,registered?frame.cameraConfidence:.65});
    }
    private double[] supportFloor() {
        double[] best=null;double nearest=Double.POSITIVE_INFINITY;
        for(double[] b:borderRows)if((int)b[5]==room&&b[4]==1&&px>=b[0]-.045&&px<=b[2]+.045&&b[1]>=py-bodyHalfH()&&b[1]-py<.42) {
            if(b[1]-py<nearest){nearest=b[1]-py;best=b;}
        }
        return best;
    }
    private double[] wallAhead(int side) {
        double[] best=null;double nearest=Double.POSITIVE_INFINITY;
        for(double[] b:borderRows)if((int)b[5]==room&&b[4]==3&&b[3]-b[1]>=.15&&b[1]<py+bodyHalfH()&&b[3]>py-bodyHalfH()) {
            double gap=(b[0]-px)*side-bodyHalfW();if(gap>=-.035&&gap<nearest){nearest=gap;best=b;}
        }
        return best;
    }
    private double wallGap(double[] b,int side){return (b[0]-px)*side-bodyHalfW();}
    private boolean wallContact(int side){if(side==0)return false;if(side>0&&frame.wallRight||side<0&&frame.wallLeft)return true;double[] wall=wallAhead(side);return wall!=null&&wallGap(wall,side)<.025;}
    private double predictionDistance(){return horizontalSpeed*(normalDuration()/1000.+Math.min(.4,Math.max(0,now-frame.capturedAt)/1000.))+.020;}
    private double roofGap() {
        double gap=Double.POSITIVE_INFINITY;
        for(double[] b:borderRows)if((int)b[5]==room&&b[4]==2&&px>=b[0]-bodyHalfW()&&px<=b[2]+bodyHalfW()&&b[1]<py)
            gap=Math.min(gap,py-bodyHalfH()-b[1]);
        return gap;
    }
    private double[] findOpening(int side,double[] floor) {
        if(floor==null)return null;
        double edge=side>0?floor[2]:floor[0];if((edge-px)*side<-.06||(edge-px)*side>.30)return null;
        double center=edge+side*.05;int known=0,free=0;
        for(double dx:new double[]{-.018,.018})for(double dy:new double[]{.055,.095,.135}) {
            int v=worldValue(center+dx,floor[1]+dy);if(v!=0)known++;if(v==1)free++;
        }
        if(known<4||free<4||worldValue(center,floor[1])==2||worldValue(center,floor[1]+.03)==2)return null;
        return new double[]{center,floor[1],side};
    }
    private Decision followDrop() {
        int side=Math.abs(dropX-px)>.035?(dropX>px?1:-1):0;
        goal=new Goal(dropX,py+.2,"observed downward passage",-1);
        if(wallContact(side))side=0;
        if(now-dropAt>6500&&!frame.grounded)return decision(0,0,0,true,"Observed opening made no landing progress; inspect the corridor");
        return act(side,0,350,"Follow the real floor opening downward; commit the next direction after landing");
    }
    private void commitObservedDrop(double[] floor) {
        if(!frame.grounded||groundedFrames<2)return;
        if(orientationPending&&floor!=null){
            double[] forwardWall=wallAhead(corridorDirection);
            if(forwardWall!=null&&wallGap(forwardWall,corridorDirection)<.20&&freeBody(px-corridorDirection*.08,py)){
                corridorDirection=-corridorDirection;corridor++;corridorStartX=px;emptySweeps=0;
            }
            orientationPending=false;corridorFloor=py;phase="GROUND_SWEEP";return;
        }
        boolean lower=Double.isFinite(corridorFloor)&&py-corridorFloor>.18;
        if(lower&&now-lastTurn>1200) {
            int next=-corridorDirection;
            boolean free=freeBody(px+next*.09,py)||floor!=null&&px>floor[0]+.10&&px<floor[2]-.10;
            if(free&&(pendingDrop||velocityY>=-.02)) {
                corridorDirection=next;corridor++;lastTurn=now;pendingDrop=false;dropX=Double.NaN;dropAt=-1;
                corridorFloor=py;corridorStartX=px;emptySweeps=0;scoutDone=false;returnTrack=-1;returnX=Double.NaN;phase="GROUND_SWEEP";
            }
        } else if(!Double.isFinite(corridorFloor))corridorFloor=py;
        if(!lower&&phase.equals("DROP_TO_CORRIDOR")&&now-dropAt>1800){dropX=Double.NaN;pendingDrop=false;phase="GROUND_SWEEP";}
    }
    private boolean freeBody(double x,double y) {
        for(double dx:new double[]{-bodyHalfW(),0,bodyHalfW()})for(double dy:new double[]{-bodyHalfH()*.5,0,bodyHalfH()*.5})if(worldValue(x+dx,y+dy)!=1)return false;
        return true;
    }
    private Decision blockedPrimitive(double[] wall,double[] opening,double[] floor) {
        if(opening!=null){dropX=opening[0];pendingDrop=true;dropAt=now;phase="DROP_TO_CORRIDOR";return followDrop();}
        double[] behind=findOpening(-corridorDirection,floor);
        if(wall!=null&&behind!=null){dropX=behind[0];pendingDrop=true;dropAt=now;phase="DROP_TO_CORRIDOR";return followDrop();}
        if(blockedSince<0)blockedSince=now;
        if(now<releaseUntil)return act(0,0,350,"Keep the blocked primitive released; do not alternate wall pushes");
        if(dispatch&&now-blockedSince>Math.max(3000,Math.min(8000,config.stuckTimeoutMs)))return decision(0,0,0,true,"No evidenced corridor continuation; preserve the map and inspect the obstruction");
        phase="RECOVERY";
        if(wall!=null) {
            double rise=py-bodyHalfH()-wall[1];
            if(frame.grounded&&rise>-.015&&rise<jumpRise*.8&&usedJumps==0&&roofGap()>.095&&failedEscapes==0) {
                if(dispatch)failedEscapes++;return act(corridorDirection,1,280,"Verified low ledge: try its ground-jump primitive once");
            }
            releaseUntil=now+350;return act(0,0,350,"Rectangular corridor wall: release and look for actual downward continuation");
        }
        if(frame.grounded&&groundJumpReady()&&failedEscapes==0){if(dispatch)failedEscapes++;return act(corridorDirection,1,280,"Failed move without a wall: one ground-jump probe in the committed direction");}
        if(dispatch)failedEscapes++;releaseUntil=now+350;
        return act(0,0,350,"Failed movement primitive: coast and reobserve instead of flipping direction");
    }
    private boolean roofStillOutsideView() {
        if(!validTerrain(frame)||frame.ceilingReached||roofGap()<.10)return false;
        double ahead=px+corridorDirection*.12;
        for(double[] b:borderRows)if((int)b[5]==room&&b[4]==2&&ahead>=b[0]-.03&&ahead<=b[2]+.03&&b[1]<py) {
            boolean checked=true;int count=0;
            for(Ceiling c:ceilings.values())if(c.section==room&&Math.abs(c.x-ahead)<.14&&Math.abs(c.y-b[1])<.03){count++;checked&=c.checked;}
            if(count>0&&checked)return false;
            return b[1]-cameraY<.17;
        }
        int total=0,free=0;double sx=Math.max(.07,Math.min(.93,frame.playerX+corridorDirection*.08));
        for(double sy=frame.playerY-bodyHalfH()-.06;sy>=.19;sy-=1./ROWS) {
            int v=localValue(sx,sy);if(v==2)return false;total++;if(v==1)free++;
        }
        return total>=4&&free>=Math.ceil(total*.75);
    }
    private void finishScout(){scoutDone=true;highRoofPending=false;returnX=Double.isFinite(scoutX)?scoutX:px;phase="RETURN_GROUND";}
    private boolean groundJumpReady(){return frame.grounded&&usedJumps==0&&now-lastJump>=Math.max(250,config.jumpSpacingMs)&&jumpFits(corridorDirection)&&!wallContact(corridorDirection);}
    private boolean measuredAirPulse(){return usedJumps<jumpBudget()&&now-lastJump>=Math.max(250,config.jumpSpacingMs)&&!frame.ceilingReached&&jumpFits(corridorDirection)&&(frame.grounded&&usedJumps==0||velocityY>=-.10);}
    /** A short ballistic primitive, checked as a swept body rather than an air-grid path. */
    private boolean jumpFits(int side){
        if(roofGap()<jumpRise+.035)return false;
        double impulse=Math.sqrt(2*gravity*jumpRise),horizon=Math.min(.65,2*impulse/gravity);
        for(double t=.04;t<=horizon;t+=.04){
            double xx=px+side*horizontalSpeed*t,yy=py-impulse*t+.5*gravity*t*t;
            for(double dx:new double[]{-bodyHalfW(),bodyHalfW()})for(double dy:new double[]{-bodyHalfH(),0})
                if(worldValue(xx+dx,yy+dy)==2)return false;
        }
        return true;
    }
    private Track forwardEnemy() {
        Track best=null;double score=Double.POSITIVE_INFINITY;
        for(Track t:tracks)if(t.section==room&&t.matched&&(t.touchedAt<0||now-t.touchedAt>=BURN_GRACE_MS)&&(t.x-px)*corridorDirection>=-.065) {
            double d=distance(px,py,t.x,t.y);if(d<score){score=d;best=t;}
        }
        return best;
    }
    private Track missedEnemy() {
        Track best=null;double score=Double.POSITIVE_INFINITY;
        for(Track t:tracks)if(t.section==room&&t.corridor==corridor&&t.deferredUntil<=now&&(t.touchedAt<0||now-t.touchedAt>=BURN_GRACE_MS)&&(t.x-px)*corridorDirection<-.085) {
            if(remainingEnemies==0&&!t.ceilingCandidate)continue;
            int side=t.x>px?1:-1;double[] wall=wallAhead(side);if(wall!=null&&wallGap(wall,side)<Math.abs(t.x-px)-.04)continue;
            double d=Math.abs(t.x-px);if(d<score){score=d;best=t;}
        }
        return best;
    }
    private void startEnemyReturn(Track t){
        returnTrack=t.id;returnAt=now;phase="REVISIT_ENEMY";
        returnDeadline=now+Math.max(6500,Math.min(60000,(long)(Math.abs(t.x-px)/Math.max(.08,horizontalSpeed)*1800)+3000));
    }
    private Decision returnToEnemy(Track t) {
        if(now>returnDeadline||t.section!=room){t.deferredUntil=now+5000;returnTrack=-1;phase="GROUND_SWEEP";return null;}
        if(t.touchedAt>=0&&now-t.touchedAt<BURN_GRACE_MS){returnTrack=-1;phase="GROUND_SWEEP";return null;}
        if(touching(t,frame)){markContact(t);returnTrack=-1;phase="GROUND_SWEEP";return act(corridorDirection,0,t.heavy>.6?260:180,"Named missed enemy contact attempted; resume the committed corridor direction");}
        goal=new Goal(t.x,t.y,"named missed enemy "+t.id,t.id);
        int side=Math.abs(t.x-px)>.035?(t.x>px?1:-1):0;
        if(wallContact(side)){t.deferredUntil=now+2500;returnTrack=-1;phase="GROUND_SWEEP";return null;}
        if(dispatch&&blockedSince>=returnAt&&now-blockedSince>3000)
            return decision(0,0,0,true,"Named enemy return made no horizontal progress; preserve the map and inspect the passage");
        int pulse=frame.grounded&&usedJumps==0&&jumpFits(side)&&now-lastJump>=Math.max(250,config.jumpSpacingMs)?1:0;
        if(t.ceilingCandidate&&t.y<py-.13&&measuredAirPulse())pulse=1;
        return act(side,pulse,normalDuration(),pulse>0?"Named enemy return: ground jump through its body":"Named enemy return: descend/coast to its stored ground position");
    }
    private Decision remainingSweep(){
        if(remainingEnemies==0){phase="GROUND_SWEEP";remainingSweepAt=-1;emptySweeps=0;return null;}
        for(Track t:tracks)if(t.section==room&&t.corridor==corridor&&t.matched&&touching(t,frame))markContact(t);
        int side=-corridorDirection;
        boolean reached=Double.isFinite(corridorStartX)&&(px-corridorStartX)*corridorDirection<.08;
        if(reached||wallContact(side)){
            emptySweeps++;phase="GROUND_SWEEP";remainingSweepAt=-1;
            return act(corridorDirection,0,normalDuration(),"Ground return sweep finished: sweep forward again and re-read the enemy count");
        }
        if(dispatch&&blockedSince>=remainingSweepAt&&now-blockedSince>3000)
            return decision(0,0,0,true,"Remaining-enemy return made no progress; do not count a partial sweep as complete");
        int jump=frame.grounded&&usedJumps==0&&jumpFits(side)&&now-lastJump>=Math.max(250,config.jumpSpacingMs)?1:0;
        return act(side,jump,normalDuration(),"Enemy counter is positive: jump-move back through the observed corridor to find missed bots");
    }
    private void resolveOrdinary(){for(int i=tracks.size()-1;i>=0;i--)if(!tracks.get(i).ceilingCandidate&&tracks.get(i).sector<=activeSector&&(runEnded||!tracks.get(i).matched))retireTrack(i);}
    private void markContact(Track t){if(t.touchedAt<0||now-t.touchedAt>=BURN_GRACE_MS){t.touchedAt=now;t.missingFrames=0;t.absentSince=-1;}}
    private Decision unregisteredPass() {
        if(cameraGapAt>=0&&now-cameraGapAt>8000)return decision(0,0,0,true,"Camera registration did not recover; saved map remains partial");
        if(wallContact(corridorDirection)||frame.ceilingReached)return act(0,0,350,"Unregistered contact: release movement and preserve the corridor direction");
        if(frame.grounded&&localFree(frame.playerX+corridorDirection*.06,frame.playerY))return act(corridorDirection,0,180,"Camera registration uncertain: short grounded probe in the corridor direction; atlas frozen");
        return act(0,0,350,"Unregistered airborne view: fall naturally while the camera recovers; atlas frozen");
    }
    private Decision act(int dir,int pulse,long duration,String text){if(dispatch&&pulse>0)recordJump(registered);return remember(decision(dir,pulse,duration,false,text));}
    private long normalDuration(){return Math.max(150,Math.min(350,config.moveMs));}
    private double bodyHalfW(){return validCoordinate(frame.playerLeft)&&validCoordinate(frame.playerRight)?Math.max(.014,Math.min(.065,(frame.playerRight-frame.playerLeft)/2)):.02;}
    private double bodyHalfH(){return validCoordinate(frame.playerTop)&&validCoordinate(frame.playerBottom)?Math.max(.024,Math.min(.09,(frame.playerBottom-frame.playerTop)/2)):.035;}
    private int worldValue(double x,double y){return mapValue(cellX(x),cellY(y));}
    private int mapValue(int x,int y){Tile t=tiles.get(key(x,y));return t==null?0:t.solid?2:1;}
    private boolean completeEvidence() {
        if(!runEnded||!runSucceeded||room!=0||pendingAnchor||highRoofPending||!unseenRoofColumns.isEmpty()||!tracks.isEmpty()||ceilings.isEmpty())return false;
        for(Ceiling c:ceilings.values())if(!c.checked)return false;
        for(Map.Entry<Long,Tile> e:tiles.entrySet())if(!e.getValue().solid) {
            int x=x(e.getKey()),y=y(e.getKey());if(mapValue(x-1,y)==0||mapValue(x+1,y)==0||mapValue(x,y-1)==0||mapValue(x,y+1)==0)return false;
        }
        return true;
    }
    private boolean localSolid(double sx,double sy){return localValue(sx,sy)==2;}
    private boolean localFree(double sx,double sy){return localValue(sx,sy)==1;}
    private int localValue(double sx,double sy) {
        if(!validTerrain(frame)||!valid(sx,sy))return 0;
        int xx=Math.min(frame.terrainCols-1,(int)(sx*frame.terrainCols)),yy=Math.min(frame.terrainRows-1,(int)(sy*frame.terrainRows));
        return frame.terrainCells[yy*frame.terrainCols+xx];
    }
    private boolean touching(Track t,FarmEngine.Frame f) {
        double l=validCoordinate(f.playerLeft)?f.playerLeft+cameraX:px-.018,r=validCoordinate(f.playerRight)?f.playerRight+cameraX:px+.018;
        double top=validCoordinate(f.playerTop)?f.playerTop+cameraY:py-.030,bottom=validCoordinate(f.playerBottom)?f.playerBottom+cameraY:py+.030;
        return t.x+t.width/2>=l&&t.x-t.width/2<=r&&t.y+t.height/2>=top&&t.y-t.height/2<=bottom;
    }
    private Track track(int id){if(id<0)return null;for(Track t:tracks)if(t.id==id)return t;return null;}
    private Decision remember(Decision d){previousCommand=dispatch&&d.direction!=0; if(dispatch&&d.direction!=0)lastDirection=d.direction;return d;}
    private void recordJump(boolean learn) {
        usedJumps++;lastJump=now;
        learningJump=learn&&now-frame.capturedAt<=300&&!frame.ceilingReached;
        jumpOriginY=jumpMinimumY=py;jumpObservedAfter=now;
    }
    private Decision decision(int dir,int jumps,long ms,boolean pause,String text){reason=text;return new Decision(dir,jumps,ms,pause,text);}
    private int jumpBudget(){return Math.max(1,Math.min(21,config.jumpBudget));}
    private static boolean validTerrain(FarmEngine.Frame f){return f!=null&&f.terrainCols>0&&f.terrainRows>0&&f.terrainCols<=96&&f.terrainRows<=48&&f.terrainCells!=null&&f.terrainCells.length==f.terrainCols*f.terrainRows;}
    private static boolean validCoordinate(double v){return Double.isFinite(v)&&v>=0&&v<=1;}
    private static boolean valid(double x,double y){return validCoordinate(x)&&validCoordinate(y);}
    private static double distance(double ax,double ay,double bx,double by){return Math.abs(ax-bx)+.55*Math.abs(ay-by);}
    private static int cellX(double x){return (int)Math.floor(x*COLS);}
    private static int cellY(double y){return (int)Math.floor(y*ROWS);}
    private static double wx(int x){return (x+.5)/COLS;}
    private static double wy(int y){return (y+.5)/ROWS;}
    private static long key(int x,int y){return ((long)x<<32)|(y&0xffffffffL);}
    private static int x(long key){return (int)(key>>32);}
    private static int y(long key){return (int)key;}
}
