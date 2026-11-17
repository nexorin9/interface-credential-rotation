package com.med.icr.ui;

import com.med.icr.pipeline.QueueWatermark;

/** 底栏心跳行：队列深度 / 即将到期数 / 已验证数 / 模型状态。 */
public final class StatusBar {
    private final QueueWatermark.Snapshot watermark;
    private final int expiringCount;
    private final int verifiedCount;
    private final String subscriptionState;
    private final String modelState;

    public StatusBar(QueueWatermark.Snapshot watermark, int expiringCount, int verifiedCount,
                     String subscriptionState, String modelState) {
        this.watermark = watermark;
        this.expiringCount = expiringCount;
        this.verifiedCount = verifiedCount;
        this.subscriptionState = subscriptionState;
        this.modelState = modelState;
    }

    public boolean nearReject() { return watermark.nearReject(); }

    public String render() {
        return "队列深度 " + watermark.depth() + "/" + watermark.capacity()
                + "（峰值 " + watermark.peak() + "，拒收 " + watermark.rejected() + "）"
                + (nearReject() ? " 即将拒收" : "")
                + " | 即将到期 " + expiringCount
                + " | 已验证 " + verifiedCount
                + " | 订阅：" + subscriptionState
                + " | 模型：" + modelState;
    }
}
