package com.med.icr.domain;

import com.med.icr.util.Hashes;
import com.med.icr.util.Times;

import java.time.Instant;

/**
 * 幂等键 = sha256(长度前缀(credential_id) + "|" + ISO8601(故障窗口起始))。
 * 长度前缀保证 credential_id 含分隔符时也不产生歧义。T36 写回与 T31 基准共用此处。
 */
public final class IdempotencyKey {
    private IdempotencyKey() {}

    public static String compute(String credentialId, Instant windowStart) {
        if (credentialId == null || windowStart == null) {
            throw new IllegalArgumentException("幂等键需要 credential_id 与故障窗口起始");
        }
        String canonical = credentialId.length() + ":" + credentialId + "|" + Times.format(windowStart);
        return Hashes.sha256Hex(canonical);
    }
}
