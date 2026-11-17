package com.med.icr.ingest;

import com.med.icr.domain.FailureEvent;
import com.med.icr.pipeline.BackpressureQueue;
import com.med.icr.pipeline.EventCounter;
import com.med.icr.pipeline.QueueWatermark;
import com.med.icr.store.EventArchiveDao;

import java.util.ArrayList;
import java.util.List;

/** 入站管线整合：拉取 → 先分派后解析 → 脱敏 → 入队 → 计数 → 归档，单条坏不中断整批。 */
public final class FailureIngestPipeline {
    public record IngestResult(int enqueued, long rejected, List<String> reasons,
                               QueueWatermark.Snapshot watermark) {}

    private final FailureEventParser parser = new FailureEventParser();
    private final BackpressureQueue<FailureEvent> queue;
    private final EventArchiveDao archiveDao;
    private final EventCounter counter;
    private final QueueWatermark watermark;

    public FailureIngestPipeline(BackpressureQueue<FailureEvent> queue, EventArchiveDao archiveDao,
                                 EventCounter counter, QueueWatermark watermark) {
        this.queue = queue;
        this.archiveDao = archiveDao;
        this.counter = counter;
        this.watermark = watermark;
    }

    public IngestResult collect(List<String> rawLines) {
        FailureEventParser.ParseResult parsed = parser.parseLines(rawLines);
        List<String> reasons = new ArrayList<>();
        List<FailureEvent> archived = new ArrayList<>();
        int enqueued = 0;
        for (FailureEventParser.Rejected r : parsed.rejected()) {
            queue.recordRejected(r.reason());
            reasons.add(r.reason());
        }
        for (FailureEvent raw : parsed.events()) {
            try {
                // 报错原文脱敏后才进入队列与归档
                FailureEvent masked = raw.withMaskedMessage();
                if (queue.offerOrReject(masked)) {
                    enqueued++;
                    counter.onOffer();
                } else {
                    reasons.add("队列满拒收: " + raw.eventId());
                }
                archived.add(masked);
            } catch (RuntimeException e) {
                // 逐条 try/catch，单条失败不影响批次其余事件
                queue.recordRejected(e.getMessage());
                reasons.add(e.getMessage());
            }
        }
        archiveDao.archive(archived);
        return new IngestResult(enqueued, queue.rejectedCount(), reasons, watermark.observe(queue));
    }

    public BackpressureQueue<FailureEvent> queue() { return queue; }
    public EventCounter counter() { return counter; }
    public QueueWatermark watermark() { return watermark; }
}
