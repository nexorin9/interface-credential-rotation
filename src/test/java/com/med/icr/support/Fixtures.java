package com.med.icr.support;

import com.med.icr.domain.Credential;
import com.med.icr.domain.CredentialState;
import com.med.icr.domain.CredentialType;
import com.med.icr.domain.FailureEvent;
import com.med.icr.domain.Ticket;
import com.med.icr.domain.TicketState;
import com.med.icr.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** 测试夹具：只在 src/test 可见，生产代码不依赖本包。 */
public final class Fixtures {
    public static final String FP_KEY = "TEST-FINGERPRINT-KEY-NOT-A-SECRET";
    public static final Instant T0 = Instant.parse("2026-11-09T08:00:00Z");

    private Fixtures() {}

    public static FailureEvent event(String id, String channel, String errorClass, Instant at, String fp) {
        return new FailureEvent(id, channel, errorClass, "certificate has expired", at, fp,
                "TRACE-" + id, "/api/v1/placeholder", "上游系统", null);
    }

    public static FailureEvent schemaError(String id, Instant at) {
        return new FailureEvent(id, "CH-03", "field_schema", "字段口径错 <EXAMPLE_FIELD>", at,
                null, "TRACE-" + id, null, null, null);
    }

    public static Credential credential(String id, String fp, Instant expiresAt, String... channels) {
        String[] ch = channels.length == 0 ? new String[]{"CH-01"} : channels;
        return new Credential(id, ch[0], CredentialType.CERT, expiresAt, "信息科", "集成运维岗",
                "厂商服务台", null, fp, CredentialState.ACTIVE, Arrays.asList(ch),
                List.of("医保平台", "卫健数据上报"));
    }

    public static Ticket ticket(String credentialId, String key) {
        return new Ticket(credentialId, "接口失败集中在该凭据上", List.of("TRACE-1", "TRACE-2"),
                T0.plusSeconds(3L * 86400), TicketState.PENDING, key, null);
    }

    /** 合法 JSONL 事件行，字段口径与正式端点一致。 */
    public static String eventJson(String id, String channel, String errorClass, Instant at, String fp) {
        var n = Json.MAPPER.createObjectNode();
        n.put("event_id", id);
        n.put("channel_code", channel);
        n.put("error_class", errorClass);
        n.put("error_message", "TLS handshake failed");
        n.put("occurred_at", at.toString());
        if (fp == null) n.putNull("credential_fingerprint");
        else n.put("credential_fingerprint", fp);
        n.put("trace_id", "TRACE-" + id);
        return n.toString();
    }

    public static String sha256(Path path) throws IOException, NoSuchAlgorithmException {
        return hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    public static String sha256(String text) throws NoSuchAlgorithmException {
        return hex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
