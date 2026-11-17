package com.med.icr.pipeline;

import java.util.concurrent.atomic.AtomicLong;

/** 进出计数：入队增、出队减，当前在途数 = 入队数 - 出队数恒成立。 */
public final class EventCounter {
    private final AtomicLong enqueued = new AtomicLong();
    private final AtomicLong dequeued = new AtomicLong();
    private long peak;

    public synchronized void onOffer() {
        enqueued.incrementAndGet();
        peak = Math.max(peak, inFlight());
    }

    public synchronized void onPoll(boolean success) {
        if (success) dequeued.incrementAndGet();
    }

    public synchronized long inFlight() { return enqueued.get() - dequeued.get(); }
    public long enqueuedCount() { return enqueued.get(); }
    public long dequeuedCount() { return dequeued.get(); }
    public synchronized long peak() { return peak; }
}
