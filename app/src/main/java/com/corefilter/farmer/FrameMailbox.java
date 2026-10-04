package com.corefilter.farmer;

/** One latest observation waits while Android finishes a touch. Main-thread owned. */
final class FrameMailbox<T> {
    private T value;
    private long capturedAt;
    private int generation;

    void offer(T next, long timestamp, int ticket) {
        if (value != null && ticket == generation && timestamp <= capturedAt) return;
        value = next; capturedAt = timestamp; generation = ticket;
    }

    T take(long now, int ticket, long maxAge) {
        return take(now,ticket,maxAge,0);
    }

    T take(long now, int ticket, long maxAge, long earliestCapture) {
        T latest = value;
        boolean fresh = latest != null && ticket == generation && capturedAt >= earliestCapture && capturedAt <= now && now-capturedAt <= maxAge;
        clear();
        return fresh ? latest : null;
    }

    void clear() { value = null; }
}
