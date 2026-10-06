package com.corefilter.farmer;

import org.junit.Test;
import static org.junit.Assert.*;

public class PreviewImagesTest {
    @Test public void nativeImageDimensionsCannotExceedDisplayBudget(){
        for(int[] size:new int[][]{{2048,2048},{12000,1000},{1600,737},{1,400000},{80000,80000}}){
            long budget=4*1024*1024;int sample=PreviewImages.sample(size[0],size[1],budget);
            long pixels=((size[0]+(long)sample-1)/sample)*((size[1]+(long)sample-1)/sample);
            assertTrue(pixels*4<=budget);assertEquals(0,sample&(sample-1));
        }
    }
    @Test public void alreadySmallPreviewKeepsItsPixels(){assertEquals(1,PreviewImages.sample(1024,768,4*1024*1024));}
    @Test public void largeLegacyPreviewUsesSafeDecodeSample(){assertEquals(2,PreviewImages.sample(2048,2048,4*1024*1024));}
}
