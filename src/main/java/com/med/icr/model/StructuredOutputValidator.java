package com.med.icr.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 结构化输出校验：缺字段 / 类型不符 / 越界一律判为不合规，触发降级。 */
public final class StructuredOutputValidator {
    public static final Set<String> SEVERITIES = Set.of(
            "CHANNEL_CRITICAL", "EVENT_NON_CRITICAL", "通道级", "单条级");

    private StructuredOutputValidator() {}

    public static ModelResponse validate(JsonNode node) {
        if (node == null || !node.isObject()) throw bad("输出不是 JSON 对象");
        String rootCause = text(node, "root_cause");
        String credentialId = text(node, "credential_id");
        String suggestedAction = text(node, "suggested_action");
        String severity = text(node, "severity");
        JsonNode confidenceNode = node.get("confidence");
        if (confidenceNode == null || !confidenceNode.isNumber()) throw bad("confidence 缺失或类型不符");
        double confidence = confidenceNode.asDouble();
        if (confidence < 0 || confidence > 1) throw bad("confidence 越界");
        JsonNode refsNode = node.get("evidence_refs");
        if (refsNode == null || !refsNode.isArray()) throw bad("evidence_refs 缺失或不是数组");
        List<String> refs = new ArrayList<>();
        for (JsonNode ref : refsNode) {
            if (!ref.isTextual()) throw bad("evidence_refs 含非字符串元素");
            refs.add(ref.asText());
        }
        if (!SEVERITIES.contains(severity)) throw bad("severity 不在枚举内");
        return new ModelResponse(rootCause, credentialId, confidence, refs, suggestedAction, severity);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isTextual() || v.asText().isBlank()) {
            throw bad(field + " 缺失或类型不符");
        }
        return v.asText();
    }

    private static ModelUnavailableException bad(String reason) {
        return new ModelUnavailableException(ModelUnavailableException.BAD_OUTPUT, reason);
    }
}
