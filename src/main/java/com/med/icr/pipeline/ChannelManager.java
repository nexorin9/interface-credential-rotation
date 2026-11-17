package com.med.icr.pipeline;

import com.med.icr.domain.FailureEvent;
import com.med.icr.store.ChannelStateDao;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** 分级处置流程：一条通道致命失败不影响其余通道继续消费。 */
public final class ChannelManager {
    private final ChannelStateDao stateDao;
    private long rejected;
    private final List<String> criticalEvents = new ArrayList<>();

    public ChannelManager(ChannelStateDao stateDao) { this.stateDao = stateDao; }

    public boolean isEnabled(String channel) { return stateDao.isEnabled(channel); }

    public void consume(FailureEvent event, FailureSeverity severity, Consumer<FailureEvent> handler) {
        String channel = event.channelCode();
        if (!isEnabled(channel)) {
            rejected++;
            return;
        }
        if (severity == FailureSeverity.CHANNEL_CRITICAL) {
            // 通道级失败：停该通道的消费并置「已停用 + 原因」，其余通道继续
            stateDao.setEnabled(channel, false, "CHANNEL_CRITICAL:" + event.errorClass());
            criticalEvents.add(event.eventId());
            rejected++;
            return;
        }
        // 单条级失败：记 rejected 计数与原因，归档该条后继续（由调用方归档）
        rejected++;
        handler.accept(event);
    }

    public void consume(FailureEvent event, Consumer<FailureEvent> handler) {
        consume(event, FailureSeverity.categorize(event), handler);
    }

    public long rejectedCount() { return rejected; }
    public List<String> criticalEvents() { return List.copyOf(criticalEvents); }
    public ChannelStateDao.Status status(String channel) { return stateDao.status(channel); }
}
