package com.corefilter.farmer.engine;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.corefilter.farmer.engine.FarmEngine.*;

/** Generated worlds and actual feedback loops; no recordings are opened. */
public class CorridorControllerTest {
    @Test public void missedEnemyReturnSurvivesRoofContactAndCameraLoss(){
        for(boolean registered:new boolean[]{true,false}){
            MapNavigator n=new MapNavigator(new Config());
            for(int i=0;i<4;i++)n.next(room(i*350,.45+i*.09,.715,true,true));
            Frame exit=room(1400,.85,.715,true,false);exit.gate=true;exit.gateX=.56;
            exit.remainingEnemies=1;exit.remainingEnemiesConfidence=.95;
            if(!registered)exit.cameraConfidence=0;
            assertEquals(-1,n.next(exit).direction);assertEquals("REMAINING_ENEMY_SWEEP",n.phase());
            Frame roof=room(1750,.79,.52,false,true);roof.ceilingReached=true;roof.remainingEnemies=1;roof.remainingEnemiesConfidence=.95;
            roof.cameraConfidence=0;
            assertEquals(-1,n.next(roof).direction);assertEquals("REMAINING_ENEMY_SWEEP",n.phase());
        }
    }
    @Test public void aScoutCannotRefillItsJumpsAndClimbForeverOnOneLedge(){
        MapNavigator n=new MapNavigator(new Config());n.next(room(0,.45,.715,true,false));n.next(room(350,.54,.6,false,false));n.next(room(700,.65,.56,false,false));
        assertEquals("SCOUT_HIGH_CEILING",n.phase());
        Frame ledge=room(8400,.68,.5,true,false);
        n.next(ledge);assertNotEquals("SCOUT_HIGH_CEILING",n.phase());assertFalse(n.snapshot().complete);
    }
    @Test public void cameraGapsRetainLocalGeometryWithoutInventingWorldOffsets(){
        MapNavigator n=new MapNavigator(new Config());n.next(room(0,.45,.715,true,true));
        Frame f=room(700,.45,.50,false,true);f.cameraConfidence=0;n.next(f);
        MapNavigator.Snapshot saved=n.snapshot();assertEquals(2,saved.screenTerrain.length);
        assertTrue(Double.isNaN(saved.screenPoses[1][3]));assertEquals(1,saved.screenPoses[1][11],0);
        byte original=saved.screenTerrain[1][6*48+24];f.terrainCells[6*48+24]=0;
        assertEquals(original,saved.screenTerrain[1][6*48+24]);
    }
    @Test public void unknownBackgroundCannotSuppressTheHiddenRoofScout(){
        MapNavigator n=new MapNavigator(new Config());
        for(int i=0;i<3;i++){
            Frame f=room(i*350,.45+i*.10,i==0?.715:i==1?.60:.62,i==0,false);
            for(int y=4;y<13;y++)for(int x=1;x<47;x++)f.terrainCells[y*48+x]=0;
            MapNavigator.Decision d=n.next(f);
            if(i==2){assertEquals(d.reason,1,d.jumps);assertEquals("SCOUT_HIGH_CEILING",n.snapshot().phase);}
        }
    }
    @Test public void sectorBannerCanInvertDirectionTwiceWithoutARegisteredFloor(){
        MapNavigator n=new MapNavigator(new Config());
        for(int i=0;i<4;i++){Frame f=room(i*350,.45,.715,true,true);f.cameraConfidence=0;n.next(f);}
        Frame lower=room(1400,.45,.715,true,true);lower.cameraConfidence=0;lower.completedSector=1;lower.wallRight=true;
        assertEquals(-1,n.next(lower).direction);assertEquals(-1,n.snapshot().corridorDirection);
        lower=room(1750,.45,.715,true,true);lower.cameraConfidence=0;lower.completedSector=1;lower.wallRight=true;
        assertEquals(-1,n.next(lower).direction);assertEquals("Do not invert twice on the same banner",-1,n.snapshot().corridorDirection);
        lower=room(2800,.45,.715,true,true);lower.cameraConfidence=0;lower.completedSector=2;lower.wallLeft=true;
        assertEquals(1,n.next(lower).direction);assertEquals(1,n.snapshot().corridorDirection);
    }
    @Test public void visibleLowerPassageCanTurnBeforeLandingWithoutASectorBanner(){
        MapNavigator n=new MapNavigator(new Config());n.next(room(0,.45,.715,true,true));
        Frame lower=room(1400,.70,.45,false,true);lower.cameraY=.50;lower.wallRight=true;
        MapNavigator.Decision d=n.next(lower);assertFalse(d.reason,d.pause);assertEquals(-1,d.direction);
    }
    @Test public void aChosenLowerDirectionSurvivesTheFallAndLandingWithoutAnotherInversion(){
        MapNavigator n=new MapNavigator(new Config());n.next(room(0,.45,.715,true,true));
        Frame corner=room(1400,.70,.715,true,true);corner.completedSector=1;corner.wallRight=true;
        assertEquals(-1,n.next(corner).direction);
        Frame falling=room(1750,.65,.50,false,false);falling.cameraY=.60;
        MapNavigator.Decision d=n.next(falling);assertEquals(-1,d.direction);assertEquals(0,d.jumps);
        Frame landed=room(2100,.60,.715,true,true);landed.cameraY=.65;
        d=n.next(landed);assertEquals(-1,d.direction);assertEquals(-1,n.snapshot().corridorDirection);
    }
    @Test public void standingOnWeakSupportKeepsDrivingWhenMapCannotRegister(){
        MapNavigator n=new MapNavigator(new Config());int pulses=0;
        for(int i=0;i<14;i++){
            Frame f=room(i*350,.50,.645,false,true);f.cameraConfidence=0;f.cameraX=f.cameraY=0;f.groundContactCandidate=true;
            MapNavigator.Decision d=n.next(f);assertFalse(d.reason,d.pause);assertEquals(d.reason,1,d.direction);pulses+=d.jumps;
        }
        assertTrue("Keep retrying base jumps on observed support instead of waiting for registration",pulses>=3);
        assertEquals("Uncertain camera observations cannot manufacture a world path",1,n.snapshot().path.length);
    }
    @Test public void ignoredIntroJumpDoesNotLockOutAllSubsequentJumps(){
        MapNavigator n=new MapNavigator(new Config());int pulses=0;
        for(int i=0;i<12;i++)pulses+=n.next(room(i*350,.45+i*.04,.715,true,true)).jumps;
        assertTrue("No observed airtime means retry rather than permanently spend the first charge",pulses>=3);
    }
    @Test public void staleCachedEnemyCountDoesNotStartARevisit(){
        MapNavigator n=new MapNavigator(new Config());n.next(room(0,.45,.715,true,true));
        Frame f=room(4000,.75,.715,true,true);f.gate=true;f.gateX=.56;f.remainingEnemies=2;f.remainingEnemiesConfidence=.95;f.remainingEnemiesCapturedAt=500;
        n.next(f);assertNotEquals("REMAINING_ENEMY_SWEEP",n.snapshot().phase);
    }
    private Frame room(long at,double worldX,double y,boolean grounded,boolean roof){
        Frame f=new Frame(at,"com.Overcurve.Corebound","",null);f.gameplay=true;
        f.playerConfidence=.95;f.cameraConfidence=.95;f.cameraX=worldX-.45;f.cameraY=0;
        f.playerX=.45;f.playerY=y;f.playerLeft=.43;f.playerRight=.47;f.playerTop=y-.035;f.playerBottom=y+.035;
        f.grounded=grounded;f.viewportAspectRatio=2.17;f.terrainCols=48;f.terrainRows=24;f.terrainCells=new byte[48*24];
        for(int yy=4;yy<18;yy++)for(int xx=1;xx<47;xx++)f.terrainCells[yy*48+xx]=1;
        for(int xx=1;xx<47;xx++){f.terrainCells[18*48+xx]=2;if(roof)f.terrainCells[6*48+xx]=2;}
        return f;
    }
    @Test public void rearEnemyCannotOverrideEntryAndLeftImageBorder(){
        MapNavigator n=new MapNavigator(new Config());Frame f=room(0,.45,.715,true,true);
        f.wallLeft=true;f.enemyBoxes=new double[][]{{.17,.66,.23,.72,0,0}};
        MapNavigator.Decision d=n.next(f);assertEquals(1,d.direction);assertEquals(1,d.jumps);
        assertEquals("ENTER",n.snapshot().phase);assertEquals(1,n.snapshot().unresolvedEnemies);
    }
    @Test public void visibleRoofNeedsNoExtraAirJump(){
        MapNavigator n=new MapNavigator(new Config());assertEquals(1,n.next(room(0,.45,.715,true,true)).jumps);
        assertEquals(0,n.next(room(350,.53,.60,false,true)).jumps);
        assertEquals(0,n.next(room(700,.62,.62,false,true)).jumps);
        assertNotEquals("SCOUT_HIGH_CEILING",n.snapshot().phase);
        assertTrue(n.snapshot().inspectedCeilings>10);
    }
    @Test public void hiddenHighRoofWaitsForNormalAscentThenUsesAnExtraImpulse(){
        MapNavigator n=new MapNavigator(new Config());assertEquals(1,n.next(room(0,.45,.715,true,false)).jumps);
        assertEquals(0,n.next(room(350,.53,.60,false,false)).jumps);
        assertEquals(1,n.next(room(700,.64,.62,false,false)).jumps);
        assertEquals("SCOUT_HIGH_CEILING",n.snapshot().phase);
        assertEquals("Chain before crest instead of losing the previous rise",1,n.next(room(1050,.71,.52,false,false)).jumps);
    }
    @Test public void highRoofScoutChainsUpwardBesideWallWithAndWithoutCameraRegistration(){
        for(boolean registered:new boolean[]{true,false}){
            MapNavigator n=new MapNavigator(new Config());
            for(int i=0;i<3;i++){Frame f=room(i*350,.45+i*.09,i==0?.715:.60-i*.02,i==0,false);if(!registered)f.cameraConfidence=0;n.next(f);}
            Frame f=room(1050,.73,.48,false,false);f.wallRight=true;if(!registered)f.cameraConfidence=0;
            MapNavigator.Decision d=n.next(f);assertEquals(d.reason,0,d.direction);assertEquals(d.reason,1,d.jumps);
            assertEquals("SCOUT_HIGH_CEILING",n.snapshot().phase);
        }
    }
    @Test public void cameraOriginRecoveryDoesNotEraseActiveHighRoofScout(){
        MapNavigator n=new MapNavigator(new Config());n.next(room(0,.45,.715,true,false));n.next(room(350,.54,.60,false,false));n.next(room(700,.65,.56,false,false));
        Frame f=room(1050,.70,.45,false,false);f.registrationEpoch=1;f.sceneChanged=true;n.next(f);
        f=room(3150,.70,.40,false,false);f.registrationEpoch=1;n.next(f);
        assertEquals("SCOUT_HIGH_CEILING",n.snapshot().phase);
    }
    @Test public void supportedScoutReturnCannotPushAnInterveningWallForever(){
        MapNavigator n=new MapNavigator(new Config());n.next(room(0,.45,.715,true,false));n.next(room(350,.54,.60,false,false));n.next(room(700,.65,.56,false,false));
        n.next(room(1050,1.20,.45,false,true));n.next(room(1400,1.25,.48,false,true));
        assertEquals("RETURN_GROUND",n.snapshot().phase);
        Frame landing=room(2100,1.25,.715,true,true);landing.wallLeft=true;
        MapNavigator.Decision d=n.next(landing);assertFalse(d.reason,d.pause);assertNotEquals("RETURN_GROUND",n.snapshot().phase);assertEquals(1,d.direction);
    }
    @Test public void proximityCannotInspectAnOccludedCeiling(){
        MapNavigator n=new MapNavigator(new Config());n.observe(room(0,.45,.35,false,true));
        for(int i=1;i<=3;i++){Frame f=room(i*350,.45,.35,false,true);for(int y=7;y<=9;y++)f.terrainCells[y*48+24]=0;n.observe(f);}
        boolean found=false;for(double[] c:n.snapshot().coverage)if(Math.abs((c[0]+c[2])/2-24.5/48.)<.002){found=true;assertEquals(0,c[4],0);}
        assertTrue(found);
    }
    @Test public void forwardWallDoesNotPreventNamedEnemyReturn(){
        MapNavigator n=new MapNavigator(new Config());Frame f=room(0,.45,.715,true,true);
        f.enemyBoxes=new double[][]{{.27,.65,.31,.72,0,0}};n.next(f);
        f=room(700,.66,.715,true,true);f.wallRight=true;
        MapNavigator.Decision d=n.next(f);assertEquals(-1,d.direction);assertTrue(d.reason.contains("Named enemy return"));
    }
    @Test public void zeroOrdinaryCounterDoesNotClearHangingCandidate(){
        MapNavigator n=new MapNavigator(new Config());Frame f=room(0,.45,.715,true,true);
        f.enemyBoxes=new double[][]{{.60,.31,.64,.38,0,0}};n.next(f);
        f=room(700,.65,.715,true,true);f.remainingEnemies=0;f.remainingEnemiesConfidence=.95;n.next(f);
        assertEquals(1,n.snapshot().unresolvedEnemies);
    }
    @Test public void positiveCounterAtExitStartsReturnSearchEvenWithoutDetectedTarget(){
        MapNavigator n=new MapNavigator(new Config());n.next(room(0,.45,.715,true,true));
        Frame f=room(700,.75,.715,true,true);f.gate=true;f.gateX=.56;f.remainingEnemies=2;f.remainingEnemiesConfidence=.95;
        assertEquals(-1,n.next(f).direction);assertEquals("REMAINING_ENEMY_SWEEP",n.snapshot().phase);
    }
    @Test public void zeroCounterAdvancesRatherThanWaitingForGateAnimation(){
        MapNavigator n=new MapNavigator(new Config());n.next(room(0,.45,.715,true,true));
        Frame f=room(700,.75,.715,true,true);f.gate=true;f.gateX=.56;f.remainingEnemies=0;f.remainingEnemiesConfidence=.95;
        MapNavigator.Decision d=n.next(f);assertEquals(1,d.direction);assertFalse(d.pause);
    }
    @Test public void returnSearchContinuesPastTenSecondsWhileMovingTowardCorridorStart(){
        MapNavigator n=new MapNavigator(new Config());n.observe(room(0,.45,.715,true,true));
        n.observe(room(700,8.45,.715,true,true));
        Frame f=room(1000,8.45,.715,true,true);f.gate=true;f.gateX=.56;f.remainingEnemies=2;f.remainingEnemiesConfidence=.95;
        assertEquals(-1,n.next(f).direction);
        for(int i=1;i<=45;i++){
            f=room(1000+i*350,8.45-i*.075,.715,true,true);f.remainingEnemies=2;f.remainingEnemiesConfidence=.95;
            MapNavigator.Decision d=n.next(f);assertFalse(d.reason,d.pause);assertEquals(d.reason,-1,d.direction);
        }
        assertEquals("REMAINING_ENEMY_SWEEP",n.snapshot().phase);
    }
    @Test public void stalledReturnDoesNotClaimToHaveCompletedTheSweep(){
        MapNavigator n=new MapNavigator(new Config());n.observe(room(0,.45,.715,true,true));n.observe(room(700,2,.715,true,true));
        Frame f=room(1000,2,.715,true,true);f.gate=true;f.gateX=.56;f.remainingEnemies=1;f.remainingEnemiesConfidence=.95;n.next(f);
        boolean paused=false;
        for(int i=1;i<=12;i++){
            f=room(1000+i*350,2,.715,true,true);f.remainingEnemies=1;f.remainingEnemiesConfidence=.95;
            MapNavigator.Decision d=n.next(f);if(d.pause){assertEquals(0,d.direction);paused=true;break;}
        }
        assertTrue(paused);assertEquals("REMAINING_ENEMY_SWEEP",n.snapshot().phase);
    }
    @Test public void bodyAtRoofNeverReceivesAnotherUpwardInput(){
        MapNavigator n=new MapNavigator(new Config());n.next(room(0,.45,.715,true,false));
        Frame f=room(350,.54,.3,false,true);f.ceilingReached=true;
        assertEquals(0,n.next(f).jumps);
    }
    @Test public void failedPrimitiveLatchesRatherThanAlternatingWallPushes(){
        MapNavigator n=new MapNavigator(new Config());int reversals=0;boolean stopped=false;
        for(int i=0;i<30;i++){Frame f=room(i*350,.45,.715,true,true);f.wallRight=true;MapNavigator.Decision d=n.next(f);if(d.direction<0)reversals++;if(d.pause){stopped=true;break;}}
        assertEquals(0,reversals);assertTrue(stopped);
    }
    @Test public void completedMapKeepsBordersAndEnemyHistoryWithoutAssumingCoverage(){
        MapNavigator n=new MapNavigator(new Config());Frame f=room(0,.45,.715,true,true);f.enemyBoxes=new double[][]{{.43,.68,.47,.72,0,0}};n.next(f);
        n.finish(true);MapNavigator.Snapshot s=n.snapshot();assertTrue(s.runEnded&&s.runSucceeded);assertTrue(s.borders.length>0);assertEquals(1,s.enemyHistory.length);assertFalse(s.complete);
        n.reset();assertEquals(0,n.snapshot().cells.length);assertTrue(s.cells.length>0);
    }
    @Test public void captureDelayDoesNotChangeMeasuredVerticalVelocity(){
        MapNavigator n=new MapNavigator(new Config());Frame f=room(0,.45,.715,true,true);f.now=150;n.next(f);
        f=room(350,.53,.60,false,true);f.now=1000;n.next(f);
        assertEquals(-.115/.35,n.snapshot().verticalVelocity,.015);
    }
    @Test public void closedLoopMovesThroughVisibleRoofCorridorWithoutAirHookshotsOrRoofCollision(){
        MapNavigator n=new MapNavigator(new Config());double x=.45,y=.715,vy=0;int jumps=0,roofHits=0;boolean grounded=true;
        for(int step=0;step<45&&x<2.5;step++){
            Frame f=room(step*350,x,y,grounded,true);MapNavigator.Decision d=n.next(f);assertFalse(d.reason,d.pause);
            if(d.jumps>0){assertTrue("Visible roof permits base jumps only",grounded);vy=-.72;grounded=false;jumps++;}
            double dt=d.durationMs/1000.;for(int tick=0;tick<35;tick++){double tiny=dt/35;x+=d.direction*.28*tiny;vy+=1.8*tiny;y+=vy*tiny;
                if(y-.035<7./24.){roofHits++;y=7./24.+.035;vy=0;}
                if(y+.035>=.75){y=.715;vy=0;grounded=true;}
            }
        }
        assertTrue("Must reach the corridor exit",x>=2.5);assertTrue(jumps>=3);assertEquals(0,roofHits);
        assertTrue(n.snapshot().inspectedCeilings>30);
    }
    @Test public void closedLoopFollowsTwoDropsAndReversesTwiceThroughAShapedCavern(){
        // Simplified deterministic rectangle/ballistic world, not Corebound's engine.
        double[][] solids={{0,.20,2.30,.30},{0,.30,.10,.95},{0,.75,1.90,.95},
                {2.20,.30,2.35,3.20},{.10,1.40,1.85,1.50},{.38,1.85,2.20,2.05},
                {0,1.40,.10,3.20},{.45,2.50,2.20,2.60},{.10,2.95,2.20,3.20}};
        MapNavigator n=new MapNavigator(new Config());double x=.45,y=.715,vy=0;boolean ground=true;int direction=1,turns=0;
        for(int step=0;step<180;step++){
            Frame f=new Frame(step*350,"com.Overcurve.Corebound","",null);f.gameplay=true;f.playerConfidence=f.cameraConfidence=.95;
            f.cameraX=x-.45;f.cameraY=y-.55;f.playerX=.45;f.playerY=.55;f.playerLeft=.43;f.playerRight=.47;f.playerTop=.515;f.playerBottom=.585;
            f.grounded=ground;f.terrainCols=48;f.terrainRows=24;f.terrainCells=new byte[48*24];f.viewportAspectRatio=2.17;
            for(int yy=4;yy<21;yy++)for(int xx=1;xx<47;xx++){
                double wx=(xx+.5)/48.+f.cameraX,wy=(yy+.5)/24.+f.cameraY;boolean solid=false;
                for(double[] r:solids)if(wx>=r[0]&&wx<r[2]&&wy>=r[1]&&wy<r[3])solid=true;
                f.terrainCells[yy*48+xx]=(byte)(solid?2:1);
            }
            for(double[] r:solids)if(y+.03>r[1]&&y-.03<r[3]){if(Math.abs(x+.02-r[0])<.006)f.wallRight=true;if(Math.abs(x-.02-r[2])<.006)f.wallLeft=true;}
            MapNavigator.Decision d=n.next(f);assertFalse("step "+step+" "+d.reason,d.pause);
            int chosen=n.snapshot().corridorDirection;if(chosen!=direction){turns++;direction=chosen;}
            if(d.jumps>0){vy=-.72;ground=false;}
            double dt=Math.max(.10,d.durationMs/1000.)/35.;
            for(int tick=0;tick<35;tick++){
                double nx=x+d.direction*.28*dt;for(double[] r:solids)if(nx+.02>r[0]&&nx-.02<r[2]&&y+.034>r[1]&&y-.034<r[3])nx=d.direction>0?r[0]-.02:r[2]+.02;x=nx;
                vy+=1.8*dt;double ny=y+vy*dt;ground=false;
                for(double[] r:solids)if(x+.019>r[0]&&x-.019<r[2]&&ny+.035>r[1]&&ny-.035<r[3]){if(vy>0){ny=r[1]-.035;ground=true;}else ny=r[3]+.035;vy=0;}
                y=ny;
            }
            if(turns==2&&y>2.65&&x>1.30){assertEquals(1,direction);assertTrue(n.snapshot().borders.length>8);return;}
        }
        fail("Did not complete both lower corridors; x="+x+" y="+y+" turns="+turns+" phase="+n.snapshot().phase);
    }
}
