package com.med.icr.domain;

import com.med.icr.util.Hashes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 凭据指纹：HMAC-SHA256(credential_id + "|" + secret)，只存前 16 个十六进制字符。
 * 密钥来自环境变量 FINGERPRINT_KEY，无默认值；任何表 / 日志 / 导出都不写 secret 明文。
 */
public final class Fingerprint {
    public static final int STORED_LENGTH = 16;
    private static final String KEY_ENV = "FINGERPRINT_KEY";

    private Fingerprint() {}

    public static String of(String credentialId, String secret) {
        String key = System.getenv(KEY_ENV);
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("配置项未设置: " + KEY_ENV + "（指纹密钥无默认值）");
        }
        return of(credentialId, secret, key);
    }

    public static String of(String credentialId, String secret, String key) {
        if (key == null || key.isBlank()) throw new IllegalStateException("指纹密钥为空");
        String full = Hashes.hmacSha256Hex(key, credentialId + "|" + secret);
        return full.substring(0, STORED_LENGTH);
    }

    public static String parse(String stored) {
        if (stored == null || !stored.matches("[0-9a-f]{16}")) {
            throw new IllegalArgumentException("非法指纹串（应为 16 位小写十六进制）");
        }
        return stored;
    }

    /** 恒定时间比较，避免时序侧信道。 */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
