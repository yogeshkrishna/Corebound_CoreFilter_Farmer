package com.corefilter.farmer.engine;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.corefilter.farmer.engine.FarmEngine.*;

/** Generated worlds and actual feedback loops; no recordings are opened. */
public class CorridorControllerTest {
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
        assertEquals(0,n.next(room(1050,.71,.52,false,false)).jumps);
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
