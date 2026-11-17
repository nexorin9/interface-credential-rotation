package com.med.icr.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.med.icr.domain.FailureEvent;
import com.med.icr.util.Hashes;
import com.med.icr.util.Json;
import com.med.icr.util.Times;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** JSONL 失败事件解析：坏行进 rejected 计数，单条坏不影响整批。 */
public final class FailureEventParser {
    public record Rejected(String line, String reason) {}
    public record ParseResult(List<FailureEvent> events, List<Rejected> rejected) {}

    public FailureEvent parseLine(String line) {
        if (line == null || line.isBlank()) throw new IllegalArgumentException("空行");
        JsonNode node;
        try {
            node = Json.MAPPER.readTree(line);
        } catch (IOException e) {
            throw new IllegalArgumentException("非法 JSON: " + e.getMessage());
        }
        if (node == null || !node.isObject()) throw new IllegalArgumentException("结构不符（应为 JSON 对象）");
        String channel = require(node, "channel_code");
        String errorClass = require(node, "error_class");
        String message = require(node, "error_message");
        String occurred = require(node, "occurred_at");
        String eventId = text(node, "event_id");
        if (eventId == null) eventId = text(node, "id");
        String traceId = text(node, "trace_id");
        if (eventId == null) eventId = traceId != null ? traceId : "EVT-" + Hashes.sha256Hex(line).substring(0, 12);
        return new FailureEvent(eventId, channel, errorClass, message, Times.parse(occurred),
                text(node, "credential_fingerprint"), traceId, text(node, "endpoint"),
                text(node, "upstream_service"), text(node, "credential_id"));
    }

    public ParseResult parseLines(List<String> lines) {
        List<FailureEvent> events = new ArrayList<>();
        List<Rejected> rejected = new ArrayList<>();
        for (String line : lines) {
            // 先按类型分派，再解析：非对象行直接进 rejected，不中断整批
            if (line == null || !line.trim().startsWith("{")) {
                rejected.add(new Rejected(line, "非事件报文（先分派后解析）"));
                continue;
            }
            try {
                events.add(parseLine(line));
            } catch (RuntimeException e) {
                rejected.add(new Rejected(line, e.getMessage()));
            }
        }
        return new ParseResult(events, rejected);
    }

    public ParseResult parse(Path path) throws IOException {
        return parseLines(Files.readAllLines(path, StandardCharsets.UTF_8));
    }

    private static String require(JsonNode node, String field) {
        String v = text(node, field);
        if (v == null) throw new IllegalArgumentException("缺少必需字段 " + field);
        return v;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }
}
