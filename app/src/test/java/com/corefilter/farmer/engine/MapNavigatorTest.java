package com.corefilter.farmer.engine;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.corefilter.farmer.engine.FarmEngine.*;

/** Recorded failure shapes: right-wall lock, lost ceiling targets, camera scroll,
 * and seven taps followed by an ineffective eighth before landing. */
public class MapNavigatorTest {
    private Frame room(long now) {
        Frame f=new Frame(now,"com.Overcurve.Corebound","",null);
        f.gameplay=true;f.playerConfidence=.9;f.playerX=.5;f.playerY=.60;
        f.playerLeft=.480;f.playerRight=.520;f.playerTop=.565;f.playerBottom=.635;
        f.cameraConfidence=.9;f.cameraX=f.cameraY=0;f.terrainCols=48;f.terrainRows=24;
        f.terrainCells=new byte[48*24];
        for(int y=4;y<=20;y++)for(int x=1;x<47;x++)f.terrainCells[y*48+x]=1;
        for(int x=1;x<47;x++)f.terrainCells[21*48+x]=2;
        return f;
    }
    private Frame player(Frame f,double x,double y) {
        f.playerX=x;f.playerY=y;f.playerLeft=x-.020;f.playerRight=x+.020;f.playerTop=y-.035;f.playerBottom=y+.035;return f;
    }
    private Frame enemy(Frame f,double x,double y) {f.enemyBoxes=new double[][]{{x-.025,y-.035,x+.025,y+.035,0,0}};return f;}
    private MapNavigator nav(){return new MapNavigator(new Config());}

    @Test public void screenStationaryPlayerStillMakesWorldProgressWhenCameraScrolls() {
        MapNavigator n=nav();n.next(enemy(room(0),.80,.60));
        for(int i=1;i<=5;i++) {
            Frame f=room(i*350);f.cameraX=i*.025;enemy(f,.80-f.cameraX,.60);
            MapNavigator.Decision d=n.next(f);
            assertFalse(d.reason.contains("Blocked passage"));assertEquals(1,d.direction);
        }
        assertEquals(.625,n.snapshot().playerX,.001);
        assertTrue(n.snapshot().mapCells>48*17);
    }

    @Test public void uncertainCameraFreezesWorldTilesTracksAndGateCoordinates() {
        MapNavigator n=nav();n.next(enemy(room(0),.25,.30));MapNavigator.Snapshot before=n.snapshot();
        Frame occluded=player(room(350),.75,.50);occluded.cameraConfidence=.1;occluded.cameraX=.2;
        enemy(occluded,.40,.30);occluded.gate=true;occluded.gateX=.8;
        MapNavigator.Decision local=n.next(occluded);MapNavigator.Snapshot after=n.snapshot();
        assertEquals(before.mapCells,after.mapCells);assertEquals(1,after.unresolvedEnemies);
        assertEquals(before.enemies[0][0],after.enemies[0][0],.001);
        assertEquals(before.cameraX,after.cameraX,.001);assertEquals(1,local.direction);assertFalse(local.pause);
    }

    @Test public void jumpsAreSinglePulsesSeparatedByObservedFramesAndLimitedToSeven() {
        MapNavigator n=nav();int pulses=0;
        for(int i=0;i<12;i++) {
            Frame f=player(room(i*1000),.50,.70-Math.min(i,7)*.05);f.cameraX=i*.012;enemy(f,.60-f.cameraX,.30);
            MapNavigator.Decision d=n.next(f);assertTrue(d.jumps<=1);pulses+=d.jumps;
        }
        assertTrue(pulses>0&&pulses<=7);assertEquals(7-pulses,n.snapshot().remainingJumps);
    }

    @Test public void firstGroundObservationDoesNotRechargeButSecondAfterAirtimeDoes() {
        Config c=new Config();c.jumpBudget=2;MapNavigator n=new MapNavigator(c);
        for(int i=0;i<3;i++){Frame f=player(room(i*500),.5,i==0?.84:i==1?.68:.70);f.grounded=i==0;f.cameraX=i*.02;n.next(enemy(f,.60-f.cameraX,.30));}
        assertEquals(0,n.snapshot().remainingJumps);
        Frame first=room(2000);first.cameraX=.06;first.grounded=true;n.next(enemy(first,.60,.50));
        assertEquals(0,n.snapshot().remainingJumps);
        Frame second=room(2500);second.cameraX=.06;second.grounded=true;n.next(enemy(second,.60,.50));
        assertTrue(n.snapshot().remainingJumps>=1);
    }

    @Test public void ignoredGroundJumpRetriesDoNotConsumeTheAirHookshotBudget() {
        MapNavigator n=nav();int pulses=0;
        for(int i=0;i<18;i++) {
            Frame f=room(i*500);f.grounded=true;f.cameraX=i*.04;
            pulses+=n.next(f).jumps;
        }
        assertTrue("A rejected base jump must be retried",pulses>1);
        assertTrue("Grounded retries leave the six airborne charges available",n.snapshot().remainingJumps>=6);
    }

    @Test public void aSingleRoofContactStopsJumpingImmediatelyAndRetreatsFromCorner() {
        MapNavigator n=nav();n.next(enemy(room(0),.70,.25));
        Frame ceiling=room(500);ceiling.ceilingReached=true;
        MapNavigator.Decision d=n.next(ceiling);assertEquals(0,d.jumps);assertTrue(d.reason.contains("descend"));
        Frame corner=room(850);corner.ceilingReached=true;corner.wallRight=true;
        d=n.next(corner);assertEquals(0,d.jumps);assertEquals(0,d.direction);
    }

    @Test public void animatedHudCannotHideAStationaryPlayerAtAVisibleWall() {
        MapNavigator n=nav();n.next(enemy(room(0),.80,.60));
        Frame f=room(350);f.sceneSignature=.2;n.next(enemy(f,.80,.60));
        f=room(700);f.sceneSignature=.8;
        MapNavigator.Decision escape=n.next(enemy(f,.80,.60));
        assertEquals(0,escape.direction);assertEquals(0,escape.jumps);assertTrue(escape.reason.contains("Failed movement primitive"));
    }

    @Test public void verticalBouncingDoesNotCountAsHorizontalProgressAgainstTheWall() {
        MapNavigator n=nav();n.next(enemy(room(0),.80,.60));
        n.next(enemy(player(room(350),.5,.56),.8,.60));
        MapNavigator.Decision d=n.next(enemy(player(room(700),.5,.59),.8,.60));
        assertEquals(0,d.direction);assertTrue(d.reason.contains("Failed movement primitive"));
    }

    @Test public void aMissedOffscreenEnemySurvivesSeveralSecondsAndIsBacktracked() {
        MapNavigator n=nav();n.next(enemy(room(0),.25,.40));
        Frame f=room(3000);f.cameraX=.45;
        MapNavigator.Decision d=n.next(f);assertEquals(1,n.snapshot().unresolvedEnemies);
        assertEquals(-1,d.direction);assertTrue(d.reason.contains("Named enemy return"));
    }

    @Test public void contactDoesNotMeanKilledAndBurnMustBeVerifiedAtThePosition() {
        MapNavigator n=nav();MapNavigator.Decision pass=n.next(enemy(room(0),.5,.60));
        assertTrue(pass.reason.contains("contact attempted"));assertEquals(1,n.snapshot().unresolvedEnemies);
        n.next(room(1000));assertEquals(1,n.snapshot().unresolvedEnemies);
        n.next(room(1900));n.next(room(2250));n.next(room(2600));
        assertEquals(0,n.snapshot().unresolvedEnemies);
    }

    @Test public void anUncontactedMissingBotIsNotSilentlyDeclaredDead() {
        MapNavigator n=nav();n.next(enemy(room(0),.58,.58));
        n.next(room(1900));n.next(room(2250));n.next(room(2600));
        assertEquals(1,n.snapshot().unresolvedEnemies);
    }

    @Test public void offscreenAbsenceCannotVerifyAnIgnitedEnemyDeath() {
        MapNavigator n=nav();n.next(enemy(room(0),.5,.60));
        for(int i=1;i<=8;i++){Frame f=room(i*500);f.cameraX=.65;n.next(f);}
        assertEquals(1,n.snapshot().unresolvedEnemies);
    }

    @Test public void inaccessibleEnemyBehindFullWallDoesNotStarveTheUnexploredRightFrontier() {
        Frame f=room(0);for(int y=4;y<=21;y++)f.terrainCells[y*48+17]=2;
        MapNavigator n=nav();MapNavigator.Decision d=n.next(enemy(f,.22,.45));
        assertEquals(1,n.snapshot().unresolvedEnemies);assertEquals("enter corridor",n.snapshot().goal);
        assertTrue("Must explore the free side, not bang into the separating wall",d.direction>=0);
    }

    @Test public void visibleSolidWallIsAvoidedWithoutWaitingForTheOldTwelveSecondTimer() {
        Frame f=room(0);for(int y=4;y<=21;y++)f.terrainCells[y*48+25]=2;
        f.wallRight=true;MapNavigator.Decision d=nav().next(enemy(f,.8,.50));
        assertNotEquals(1,d.direction);assertEquals(0,d.jumps);
    }

    @Test public void checkedCeilingSectionsPersistAfterScrollingAwayAndReturning() {
        MapNavigator n=nav();Frame f=player(room(0),.5,.35);
        for(int x=1;x<47;x++)f.terrainCells[5*48+x]=2;
        n.next(f);assertEquals(0,n.snapshot().inspectedCeilings);
        f=player(room(350),.5,.35);for(int x=1;x<47;x++)f.terrainCells[5*48+x]=2;n.next(f);
        int first=n.snapshot().inspectedCeilings;assertTrue(first>=2);
        f=player(room(700),.5,.35);f.cameraX=.25;
        for(int x=1;x<47;x++)f.terrainCells[5*48+x]=2;
        n.next(f);f=player(room(1050),.5,.35);f.cameraX=.25;for(int x=1;x<47;x++)f.terrainCells[5*48+x]=2;n.next(f);
        int more=n.snapshot().inspectedCeilings;assertTrue(more>first);
        f=player(room(1400),.5,.35);for(int x=1;x<47;x++)f.terrainCells[5*48+x]=2;
        n.next(f);assertTrue(n.snapshot().inspectedCeilings>=more);
    }

    @Test public void observationDoesNotConsumeHypotheticalJumpsOrInventWallRecovery() {
        MapNavigator n=nav();
        for(int i=0;i<12;i++)n.observe(enemy(room(i*500),.70,.25));
        assertEquals(7,n.snapshot().remainingJumps);assertEquals(0,n.snapshot().blockedForMs);
    }

    @Test public void cameraReanchorWithMatchingTerrainPreservesTheRoomAtlas() {
        MapNavigator n=nav();n.next(enemy(room(0),.25,.30));int cells=n.snapshot().mapCells;
        Frame f=room(350);f.registrationEpoch=1;f.registrationReset=f.sceneChanged=true;f.cameraConfidence=.1;n.next(f);
        f=room(700);f.registrationEpoch=1;n.next(f);
        assertEquals(0,n.snapshot().room);assertTrue(n.snapshot().mapCells>=cells);assertEquals(1,n.snapshot().unresolvedEnemies);
    }

    @Test public void terrainDiscontinuityPreservesUnlinkedAtlasAndTargetsWithoutRefillingAirborneJumps() {
        MapNavigator n=nav();n.next(enemy(room(0),.7,.25));int remaining=n.snapshot().remainingJumps;
        Frame f=room(350);f.registrationEpoch=1;f.registrationReset=f.sceneChanged=true;f.cameraConfidence=.1;n.next(f);
        f=room(700);f.registrationEpoch=1;
        for(int y=4;y<=20;y++)for(int x=1;x<47;x++)f.terrainCells[y*48+x]=2;
        n.next(f);assertEquals(1,n.snapshot().room);assertEquals(1,n.snapshot().unresolvedEnemies);
        assertTrue(n.snapshot().mapCells>48*17);
        assertEquals(remaining,n.snapshot().remainingJumps);
    }

    @Test public void observedRiseAndCrestAdaptReachToTheActualBuild() {
        MapNavigator n=nav();n.next(enemy(room(0),.6,.28));
        n.next(enemy(player(room(350),.52,.49),.6,.28));
        n.next(enemy(player(room(700),.55,.53),.6,.28));
        assertEquals(.11,n.snapshot().learnedJumpRise,.025);
    }

    @Test public void velocityUsesCaptureTimeEvenWhenProcessingDelayChanges() {
        MapNavigator n=nav();Frame f=enemy(room(0),.7,.29);f.now=900;n.next(f);
        f=enemy(player(room(350),.52,.50),.7,.29);f.now=1600;n.next(f);
        assertEquals(-.10/.35,n.snapshot().verticalVelocity,.02);
    }

    @Test public void wideHullCannotCertifyANarrowFourCellPassage() {
        Frame f=room(0);f.playerLeft=.45;f.playerRight=.55;f.playerTop=.535;f.playerBottom=.665;
        for(int y=4;y<=20;y++){f.terrainCells[y*48+29]=2;f.terrainCells[y*48+34]=2;}
        MapNavigator n=nav();MapNavigator.Decision d=n.next(enemy(f,32.5/48.,.4));
        assertEquals(1,n.snapshot().unresolvedEnemies);assertNotEquals("enemy",n.snapshot().goal);
        assertEquals(0,d.jumps);
    }

    @Test public void goldBoundsOverlappingAWallNeverEraseTheMappedSolidCells() {
        Frame f=player(room(0),.48,.60);f.playerRight=.53;
        for(int y=4;y<=21;y++)f.terrainCells[y*48+24]=2;
        MapNavigator n=nav();n.next(f);int wallCells=0;
        for(double[] c:n.snapshot().cells)if(Math.abs(c[0]-24.5/48.)<.001&&c[2]==2)wallCells++;
        assertEquals(18,wallCells);
    }

    @Test public void explicitSectorCompletionResolvesOrdinaryBotsWhileKeepingCeilingCandidates() {
        MapNavigator ordinary=nav();ordinary.next(enemy(room(0),.62,.60));
        Frame done=room(350);done.completedSector=1;ordinary.next(done);
        assertEquals(0,ordinary.snapshot().unresolvedEnemies);assertTrue(ordinary.sectorCleared());
        MapNavigator ceiling=nav();ceiling.next(enemy(room(0),.25,.27));
        done=room(350);done.completedSector=1;ceiling.next(done);
        assertEquals(1,ceiling.snapshot().unresolvedEnemies);
    }

    @Test public void registrationFailureDoesNotDisableFreshLocalGroundMovement() {
        MapNavigator n=nav();n.next(room(0));
        Frame f=room(350);f.cameraConfidence=.1;f.grounded=true;
        for(int x=1;x<47;x++)f.terrainCells[16*48+x]=2;
        MapNavigator.Decision probe=n.next(f);assertEquals(1,probe.direction);assertFalse(probe.pause);
        int cells=n.snapshot().mapCells;f=room(9000);f.cameraConfidence=.1;f.grounded=true;
        for(int x=1;x<47;x++)f.terrainCells[16*48+x]=2;
        MapNavigator.Decision keepMoving=n.next(f);assertFalse(keepMoving.pause);assertEquals(1,keepMoving.direction);
        assertEquals(1,keepMoving.jumps);
        assertEquals(cells,n.snapshot().mapCells);
    }

    @Test public void controlMaskIsNotAnUnexploredRoomFrontierThatPullsTheFarmerLeft() {
        Frame f=player(room(0),.50,.73);
        for(int y=16;y<=20;y++)for(int x=1;x<20;x++)f.terrainCells[y*48+x]=0;
        MapNavigator n=nav();MapNavigator.Decision d=n.next(f);
        assertTrue("Advance toward the unvisited right room boundary",d.direction>0);
        assertEquals("enter corridor",n.snapshot().goal);assertTrue(n.snapshot().goalX>.5);
    }
}
