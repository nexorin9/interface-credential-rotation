package com.med.icr.ingest;

import com.med.icr.domain.FailureEvent;
import com.med.icr.store.CursorDao;
import com.med.icr.util.Times;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 每 30 秒增量轮询：本轮无事件返回空且继续跑，不报错、不推进游标。 */
public final class SubscriptionPoller {
    public static final long INTERVAL_SECONDS = 30;
    public record PollResult(int eventCount, String cursor, boolean empty) {}

    private final EsbFailureClient client;
    private final CursorDao cursorDao;
    private final String initialSince;
    private int emptyRounds;
    private int errorLogCount;
    private boolean running;
    private ScheduledExecutorService executor;

    public SubscriptionPoller(EsbFailureClient client, CursorDao cursorDao, String initialSince) {
        this.client = client;
        this.cursorDao = cursorDao;
        this.initialSince = initialSince == null ? Times.format(Instant.EPOCH) : initialSince;
    }

    public PollResult runOnce() {
        String since = cursorDao.get(CursorDao.ESB_SINCE, initialSince);
        List<FailureEvent> events = client.fetch(since);
        if (events.isEmpty()) {
            emptyRounds++;
            return new PollResult(0, since, true);
        }
        Instant latest = events.stream().map(FailureEvent::occurredAt).max(Instant::compareTo).orElse(Instant.EPOCH);
        String next = Times.format(latest.plusSeconds(1));
        cursorDao.set(CursorDao.ESB_SINCE, next);
        return new PollResult(events.size(), next, false);
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "icr-subscription-poller");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::runOnce, 0, INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        running = false;
        if (executor != null) executor.shutdownNow();
        executor = null;
    }

    public boolean isRunning() { return running; }
    public int emptyRounds() { return emptyRounds; }
    public int errorLogCount() { return errorLogCount; }
    public String cursor() { return cursorDao.get(CursorDao.ESB_SINCE, initialSince); }
}
