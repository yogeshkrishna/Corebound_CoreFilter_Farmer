package com.corefilter.farmer.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** A bounded, on-device room atlas. It maps geometry, not brightness or projectile motion.
 * Camera registration makes off-screen targets and inspected ceiling sections persist.
 * This is partial observation: a contact is only a burn attempt, never a confirmed kill.
 */
public final class MapNavigator {
    private static final int COLS = 48, ROWS = 24, MAX_CELLS = 16000, MAX_ROUTE = 3500;
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
        public final String goal, reason;
        public final long blockedForMs;
        /** [world x, world y, occupancy, visited] and [enemy x, enemy y, burn attempt, last seen]. */
        public final double[][] cells, enemies;
        private Snapshot(MapNavigator n) {
            room = n.room; mapCells = n.tiles.size(); unresolvedEnemies = n.tracks.size();
            remainingJumps = Math.max(0, n.jumpBudget() - n.usedJumps);
            cameraX = n.cameraX; cameraY = n.cameraY; playerX = n.px; playerY = n.py;
            learnedJumpRise=n.jumpRise;verticalVelocity=n.velocityY;
            goalX = n.goal == null ? Double.NaN : n.goal.x; goalY = n.goal == null ? Double.NaN : n.goal.y;
            goal = n.goal == null ? "observe" : n.goal.kind; reason = n.reason;
            blockedForMs = n.blockedSince < 0 || n.now < 0 ? 0 : n.now - n.blockedSince;
            int done = 0; for (Ceiling c : n.ceilings.values()) if (c.checked) done++;
            inspectedCeilings = done; ceilingSections = n.ceilings.size();
            cells = new double[n.tiles.size()][4]; int i = 0;
            for (Map.Entry<Long, Tile> e : n.tiles.entrySet()) {
                cells[i++] = new double[]{wx(x(e.getKey())), wy(y(e.getKey())), e.getValue().solid ? 2 : 1, e.getValue().visited ? 1 : 0};
            }
            enemies = new double[n.tracks.size()][4]; i = 0;
            for (Track t : n.tracks) enemies[i++] = new double[]{t.x, t.y, t.touchedAt < 0 ? 0 : 1, t.seenAt};
        }
    }

    private static final class Tile { boolean solid, visited; int evidence; long seenAt; }
    private static final class Ceiling { double x, y; boolean checked; long deferredUntil; Ceiling(double x, double y) { this.x=x;this.y=y; } }
    private static final class Track {
        int id, missingFrames, sector; double x, y, width, height, heavy;boolean ceilingCandidate;
        long seenAt, touchedAt = -1, absentSince = -1, deferredUntil; boolean matched;
    }
    private static final class Goal {
        final double x, y; final String kind; final int track;
        Goal(double x, double y, String kind, int track) { this.x=x;this.y=y;this.kind=kind;this.track=track; }
    }
    private static final class Node {
        final long cell; final double cost, rank;
        Node(long cell, double cost, double rank) { this.cell=cell;this.cost=cost;this.rank=rank; }
    }

    private final FarmEngine.Config config;
    private final HashMap<Long, Tile> tiles = new HashMap<>();
    private final HashMap<Long, Ceiling> ceilings = new HashMap<>();
    private final ArrayList<Track> tracks = new ArrayList<>();
    private double cameraX, cameraY, biasX, biasY, px = Double.NaN, py = Double.NaN;
    private double previousX = Double.NaN, previousY = Double.NaN, velocityY;
    private long now=-1, previousAt=-1, lastJump=-100000, blockedSince=-1, lastProgress=-1, roofSince=-1;
    private long escapeUntil=-1, gateAt=-1, uncertainSince=-1, discontinuityAt=-1, cameraGapAt=-1;
    private int epoch=Integer.MIN_VALUE, room, nextTrack=1, usedJumps, groundedFrames, airFrames;
    private int lastDirection=1, escapeDirection=-1, failedEscapes, routeFailures;
    private int goalDecisions, completedSector, activeSector=1;
    private boolean pendingAnchor, sectorCleared;
    private double gateX=Double.NaN, gateY=Double.NaN;
    private boolean sawAir, initialMap=true, registered, previousCommand, lastGrounded, pendingSceneChange, dispatch=true;
    private Set<Long> reachable=Collections.emptySet();
    private long goalProgressAt=-1;
    private double goalDistance=Double.POSITIVE_INFINITY;
    private double jumpRise=.16, jumpOriginY, jumpMinimumY;
    private long jumpObservedAfter=-1;
    private int learnedJumps;
    private boolean learningJump;
    private Goal goal;
    private String reason="Waiting for the player and terrain";
    private FarmEngine.Frame frame;

    public MapNavigator(FarmEngine.Config config) { this.config=config; }
    public void reset() {
        tiles.clear();ceilings.clear();tracks.clear(); cameraX=cameraY=biasX=biasY=0;
        px=py=previousX=previousY=Double.NaN;velocityY=0;now=previousAt=-1;lastJump=-100000;
        blockedSince=roofSince=escapeUntil=gateAt=uncertainSince=discontinuityAt=cameraGapAt=-1;lastProgress=-1;
        epoch=Integer.MIN_VALUE;room=0;nextTrack=1;usedJumps=groundedFrames=airFrames=0;
        lastDirection=1;escapeDirection=-1;failedEscapes=routeFailures=0;sawAir=previousCommand=false;
        gateX=gateY=Double.NaN;goal=null;registered=false;initialMap=true;
        reason="Waiting for the player and terrain";frame=null;pendingSceneChange=false;
        reachable=Collections.emptySet();goalProgressAt=-1;goalDistance=Double.POSITIVE_INFINITY;
        learningJump=false;jumpObservedAfter=-1;jumpRise=.16;learnedJumps=0;
        goalDecisions=completedSector=0;activeSector=1;pendingAnchor=sectorCleared=false;
    }
    public Snapshot snapshot() { return new Snapshot(this); }
    public int room() { return room; }
    public boolean sectorCleared() { return sectorCleared; }
    public Decision observe(FarmEngine.Frame f) { return next(f,false); }

    public Decision next(FarmEngine.Frame f) {
        return next(f,true);
    }
    private Decision next(FarmEngine.Frame f,boolean dispatchActions) {
        dispatch=dispatchActions;
        frame=f;now=f.now;
        if (f.playerConfidence < PLAYER_MIN || !valid(f.playerX, f.playerY)) {
            previousCommand=false;
            if (uncertainSince < 0) uncertainSince=now;
            return decision(0,0,0,now-uncertainSince>5000,"Player position uncertain; observe before moving");
        }
        uncertainSince=-1;
        registerCamera(f);
        if(registered)cameraGapAt=-1;else if(cameraGapAt<0)cameraGapAt=now;
        px=f.playerX+cameraX;py=f.playerY+cameraY;
        boolean anchorFrame=registered||(previousAt<0&&cameraX==0&&cameraY==0);
        boolean spatial = validTerrain(f);
        if (spatial && (initialMap || registered)) {
            integrateTerrain(f);initialMap=false;
        }
        // Masked player pixels must not leave a hole at the start of a route.
        if(anchorFrame) {
            fillPlayerMask(f);updateMotionAndJumps(f);updateTracks(f);updateCeilingCoverage();
        } else updateGroundEvidence(f);
        if (anchorFrame && f.gate && validCoordinate(f.gateX)) {
            if(Double.isFinite(gateX)&&Math.abs(f.gateX+cameraX-gateX)>.35)sectorCleared=false;
            gateX=f.gateX+cameraX;gateY=validCoordinate(f.gateY)?f.gateY+cameraY:py;gateAt=now;
        }
        if(f.completedSector>completedSector) {
            completedSector=f.completedSector;activeSector=Math.max(activeSector,completedSector+1);sectorCleared=true;
            // Sector clear is authoritative for ordinary bots; a ceiling candidate
            // may be a non-gating Spectrum and still needs its mapped position checked.
            for(int i=tracks.size()-1;i>=0;i--)if(tracks.get(i).sector<=completedSector&&!tracks.get(i).ceilingCandidate)tracks.remove(i);
            goal=null;
        }
        if(anchorFrame)reachable=reachableCells(nearestFree(px,py,3));
        if (tiles.size()>MAX_CELLS) return decision(0,0,0,true,"Room atlas limit reached; inspect the room");

        // Detection acts on body motion plus camera displacement; effects and HUD changes do not help.
        boolean wall=(lastDirection>0&&f.wallRight)||(lastDirection<0&&f.wallLeft)||horizontalSolid(lastDirection);
        if (dispatch && previousCommand && wall && blockedSince<0) blockedSince=now;
        if (dispatch && blockedSince>=0 && now-blockedSince>=550 && escapeUntil<now) {
            escapeDirection=-lastDirection;escapeUntil=now+650;blockedSince=-1;goal=null;failedEscapes++;
        }
        if (lastProgress>=0 && now-lastProgress>Math.max(3500,config.stuckTimeoutMs)
                && failedEscapes>Math.max(1,config.maxRecoveries))
            return decision(0,0,0,true,"No body or camera progress after alternate routes; inspect the room");
        if (now<escapeUntil) {
            int escape=horizontalSolid(escapeDirection)?0:escapeDirection;
            return remember(decision(escape,0,200,false,"Blocked passage: retreat and drop below the obstacle"));
        }
        if (f.ceilingReached || roofTooClose()) {
            if (roofSince<0) roofSince=now;
            // A roof contact is not a navigation target. Coasting immediately permits gravity to release it.
            int side=horizontalSolid(lastDirection)?-lastDirection:lastDirection;
            if (horizontalSolid(side)) side=0;
            return remember(decision(side,0,180,false,"Ceiling clearance: stop jumping and descend into the room"));
        }
        roofSince=-1;

        // With uncertain camera registration, only current-frame targets are usable.
        // Keep the atlas frozen, rather than merging moving screen coordinates into it.
        if(!anchorFrame&&Double.isFinite(f.cameraX)) {
            if(cameraGapAt>=0&&now-cameraGapAt>8000)return decision(0,0,0,true,"Camera registration has not recovered; inspect the capture view");
            double[] local=nearestLocalEnemy(f);
            if(local!=null) {
                double ex=(local[0]+local[2])/2,ey=(local[1]+local[3])/2;
                int toward=Math.abs(ex-f.playerX)<.025?0:(ex>f.playerX?1:-1);
                if(horizontalSolid(toward))toward=0;
                int pulse=jumpNeeded(ey<f.playerY-.07,ey-f.playerY,f)?1:0;
                if(pulse>0&&dispatch)recordJump(false);
                return remember(decision(toward,pulse,180,false,"Camera registration uncertain: follow only the currently visible target"));
            }
            int probe=lastDirection;
            if(horizontalSolid(probe))probe=-probe;
            if(f.grounded&&!horizontalSolid(probe)&&localFree(f.playerX+probe*.06,f.playerY))
                return remember(decision(probe,0,180,false,"Camera registration uncertain: short grounded probe through visible clear space"));
            return remember(decision(0,0,0,false,"Camera registration uncertain: preserve the atlas and reobserve"));
        }

        Goal selected=chooseGoal();
        if (selected!=null) {
            if(goal==null||goal.track!=selected.track||!goal.kind.equals(selected.kind)
                    ||distance(goal.x,goal.y,selected.x,selected.y)>.08) {
                goalProgressAt=now;goalDistance=distance(px,py,selected.x,selected.y);
                goalDecisions=0;
            }
            goal=selected;
            goalDecisions++;
        }
        if (goal==null) {
            int advance=Double.isFinite(gateX)&&gateX<px?-1:1;
            if(horizontalSolid(advance))advance=-advance;
            // An unregistered/unknown image gets a short guarded probe, not a timed jump loop.
            return remember(decision(horizontalSolid(advance)?0:advance,0,180,false,"Observe the next room section with a short guarded advance"));
        }
        Track target=track(goal.track);
        double distanceToGoal=distance(px,py,goal.x,goal.y);
        if(distanceToGoal<goalDistance-.02){goalDistance=distanceToGoal;goalProgressAt=now;}
        if(dispatch&&goalDecisions>=4&&goalProgressAt>=0&&now-goalProgressAt>2500) {
            deferGoal(goal,now+2000);goal=null;goalProgressAt=now;
            Goal boundary=reachableFrontier(nearestFree(px,py,3),null);
            if(boundary!=null)goal=boundary;
            if(goal==null)return remember(decision(horizontalSolid(-lastDirection)?0:-lastDirection,0,180,false,"Target route made no progress: descend and revisit it from another passage"));
            target=null;
        }
        if (target!=null && touching(target,f)) {
            target.touchedAt=now;target.missingFrames=0;target.absentSince=-1;
            int through=target.x>=px?1:-1;
            if(horizontalSolid(through))through=-through;
            long contact=target.heavy>.6?260:Math.max(100,Math.min(220,config.settleMs));
            goal=null;
            return remember(decision(through,0,contact,false,"Ember contact attempted; keep moving and verify the target after burn time"));
        }

        long start=nearestFree(px,py,3), finish=nearestFree(goal.x,goal.y,5);
        List<Long> route=(spatial&&tiles.size()>8)?route(start,finish):Collections.emptyList();
        double nextX=goal.x,nextY=goal.y;
        if(!route.isEmpty()) {
            routeFailures=0;
            // Look two cells ahead while retaining the obstacle-constrained first step.
            long next=route.get(Math.min(2,route.size()-1));nextX=wx(x(next));nextY=wy(y(next));
        } else if(spatial && distance(px,py,goal.x,goal.y)>.12) {
            routeFailures++;
            // There is no certified route to this target. Go to reachable free-space boundaries instead.
            Goal detour=reachableFrontier(start,goal);
            if(detour!=null){nextX=detour.x;nextY=detour.y;reason="Follow free terrain around the blocked target";}
            else return remember(decision(horizontalSolid(lastDirection)?-lastDirection:lastDirection,0,160,false,"No clear route: change position below the obstacle and reobserve"));
        }
        double dx=nextX-px,dy=nextY-py;
        int dir=Math.abs(dx)<.025?0:(dx>0?1:-1);
        if(dir!=0&&horizontalSolid(dir)) {
            // A vertical waypoint is preferable to continuing to push into a mapped wall.
            dir=0;
            if(dy>=-.035)return remember(decision(-lastDirection,0,180,false,"Mapped wall: retreat toward reachable free space"));
        }
        boolean above=dy<-.045 || (goal.y<py-.085 && Math.abs(goal.x-px)<.18 && !horizontalSolid(dir));
        int jump=jumpNeeded(above,dy,f)?1:0;
        if(jump>0&&dispatch)recordJump(registered);
        // A high goal with depleted Hookshots explicitly chooses a landing, never repeats dead taps.
        if(above&&usedJumps>=jumpBudget()&&jump==0&&!f.grounded) {
            Goal landing=landingGoal();
            if(landing!=null){int toward=Math.abs(landing.x-px)<.035?0:(landing.x>px?1:-1);dir=horizontalSolid(toward)?0:toward;}
            return remember(decision(dir,0,200,false,"Hookshots spent: land on mapped support before the next ascent"));
        }
        long duration=Math.max(120,Math.min(350,config.moveMs));
        if(target!=null&&distance(px,py,target.x,target.y)<.08)duration=Math.min(220,duration);
        String task=goal.kind.equals("enemy")?"Follow the stored enemy position":goal.kind.equals("ceiling")?"Inspect an unchecked ceiling section with clearance":goal.kind.equals("gate")?"Return to the gate after checking the room":"Explore a reachable boundary of the room atlas";
        if(jump>0)task+="; one Hookshot, then observe its motion";
        else if(above&&velocityY<-.07)task+="; coast during the current ascent";
        return remember(decision(dir,jump,duration,false,task));
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
                room++;biasX=room*20-f.cameraX;biasY=-f.cameraY;cameraX=room*20;cameraY=0;
                initialMap=true;previousX=previousY=Double.NaN;previousAt=-1;goal=null;blockedSince=-1;
                gateX=gateY=Double.NaN;gateAt=-1;failedEscapes=0;
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
            if(t==null){t=new Tile();t.evidence=sample==2?2:-2;tiles.put(key,t);}
            else t.evidence=Math.max(-4,Math.min(4,t.evidence+(sample==2?2:-2)));
            t.solid=t.evidence>0;t.seenAt=now;
        }
        // Ceiling goals are below the underside by a body clearance, not inside the roof.
        for(int yy=1;yy<f.terrainRows-4;yy++)for(int xx=1;xx<f.terrainCols-1;xx+=3) {
            if(f.terrainCells[yy*f.terrainCols+xx]!=2)continue;
            if(f.terrainCells[(yy+1)*f.terrainCols+xx]!=1||f.terrainCells[(yy+2)*f.terrainCols+xx]!=1||f.terrainCells[(yy+3)*f.terrainCols+xx]!=1)continue;
            double cx=(xx+.5)/f.terrainCols+cameraX,cy=(yy+3.3)/f.terrainRows+cameraY;
            long section=key(cellX(cx)/3,cellY(cy));
            if(!ceilings.containsKey(section))ceilings.put(section,new Ceiling(cx,cy));
        }
    }

    private void observeFree(int x,int y,boolean visit) {
        Tile t=tiles.get(key(x,y));if(t==null){t=new Tile();t.evidence=-2;tiles.put(key(x,y),t);}
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
            velocityY=dy/Math.max(.08,dt);
            boolean measurable=registered||(!Double.isFinite(f.cameraX)&&Math.abs(f.cameraDx)+Math.abs(f.cameraDy)<.001);
            if(measurable&&Math.abs(dx)+Math.abs(dy)>.010) {
                lastProgress=now;
                if(Math.abs(dx)>.08)failedEscapes=Math.max(0,failedEscapes-1);
            }
            if(measurable&&Math.abs(dx)>.007)blockedSince=-1;
            else if(dispatch&&measurable&&previousCommand&&lastDirection!=0&&measuredAt-previousAt>=100&&blockedSince<0)blockedSince=now-(measuredAt-previousAt);
            if(!measurable)velocityY=0;
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
        previousX=px;previousY=py;previousAt=measuredAt;lastGrounded=f.grounded;
    }
    private void updateGroundEvidence(FarmEngine.Frame f) {
        if(f.grounded) {
            groundedFrames++;airFrames=0;
            if(groundedFrames>=2&&sawAir){usedJumps=0;sawAir=false;}
        } else {groundedFrames=0;airFrames++;if(airFrames>=2&&usedJumps>0)sawAir=true;}
    }

    private void updateTracks(FarmEngine.Frame f) {
        for(Track t:tracks)t.matched=false;
        if(f.enemyBoxes!=null)for(double[] b:f.enemyBoxes) {
            if(b==null||b.length<4||!valid(b[0],b[1])||!valid(b[2],b[3])||b[2]<=b[0]||b[3]<=b[1])continue;
            double ex=(b[0]+b[2])/2+cameraX,ey=(b[1]+b[3])/2+cameraY;
            Track best=null;double closest=.14;
            for(Track t:tracks)if(!t.matched) {
                double d=distance(ex,ey,t.x,t.y);if(d<closest){closest=d;best=t;}
            }
            if(best==null){best=new Track();best.id=nextTrack++;best.sector=activeSector;tracks.add(best);}
            best.x=ex;best.y=ey;best.width=b[2]-b[0];best.height=b[3]-b[1];
            best.heavy=b.length>5?b[5]:0;best.seenAt=now;best.matched=true;
            best.ceilingCandidate|=ey<py-.14;
            best.absentSince=-1;best.missingFrames=0;
            if(b.length>4&&b[4]>=.6&&best.touchedAt<0)best.touchedAt=now;
        }
        for(int i=tracks.size()-1;i>=0;i--) {
            Track t=tracks.get(i);if(t.matched)continue;
            double sx=t.x-cameraX,sy=t.y-cameraY;
            // Off-screen or occluded absence cannot clear an enemy from memory.
            boolean revisited=Math.abs(t.x-px)<.15&&Math.abs(t.y-py)<.16;
            boolean visible=sx>.08&&sx<.93&&sy>.18&&sy<.70;
            boolean clear=localFree(sx,sy)&&registered;
            if(revisited&&visible&&clear&&now-t.seenAt>=BURN_GRACE_MS) {
                if(t.absentSince<0)t.absentSince=now;t.missingFrames++;
                if(t.missingFrames>=3&&now-t.absentSince>=500) {
                    if(t.touchedAt>=0)tracks.remove(i);
                    else {t.deferredUntil=now+5000;t.absentSince=-1;t.missingFrames=0;}
                }
            } else {t.missingFrames=0;t.absentSince=-1;}
        }
    }

    private void updateCeilingCoverage() {
        for(Ceiling c:ceilings.values())if(Math.abs(c.x-px)<.10&&Math.abs(c.y-py)<.10)c.checked=true;
        if(goal!=null&&!goal.kind.equals("enemy")&&distance(px,py,goal.x,goal.y)<.065) {
            Tile t=tiles.get(key(cellX(goal.x),cellY(goal.y)));if(t!=null)t.visited=true;
            goal=null;
        }
    }

    private Goal chooseGoal() {
        Track best=null;double score=Double.POSITIVE_INFINITY;
        for(Track t:tracks) {
            if(t.touchedAt>=0&&now-t.touchedAt<BURN_GRACE_MS)continue;
            if(t.deferredUntil>now)continue;
            if(!goalReachable(t.x,t.y)){t.deferredUntil=now+1600;continue;}
            double s=distance(px,py,t.x,t.y)+(now-t.seenAt>1200?.03:0);
            // Retaining a reachable goal prevents frame-to-frame target oscillation.
            if(goal!=null&&goal.track==t.id)s-=.06;
            if(t.y<py-.10)s-=.03;
            if(s<score){score=s;best=t;}
        }
        if(best!=null)return new Goal(best.x,best.y,"enemy",best.id);
        Ceiling unchecked=null;score=Double.POSITIVE_INFINITY;
        for(Ceiling c:ceilings.values())if(!c.checked) {
            if(c.deferredUntil>now)continue;
            if(!goalReachable(c.x,c.y)){c.deferredUntil=now+1600;continue;}
            double s=distance(px,py,c.x,c.y)+(c.x<px-.12?.06:0);
            if(s<score){score=s;unchecked=c;}
        }
        if(unchecked!=null)return new Goal(unchecked.x,unchecked.y,"ceiling",-1);
        if(goal!=null&&goal.kind.equals("frontier")&&distance(px,py,goal.x,goal.y)>.065
                &&passable(key(cellX(goal.x),cellY(goal.y)))&&isFrontier(key(cellX(goal.x),cellY(goal.y))))return goal;
        Goal frontier=reachableFrontier(nearestFree(px,py,3),null);
        if(frontier!=null)return frontier;
        if(Double.isFinite(gateX)&&goalReachable(gateX,gateY))return new Goal(gateX,gateY,"gate",-1);
        return null;
    }

    private Goal reachableFrontier(long start,Goal toward) {
        if(!passable(start))return null;
        ArrayDeque<Long> q=new ArrayDeque<>();Set<Long>seen=new HashSet<>();q.add(start);seen.add(start);
        Goal best=null;double score=Double.NEGATIVE_INFINITY;int explored=0;
        while(!q.isEmpty()&&explored++<MAX_ROUTE) {
            long k=q.removeFirst();int tx=x(k),ty=y(k);Tile tile=tiles.get(k);
            for(int[]d:NEIGHBORS) {
                long n=key(tx+d[0],ty+d[1]);
                if(tiles.containsKey(n)&&passable(n)&&seen.add(n))q.addLast(n);
            }
            double fx=wx(tx),fy=wy(ty),dist=distance(px,py,fx,fy);
            if(!isFrontier(k)||dist<.085||tile==null||tile.visited)continue;
            // Ignore screen-mask borders: exploration must lead through playable room space.
            double sy=fy-cameraY;if(sy<.21||sy>.84)continue;
            double s=-dist+(fy<py-.05?.08:0)+(fx>px?.16:0);
            if(toward!=null)s-=distance(fx,fy,toward.x,toward.y);
            if(s>score){score=s;best=new Goal(fx,fy,"frontier",-1);}
        }
        return best;
    }
    private boolean isFrontier(long cell) {
        int unknown=0,xx=x(cell),yy=y(cell);
        for(int[]d:NEIGHBORS) {
            long next=key(xx+d[0],yy+d[1]);if(tiles.containsKey(next))continue;
            double sx=wx(xx+d[0])-cameraX,sy=wy(yy+d[1])-cameraY;
            if(sy<.20||sy>.86||sx<.42&&sy>.67)continue;
            unknown++;
        }
        return unknown>=3;
    }

    private Goal landingGoal() {
        Goal best=null;double score=Double.POSITIVE_INFINITY;
        for(Map.Entry<Long,Tile>e:tiles.entrySet())if(e.getValue().solid) {
            int tx=x(e.getKey()),ty=y(e.getKey());double lx=wx(tx),ly=wy(ty-2);
            if(ly<py+.04||Math.abs(lx-px)>.35||!passable(key(tx,ty-2)))continue;
            double s=distance(px,py,lx,ly);if(s<score){score=s;best=new Goal(lx,ly,"land",-1);}
        }
        return best;
    }

    private static final int[][] NEIGHBORS={{1,0},{-1,0},{0,1},{0,-1},{1,1},{-1,1},{1,-1},{-1,-1}};
    private List<Long> route(long start,long finish) {
        if(start==Long.MIN_VALUE||finish==Long.MIN_VALUE||!passable(start)||!passable(finish))return Collections.emptyList();
        if(start==finish)return Collections.singletonList(start);
        PriorityQueue<Node>open=new PriorityQueue<>(Comparator.comparingDouble(n->n.rank));
        HashMap<Long,Double>cost=new HashMap<>();HashMap<Long,Long>parent=new HashMap<>();
        cost.put(start,0.);open.add(new Node(start,0,heuristic(start,finish)));int expanded=0;
        while(!open.isEmpty()&&expanded++<MAX_ROUTE) {
            Node node=open.poll();if(node.cost>cost.get(node.cell)+.0001)continue;
            if(node.cell==finish) {
                ArrayList<Long>p=new ArrayList<>();long k=finish;p.add(k);
                while(k!=start){k=parent.get(k);p.add(k);}Collections.reverse(p);return p;
            }
            int xx=x(node.cell),yy=y(node.cell);
            for(int[]d:NEIGHBORS) {
                long next=key(xx+d[0],yy+d[1]);if(!passable(next))continue;
                if(d[0]!=0&&d[1]!=0&&(!passable(key(xx+d[0],yy))||!passable(key(xx,yy+d[1]))))continue;
                // Falling is free, climbing needs a remaining Hookshot or a supported landing.
                if(!heightReachable(yy+d[1]))continue;
                double add=d[0]!=0&&d[1]!=0?1.42:1;
                if(d[1]<0)add+=.7; // conserve height and avoid gratuitous ceiling hugging
                double nc=node.cost+add;
                if(nc<cost.getOrDefault(next,Double.POSITIVE_INFINITY)) {
                    cost.put(next,nc);parent.put(next,node.cell);open.add(new Node(next,nc,nc+heuristic(next,finish)));
                }
            }
        }
        return Collections.emptyList();
    }
    private double heuristic(long a,long b){return Math.abs(x(a)-x(b))+Math.abs(y(a)-y(b));}
    private Set<Long> reachableCells(long start) {
        if(!passable(start))return Collections.emptySet();
        Set<Long> set=new HashSet<>();ArrayDeque<Long> q=new ArrayDeque<>();set.add(start);q.add(start);
        int count=0;
        while(!q.isEmpty()&&count++<MAX_ROUTE) {
            long k=q.removeFirst();int xx=x(k),yy=y(k);
            for(int[]d:NEIGHBORS) {
                long n=key(xx+d[0],yy+d[1]);if(!passable(n)||!heightReachable(yy+d[1]))continue;
                if(d[0]!=0&&d[1]!=0&&(!passable(key(xx+d[0],yy))||!passable(key(xx,yy+d[1]))))continue;
                if(set.add(n))q.addLast(n);
            }
        }
        return set;
    }
    private boolean heightReachable(int row) {
        // A geometry path cannot invent vertical flight after the last charge.
        // Momentum adds a short remaining coast, and later observed landings refill the budget.
        double rise=Math.max(0,jumpBudget()-usedJumps)*jumpRise*.9+Math.max(0,-velocityY)*.22;
        return wy(row)>=py-rise-.045;
    }
    private boolean goalReachable(double gx,double gy) {
        if(!validTerrain(frame))return true;
        long closest=nearestFree(gx,gy,4);
        return closest!=Long.MIN_VALUE&&distance(wx(x(closest)),wy(y(closest)),gx,gy)<.11
                &&reachable.contains(closest)&&heightReachable(y(closest));
    }
    private void deferGoal(Goal stale,long until) {
        Track t=track(stale.track);if(t!=null)t.deferredUntil=until;
        if(stale.kind.equals("ceiling"))for(Ceiling c:ceilings.values())
            if(distance(c.x,c.y,stale.x,stale.y)<.05)c.deferredUntil=until;
    }
    private boolean passable(long key) {
        Tile center=tiles.get(key);if(center==null||center.solid)return false;
        int xx=x(key),yy=y(key);
        // Use the observed hull, not a fixed one-cell point robot, for clearance.
        double halfW=frame!=null&&validCoordinate(frame.playerLeft)&&validCoordinate(frame.playerRight)?
                Math.max(.014,Math.min(.065,(frame.playerRight-frame.playerLeft)/2)):.018;
        double halfH=frame!=null&&validCoordinate(frame.playerTop)&&validCoordinate(frame.playerBottom)?
                Math.max(.024,Math.min(.090,(frame.playerBottom-frame.playerTop)/2)):.030;
        int rx=(int)Math.ceil(halfW*COLS+.5),ry=(int)Math.ceil(halfH*ROWS+.5);
        for(int dx=-rx;dx<=rx;dx++)for(int dy=-ry;dy<=ry;dy++) {
            if(Math.abs(dx)/(double)COLS>=halfW+.5/COLS-.001||Math.abs(dy)/(double)ROWS>=halfH+.5/ROWS-.001)continue;
            Tile t=tiles.get(key(xx+dx,yy+dy));if(t!=null&&t.solid)return false;
        }
        return true;
    }
    private long nearestFree(double xx,double yy,int radius) {
        int cx=cellX(xx),cy=cellY(yy);long best=Long.MIN_VALUE;double score=Double.POSITIVE_INFINITY;
        for(int dx=-radius;dx<=radius;dx++)for(int dy=-radius;dy<=radius;dy++) {
            long k=key(cx+dx,cy+dy);if(!passable(k))continue;
            double s=dx*dx+dy*dy;if(s<score){score=s;best=k;}
        }
        return best;
    }
    private boolean horizontalSolid(int direction) {
        if(direction==0)return false;
        if(direction>0&&frame.wallRight||direction<0&&frame.wallLeft)return true;
        double width=validCoordinate(frame.playerLeft)&&validCoordinate(frame.playerRight)?Math.max(.015,(frame.playerRight-frame.playerLeft)/2):.018;
        double height=validCoordinate(frame.playerTop)&&validCoordinate(frame.playerBottom)?Math.max(.02,(frame.playerBottom-frame.playerTop)/2):.028;
        double sx=frame.playerX+direction*(width+.012);
        for(double sy=frame.playerY-height*.6;sy<=frame.playerY+height*.6;sy+=.02)if(localSolid(sx,sy))return true;
        return false;
    }
    private boolean roofTooClose() {
        double top=validCoordinate(frame.playerTop)?frame.playerTop:frame.playerY-.028;
        for(double sx=frame.playerX-.02;sx<=frame.playerX+.02;sx+=.02)
            if(localSolid(sx,top-.025)||localSolid(sx,top-.055))return true;
        return false;
    }
    private boolean localSolid(double sx,double sy){return localValue(sx,sy)==2;}
    private boolean localFree(double sx,double sy){return localValue(sx,sy)==1;}
    private int localValue(double sx,double sy) {
        if(!validTerrain(frame)||!valid(sx,sy))return 0;
        int xx=Math.min(frame.terrainCols-1,(int)(sx*frame.terrainCols)),yy=Math.min(frame.terrainRows-1,(int)(sy*frame.terrainRows));
        return frame.terrainCells[yy*frame.terrainCols+xx];
    }
    private boolean jumpNeeded(boolean above,double dy,FarmEngine.Frame f) {
        if(!above||usedJumps>=jumpBudget()||f.ceilingReached||roofTooClose()||now-lastJump<Math.max(450,config.jumpSpacingMs))return false;
        // Do not retrigger while the preceding jump is still making useful ascent.
        if(!f.grounded&&now-lastJump<900&&velocityY<-.09&&dy>-.18)return false;
        return f.grounded||velocityY>-.09||now-lastJump>=700;
    }
    private boolean touching(Track t,FarmEngine.Frame f) {
        double l=validCoordinate(f.playerLeft)?f.playerLeft+cameraX:px-.018,r=validCoordinate(f.playerRight)?f.playerRight+cameraX:px+.018;
        double top=validCoordinate(f.playerTop)?f.playerTop+cameraY:py-.030,bottom=validCoordinate(f.playerBottom)?f.playerBottom+cameraY:py+.030;
        return t.x+t.width/2>=l&&t.x-t.width/2<=r&&t.y+t.height/2>=top&&t.y-t.height/2<=bottom;
    }
    private Track track(int id){if(id<0)return null;for(Track t:tracks)if(t.id==id)return t;return null;}
    private double[] nearestLocalEnemy(FarmEngine.Frame f) {
        double[] best=null;double score=Double.POSITIVE_INFINITY;
        if(f.enemyBoxes==null)return null;
        for(double[]b:f.enemyBoxes)if(b!=null&&b.length>=4&&valid(b[0],b[1])&&valid(b[2],b[3])) {
            double d=distance(f.playerX,f.playerY,(b[0]+b[2])/2,(b[1]+b[3])/2);if(d<score){score=d;best=b;}
        }
        return best;
    }
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
