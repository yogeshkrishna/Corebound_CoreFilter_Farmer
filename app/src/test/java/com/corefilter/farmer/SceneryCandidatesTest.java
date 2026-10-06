package com.corefilter.farmer;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

/** The faster shortlist must select exactly the same registration anchors. */
public class SceneryCandidatesTest {
    @Test public void equalScoresKeepTheEarliestOriginalFrames(){
        OfflineReconstructor.CandidatePool pool=new OfflineReconstructor.CandidatePool(10);
        // Offer in reverse order as an extra check that ties depend on frame
        // identity, rather than on priority-queue insertion order.
        for(int i=999;i>=0;i--)pool.offer(i,.5);
        assertArrayEquals(new int[]{0,1,2,3,4,5,6,7,8,9},pool.indices());
    }

    @Test public void shortlistMatchesStableFullSortForMixedAndExceptionalScores(){
        Random random=new Random(4247);
        for(int count:new int[]{1,7,10,11,1000}){
            double[] scores=new double[count];List<Integer> reference=new ArrayList<>();OfflineReconstructor.CandidatePool pool=new OfflineReconstructor.CandidatePool(10);
            for(int i=0;i<count;i++){scores[i]=i%19==0?Double.NaN:i%17==0?Double.POSITIVE_INFINITY:i%13==0?-0.0:random.nextInt(20)/20.0;reference.add(i);pool.offer(i,scores[i]);}
            reference.sort((a,b)->Double.compare(scores[b],scores[a]));
            int[] expected=new int[Math.min(10,count)];for(int i=0;i<expected.length;i++)expected[i]=reference.get(i);
            assertArrayEquals("Same anchor identities and tie order for "+count+" candidates",expected,pool.indices());
        }
    }
}
