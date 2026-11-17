package com.med.icr.adapter;

import java.time.Instant;

/** TLS / HTTP 探测共用结果类型：通没通、证书剩余天数、失败原因分类、探测时刻。 */
public record ProbeResult(boolean passed, long certificateDaysLeft, String failureClass, String code,
                          Instant probedAt, String endpoint, String detail) {
    public static final String PASSED = "PASSED";
    public static final String FAILED = "FAILED";

    public static ProbeResult passed(long daysLeft, Instant now, String endpoint) {
        return new ProbeResult(true, daysLeft, "NONE", "PROBE_OK", now, endpoint, "连通性自检通过");
    }

    public static ProbeResult passedWithAuthWarning(Instant now, String endpoint) {
        return new ProbeResult(true, -1, "AUTH_REJECTED", "HTTP_AUTH_WARNING", now, endpoint,
                "通道可达但凭据被拒（401/403）");
    }

    public static ProbeResult failed(String failureClass, String code, Instant now, String endpoint, String detail) {
        return new ProbeResult(false, -1, failureClass, code, now, endpoint, detail);
    }
}
