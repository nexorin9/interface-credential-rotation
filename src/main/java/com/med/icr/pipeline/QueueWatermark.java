package com.med.icr.pipeline;

/** 队列水位：深度 ≥ 80% 容量时置「即将拒收」标志，供界面与 CLI 查询。 */
public final class QueueWatermark {
    public record Snapshot(int depth, long peak, long rejected, boolean nearReject, int capacity) {}

    private final int capacity;
    private long peak;
    private long rejected;
    private int depth;

    public QueueWatermark(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("队列容量必须为正");
        this.capacity = capacity;
    }

    public Snapshot observe(int currentDepth, long currentRejected) {
        this.depth = currentDepth;
        this.rejected = currentRejected;
        this.peak = Math.max(peak, currentDepth);
        return snapshot();
    }

    public Snapshot observe(BackpressureQueue<?> queue) {
        return observe(queue.size(), queue.rejectedCount());
    }

    public boolean nearReject() { return depth * 10 >= capacity * 8; }
    public Snapshot snapshot() {
        return new Snapshot(depth, peak, rejected, nearReject(), capacity);
    }
    public int capacity() { return capacity; }
}
