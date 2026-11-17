package com.med.icr.domain;

import com.med.icr.util.Masking;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 根因结论卡：永远是草稿，不自动派单、不推进任何状态。 */
public final class RootCauseCard {
    private final String rootCause;
    private final String credentialId;
    private final List<String> evidenceRefs;
    private final String suggestedAction;
    private final double confidenceLower;
    private final double confidenceUpper;
    private final String severity;
    private final boolean modelUsed;
    private final Instant windowStart;
    private final int eventCount;
    private final String channelSummary;

    public RootCauseCard(String rootCause, String credentialId, List<String> evidenceRefs,
                         String suggestedAction, double confidenceLower, double confidenceUpper,
                         String severity, boolean modelUsed, Instant windowStart, int eventCount,
                         String channelSummary) {
        this.rootCause = rootCause;
        this.credentialId = credentialId;
        this.evidenceRefs = evidenceRefs == null ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(evidenceRefs));
        this.suggestedAction = suggestedAction;
        this.confidenceLower = confidenceLower;
        this.confidenceUpper = confidenceUpper;
        this.severity = severity;
        this.modelUsed = modelUsed;
        this.windowStart = windowStart;
        this.eventCount = eventCount;
        this.channelSummary = channelSummary;
    }

    public String rootCause() { return rootCause; }
    public String credentialId() { return credentialId; }
    public List<String> evidenceRefs() { return evidenceRefs; }
    public String suggestedAction() { return suggestedAction; }
    public double confidenceLower() { return confidenceLower; }
    public double confidenceUpper() { return confidenceUpper; }
    public String severity() { return severity; }
    public boolean modelUsed() { return modelUsed; }
    public Instant windowStart() { return windowStart; }
    public int eventCount() { return eventCount; }
    public String channelSummary() { return channelSummary; }
    public boolean degraded() { return !modelUsed; }

    public RootCauseCard asDegraded() {
        return new RootCauseCard(rootCause, credentialId, evidenceRefs, suggestedAction,
                Math.max(0, confidenceLower - 0.15), Math.max(0, confidenceUpper - 0.15),
                severity, false, windowStart, eventCount, channelSummary);
    }

    /** 控制台 / 可复制正文：全部经脱敏，降级卡显式标注。 */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("根因结论卡（草稿）\n");
        sb.append("根因：").append(Masking.apply(rootCause)).append('\n');
        sb.append("指认凭据：").append(credentialId == null ? "待人工关联" : credentialId).append('\n');
        sb.append("影响通道：").append(channelSummary == null ? "-" : channelSummary).append('\n');
        sb.append("证据引用：").append(String.join("、", evidenceRefs))
          .append("（共 ").append(eventCount).append(" 条事件，已脱敏）\n");
        sb.append("建议动作：").append(Masking.apply(suggestedAction)).append('\n');
        sb.append("置信提示：").append(String.format("%.2f – %.2f", confidenceLower, confidenceUpper))
          .append(" · 严重度 ").append(severity).append('\n');
        if (!modelUsed) sb.append("本次未使用模型归因（回落规则基线，置信度已下调）\n");
        return sb.toString();
    }
}
