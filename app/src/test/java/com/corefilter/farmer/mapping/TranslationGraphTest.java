package com.corefilter.farmer.mapping;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class TranslationGraphTest {
    @Test public void preservesReverseAndDropWithoutScaling(){
        TranslationGraph.Pose[] p=TranslationGraph.solve(4,new boolean[]{true,true,true,true},Arrays.asList(
            new TranslationGraph.Edge(0,1,400,0,100),new TranslationGraph.Edge(1,2,0,800,100),new TranslationGraph.Edge(2,3,-300,0,100)));
        assertEquals(100,p[3].x,.001);assertEquals(800,p[3].y,.001);assertEquals(p[0].section,p[3].section);
    }
    @Test public void uncertainSectionsNeverGetInventedRelativePositions(){
        TranslationGraph.Pose[] p=TranslationGraph.solve(3,new boolean[]{true,true,false},Collections.emptyList());
        assertNotEquals(p[0].section,p[1].section);assertEquals(-1,p[2].section);
    }
    @Test public void conflictingLookAlikeClosureDoesNotDistortValidPath(){
        TranslationGraph.Pose[] p=TranslationGraph.solve(3,new boolean[]{true,true,true},Arrays.asList(
            new TranslationGraph.Edge(0,1,400,0,100),new TranslationGraph.Edge(1,2,400,0,100),new TranslationGraph.Edge(0,2,-800,0,20)));
        assertEquals(800,p[2].x,.001);
    }
    @Test public void corroboratingLoopDistributesSmallCameraError(){
        TranslationGraph.Pose[] p=TranslationGraph.solve(3,new boolean[]{true,true,true},Arrays.asList(
            new TranslationGraph.Edge(0,1,400.4,0,100),new TranslationGraph.Edge(1,2,400.4,0,100),new TranslationGraph.Edge(0,2,800,0,150)));
        assertTrue(Math.abs(p[2].x-800)<.4);assertTrue(Math.abs(p[1].x-400)<.5);
    }
}
