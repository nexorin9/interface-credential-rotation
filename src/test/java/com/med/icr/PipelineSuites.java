package com.med.icr;

import com.med.icr.domain.FailureEvent;
import com.med.icr.ingest.EsbFailureClient;
import com.med.icr.ingest.FailureIngestPipeline;
import com.med.icr.ingest.SubscriptionPoller;
import com.med.icr.pipeline.BackpressureQueue;
import com.med.icr.pipeline.ChannelManager;
import com.med.icr.pipeline.EventCounter;
import com.med.icr.pipeline.FailureSeverity;
import com.med.icr.pipeline.QueueFullException;
import com.med.icr.pipeline.QueueWatermark;
import com.med.icr.pipeline.SubscriptionRegistry;
import com.med.icr.pipeline.UnboundedArchiveQueue;
import com.med.icr.store.AuditDao;
import com.med.icr.store.ChannelStateDao;
import com.med.icr.store.CursorDao;
import com.med.icr.store.Database;
import com.med.icr.store.EventArchiveDao;
import com.med.icr.support.Fixtures;
import com.med.icr.support.StubHttpServer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** T13–T19：背压双语义、无界归档、进出计数、失败分级、游标、订阅收敛、入站整合、水位。 */
final class PipelineSuites {}

class BackpressureQueueTest {
    @Test void offerFullReturnsFalseWithoutIncrementingRejected() {
        BackpressureQueue<String> q = new BackpressureQueue<>(2);
        assertTrue(q.offer("a"));
        assertTrue(q.offer("b"));
        assertFalse(q.offer("c"));
        assertEquals(0, q.rejectedCount(), "offer 满时拒收计数不增加");
        assertEquals(2, q.enqueuedCount());
        assertEquals(0, q.capacity() - q.size());
    }

    @Test void offerOrRejectCountsRejectionOnFullQueue() {
        BackpressureQueue<String> q = new BackpressureQueue<>(1);
        assertTrue(q.offerOrReject("a"));
        assertFalse(q.offerOrReject("b"));
        assertEquals(1, q.rejectedCount());
        assertEquals(1, q.enqueuedCount(), "拒收数与入队数互不影响");
        assertEquals(1, q.dequeuedCount() + q.size());
    }

    @Test void blockingPutTimesOutWithoutFakeSuccess() {
        BackpressureQueue<String> q = new BackpressureQueue<>(1);
        assertTrue(q.put("a", 10, TimeUnit.MILLISECONDS));
        assertThrows(QueueFullException.class, () -> q.put("b", 10, TimeUnit.MILLISECONDS));
        assertEquals(1, q.enqueuedCount());
        assertEquals(0, q.rejectedCount());
    }

    @Test void drainMovesDequeuedCount() {
        BackpressureQueue<String> q = new BackpressureQueue<>(3);
        q.offer("a"); q.offer("b"); q.offer("c");
        assertEquals(3, q.drain().size());
        assertEquals(3, q.enqueuedCount());
        assertEquals(3, q.dequeuedCount());
        assertEquals(0, q.size());
    }

    @Test void recordRejectedIsIndependentOfOffer() {
        BackpressureQueue<String> q = new BackpressureQueue<>(2);
        q.recordRejected("坏报文");
        assertEquals(1, q.rejectedCount());
        assertEquals(0, q.enqueuedCount());
    }
}

class UnboundedArchiveQueueTest {
    @Test void holdsEveryEventAndDrainsInOrder() {
        UnboundedArchiveQueue<Integer> q = new UnboundedArchiveQueue<>();
        for (int i = 0; i < 1000; i++) assertTrue(q.offer(i));
        assertEquals(1000, q.size());
        assertEquals(0, q.peek());
        assertEquals(1000, q.drain().size());
        assertEquals(0, q.size());
        assertNull(q.poll());
    }
}

class EventCounterTest {
    @Test void inFlightAlwaysEqualsEnqueuedMinusDequeued() {
        EventCounter c = new EventCounter();
        c.onOffer(); c.onOffer(); c.onOffer();
        c.onPoll(true); c.onPoll(true);
        assertEquals(1, c.inFlight());
        assertEquals(c.enqueuedCount() - c.dequeuedCount(), c.inFlight());
        assertEquals(3, c.peak());
        c.onPoll(false);
        assertEquals(1, c.inFlight(), "出队失败不减计数");
    }
}

class FailureSeverityTest {
    @Test void fieldLevelErrorsAreNonCritical() {
        FailureEvent schema = Fixtures.schemaError("E-1", Fixtures.T0);
        assertEquals(FailureSeverity.EVENT_NON_CRITICAL, FailureSeverity.categorize(schema));
        assertEquals(FailureSeverity.EVENT_NON_CRITICAL, FailureSeverity.categorize(
                Fixtures.event("E-2", "CH-01", "duplicate_report", Fixtures.T0, null)));
        assertEquals(FailureSeverity.CHANNEL_CRITICAL, FailureSeverity.categorize(
                Fixtures.event("E-3", "CH-01", "cert_expired", Fixtures.T0, "fp1")));
    }

    @Test void channelCriticalStopsOnlyThatChannel() {
        try (Database db = Database.inMemory()) {
            ChannelManager manager = new ChannelManager(new ChannelStateDao(db));
            List<String> consumed = new ArrayList<>();
            manager.consume(Fixtures.event("E-1", "CH-01", "cert_expired", Fixtures.T0, "fp1"),
                    e -> consumed.add(e.eventId()));
            assertFalse(manager.isEnabled("CH-01"));
            assertEquals("CHANNEL_CRITICAL:cert_expired", manager.status("CH-01").reason());
            manager.consume(Fixtures.event("E-2", "CH-02", "field_schema", Fixtures.T0, null),
                    e -> consumed.add(e.eventId()));
            assertTrue(manager.isEnabled("CH-02"), "其余通道仍处开启态");
            assertEquals(List.of("E-2"), consumed, "通道级失败后其他通道事件仍被消费");
            assertEquals(List.of("E-1"), manager.criticalEvents());
        }
    }
}

class QueueWatermarkTest {
    @Test void eightyPercentFlipsNearReject() {
        QueueWatermark w = new QueueWatermark(10);
        assertFalse(w.observe(7, 0).nearReject());
        assertTrue(w.observe(8, 0).nearReject(), "深度 ≥ 80% 容量应置即将拒收");
        QueueWatermark.Snapshot s = w.observe(9, 3);
        assertTrue(s.nearReject());
        assertEquals(9, s.peak());
        assertEquals(3, s.rejected());
        assertEquals(10, s.capacity());
    }

    @Test void observeFromQueueReflectsRealDepth() {
        BackpressureQueue<String> q = new BackpressureQueue<>(5);
        QueueWatermark w = new QueueWatermark(5);
        q.offer("a"); q.offer("b"); q.offer("c"); q.offer("d");
        assertTrue(w.observe(q).nearReject());
        assertEquals(4, w.snapshot().depth());
    }
}

class SubscriptionRegistryTest {
    @Test void releaseFromTwoToOneKeepsQueueOpen() {
        try (Database db = Database.inMemory()) {
            SubscriptionRegistry r = new SubscriptionRegistry();
            assertTrue(r.acquire());
            assertFalse(r.acquire());
            assertEquals(2, r.references());
            assertFalse(r.release());
            assertEquals(1, r.references());
            assertTrue(r.isOpen(), "引用计数从 2 降到 1 时队列仍处开启态");
            assertFalse(r.isAborted());
            assertTrue(r.release());
            assertFalse(r.isOpen());
            assertEquals(0, r.references());
        }
    }

    @Test void reconcileRestartsFromLedgerAndWritesAudit() {
        try (Database db = Database.inMemory()) {
            AuditDao audit = new AuditDao(db);
            SubscriptionRegistry r = new SubscriptionRegistry();
            assertEquals(3, r.reconcile(3, audit));
            assertTrue(r.isOpen());
            assertFalse(r.isAborted());
            assertEquals(1, audit.count());
        }
    }
}

class SubscriptionPollerTest {
    @Test void threeEmptyRoundsKeepCursorUnchangedWithoutErrors() throws Exception {
        try (StubHttpServer stub = new StubHttpServer().setDefault(200, "[]");
             Database db = Database.inMemory()) {
            CursorDao cursorDao = new CursorDao(db);
            String initial = "2026-11-09T00:00:00Z";
            cursorDao.set(CursorDao.ESB_SINCE, initial);
            SubscriptionPoller poller = new SubscriptionPoller(
                    new EsbFailureClient(stub.url(), "token"), cursorDao, initial);
            for (int i = 0; i < 3; i++) {
                SubscriptionPoller.PollResult r = poller.runOnce();
                assertTrue(r.empty());
                assertEquals(0, r.eventCount());
            }
            assertEquals(3, poller.emptyRounds());
            assertEquals(0, poller.errorLogCount(), "空轮询不得记 ERROR 级日志");
            assertEquals(initial, poller.cursor(), "连续空轮询后游标值不变");
            assertEquals(30L, SubscriptionPoller.INTERVAL_SECONDS);
        }
    }

    @Test void nonEmptyRoundAdvancesCursor() throws Exception {
        String event = Fixtures.eventJson("E-1", "CH-01", "cert_expired", Fixtures.T0, "fp1");
        try (StubHttpServer stub = new StubHttpServer().setDefault(200, "[" + event + "]");
             Database db = Database.inMemory()) {
            CursorDao cursorDao = new CursorDao(db);
            SubscriptionPoller poller = new SubscriptionPoller(
                    new EsbFailureClient(stub.url(), "token"), cursorDao, "2026-11-09T00:00:00Z");
            SubscriptionPoller.PollResult r = poller.runOnce();
            assertFalse(r.empty());
            assertEquals(1, r.eventCount());
            assertEquals(r.cursor(), poller.cursor());
        }
    }
}

class FailureIngestPipelineTest {
    @Test void oneBadLineDoesNotBreakTheOtherNine() {
        try (Database db = Database.inMemory()) {
            BackpressureQueue<FailureEvent> queue = new BackpressureQueue<>(20);
            EventCounter counter = new EventCounter();
            QueueWatermark watermark = new QueueWatermark(20);
            EventArchiveDao archive = new EventArchiveDao(db);
            FailureIngestPipeline pipeline = new FailureIngestPipeline(queue, archive, counter, watermark);
            List<String> lines = new ArrayList<>();
            lines.add("这不是 JSON 报文");
            for (int i = 0; i < 9; i++) {
                lines.add(Fixtures.eventJson("E-" + i, "CH-01", "cert_expired",
                        Fixtures.T0.plusSeconds(i), "fp-" + (i % 2)));
            }
            FailureIngestPipeline.IngestResult r = pipeline.collect(lines);
            assertEquals(9, r.enqueued(), "同批 9 条好报文应全部入队");
            assertEquals(1, r.rejected(), "坏报文被拒收");
            assertEquals(9, queue.size());
            assertEquals(9, archive.count(), "原始事件另有归档留证");
            assertEquals(9, counter.enqueuedCount());
            assertTrue(r.reasons().stream().anyMatch(s -> s.contains("非事件报文")));
        }
    }

    @Test void errorMessagesAreMaskedBeforeEnqueue() {
        try (Database db = Database.inMemory()) {
            BackpressureQueue<FailureEvent> queue = new BackpressureQueue<>(5);
            FailureIngestPipeline pipeline = new FailureIngestPipeline(queue,
                    new EventArchiveDao(db), new EventCounter(), new QueueWatermark(5));
            String raw = "{\"event_id\":\"E-1\",\"channel_code\":\"CH-01\",\"error_class\":\"cert_expired\","
                    + "\"error_message\":\"token=RAW-TOKEN-98765 failed\","
                    + "\"occurred_at\":\"2026-11-09T08:00:00Z\"}";
            pipeline.collect(List.of(raw));
            FailureEvent queued = queue.poll();
            assertNotNull(queued);
            assertFalse(queued.errorMessage().contains("RAW-TOKEN-98765"));
            assertTrue(queued.errorMessage().contains("MASKED"));
        }
    }
}
