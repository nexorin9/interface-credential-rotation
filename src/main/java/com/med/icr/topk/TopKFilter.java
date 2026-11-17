package com.med.icr.topk;

import java.time.Instant;

/** TopK 阈值只单向收紧（升序取 min）；到期窗不相交的凭据直接剪掉。 */
public final class TopKFilter {
    private int thresholdDays = Integer.MAX_VALUE;
    private Instant windowStart;
    private Instant windowEnd;

    /** 反向输入不放松：只取更小值。 */
    public void updateThreshold(int days) {
        thresholdDays = Math.min(thresholdDays, Math.max(0, days));
    }

    public int thresholdDays() { return thresholdDays; }

    /** 恰好等于阈值天按约定入选。 */
    public boolean mayQualify(long remainingDays) {
        return remainingDays <= thresholdDays;
    }

    public void setWindow(Instant start, Instant end) {
        this.windowStart = start;
        this.windowEnd = end;
    }

    /** 凭据到期窗 = [expiresAt - thresholdDays, expiresAt]，与查询窗相交才可能入选。 */
    public boolean mayQualifyRange(Instant queryStart, Instant queryEnd, Instant expiresAt) {
        if (expiresAt == null || queryStart == null || queryEnd == null) return false;
        int effective = thresholdDays == Integer.MAX_VALUE ? 7 : thresholdDays;
        Instant credStart = expiresAt.minusSeconds((long) effective * 86400L);
        return !expiresAt.isBefore(queryStart) && !credStart.isAfter(queryEnd);
    }

    /** 使用 setWindow 配置的查询窗（两参重载）。 */
    public boolean mayQualifyRange(Instant start, Instant end) {
        return mayQualifyRange(start, end, expiresAtCandidate(start, end));
    }

    private Instant expiresAtCandidate(Instant start, Instant end) {
        // 无外部凭据时，用查询窗右端作为候选到期点，保证重载只做区间相交判定
        return end == null ? start : end;
    }
}
