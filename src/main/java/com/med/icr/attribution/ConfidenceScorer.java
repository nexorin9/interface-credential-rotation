package com.med.icr.attribution;

/** 置信度评分：输出区间而非单点；带指纹显著高于仅 error_class 的窗口。 */
public final class ConfidenceScorer {
    public record Confidence(double lower, double upper) {}

    private ConfidenceScorer() {}

    public static Confidence score(int evidenceCount, int diversity, boolean hasFingerprint, boolean crossChannel) {
        if (evidenceCount <= 0) return new Confidence(0.0, 0.0);
        double base = 0.25 + 0.06 * Math.min(evidenceCount, 10) + 0.04 * Math.min(diversity, 6)
                + (hasFingerprint ? 0.22 : 0.0) + (crossChannel ? 0.05 : 0.0);
        base = Math.min(0.94, base);
        return new Confidence(round(base - 0.08), round(base + 0.08));
    }

    private static double round(double v) {
        return Math.round(Math.max(0, Math.min(1, v)) * 100.0) / 100.0;
    }
}
