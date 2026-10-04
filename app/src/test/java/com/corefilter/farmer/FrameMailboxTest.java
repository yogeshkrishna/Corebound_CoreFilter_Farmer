package com.corefilter.farmer;

import org.junit.Test;
import static org.junit.Assert.*;

public class FrameMailboxTest {
    @Test public void onlyNewestFrameDrivesTheNextTouch() {
        FrameMailbox<String> frames=new FrameMailbox<>();
        frames.offer("before wall",100,4);frames.offer("at wall",450,4);frames.offer("late OCR",200,4);
        assertEquals("at wall",frames.take(520,4,1000));assertNull(frames.take(521,4,1000));
    }
    @Test public void pausedSessionAndStaleScreensCannotResumeMovement() {
        FrameMailbox<String> frames=new FrameMailbox<>();frames.offer("old run",100,4);
        assertNull(frames.take(200,5,1000));frames.offer("stale",200,5);
        assertNull(frames.take(1300,5,1000));frames.offer("new",1400,5);frames.clear();
        assertNull(frames.take(1500,5,1000));
    }
    @Test public void rejectsFutureClockAndAcceptsNewSession() {
        FrameMailbox<String> frames=new FrameMailbox<>();frames.offer("future",900,4);
        assertNull(frames.take(800,4,1000));frames.offer("new run",1200,5);
        assertEquals("new run",frames.take(1300,5,1000));
    }
    @Test public void delayedOcrFromBeforeJumpCannotDriveAnotherImpulse(){
        FrameMailbox<String> frames=new FrameMailbox<>();frames.offer("before pulse",500,4);
        assertNull(frames.take(950,4,1000,745));
        frames.offer("physical response",800,4);
        assertEquals("physical response",frames.take(980,4,1000,745));
    }
}
