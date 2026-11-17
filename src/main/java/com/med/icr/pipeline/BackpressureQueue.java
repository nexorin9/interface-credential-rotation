package com.med.icr.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 背压双语义队列：
 *  - offer(e) 满则返回 false 且**拒收计数不增加**（对齐管道侧的 offer 语义）；
 *  - offerOrReject(e) 是入站管线使用的显式拒收通道，满时拒收并计数，不静默丢；
 *  - put(e) 阻塞直到有位，成功入队后才计数。
 * 逐条入队失败不影响批次其余事件。
 */
public final class BackpressureQueue<T> {
    private final ArrayBlockingQueue<T> queue;
    private final AtomicLong enqueued = new AtomicLong();
    private final AtomicLong dequeued = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final int capacity;

    public BackpressureQueue(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("队列容量必须为正");
        this.capacity = capacity;
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    public boolean offer(T e) {
        boolean ok = queue.offer(e);
        if (ok) enqueued.incrementAndGet();
        return ok;
    }

    /** 显式拒收语义：队列满时拒绝并计数，供界面「即将拒收」与审计核对。 */
    public boolean offerOrReject(T e) {
        boolean ok = offer(e);
        if (!ok) rejected.incrementAndGet();
        return ok;
    }

    public void put(T e) throws InterruptedException {
        queue.put(e);
        enqueued.incrementAndGet();
    }

    public boolean put(T e, long timeout, TimeUnit unit) {
        try {
            boolean ok = queue.offer(e, timeout, unit);
            if (!ok) throw new QueueFullException("阻塞投递超时，队列已满");
            enqueued.incrementAndGet();
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new QueueFullException("阻塞投递被中断，队列已满");
        }
    }

    /** 单条解析失败等场景显式登记拒收，不影响批次其余事件。 */
    public void recordRejected(String reason) { rejected.incrementAndGet(); }

    public T poll() {
        T e = queue.poll();
        if (e != null) dequeued.incrementAndGet();
        return e;
    }

    public List<T> drain() {
        List<T> out = new ArrayList<>();
        T e;
        while ((e = poll()) != null) out.add(e);
        return out;
    }

    public int size() { return queue.size(); }
    public int capacity() { return capacity; }
    public int remainingCapacity() { return queue.remainingCapacity(); }
    public long rejectedCount() { return rejected.get(); }
    public long enqueuedCount() { return enqueued.get(); }
    public long dequeuedCount() { return dequeued.get(); }
}
