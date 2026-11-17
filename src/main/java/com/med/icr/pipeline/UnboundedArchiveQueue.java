package com.med.icr.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;

/** 无界归档档：留住原始事件供事后复盘，与有界在途队列分开。 */
public final class UnboundedArchiveQueue<T> {
    private final LinkedBlockingDeque<T> deque = new LinkedBlockingDeque<>();

    public boolean offer(T e) { return deque.offer(e); }
    public T poll() { return deque.poll(); }
    public T peek() { return deque.peek(); }
    public int size() { return deque.size(); }
    public List<T> drain() {
        List<T> out = new ArrayList<>();
        T e;
        while ((e = poll()) != null) out.add(e);
        return out;
    }
}
