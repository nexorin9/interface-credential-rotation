package com.med.icr.model;

import java.util.List;

/** 模型输入：自有类型，只有脱敏报错摘要 + 台账元数据 + 证据 id，不含明文与未脱敏原文。 */
public record ModelRequest(List<EventSummary> events, List<CredentialSummary> credentials, String hint) {
    public record EventSummary(String eventId, String channelCode, String errorClass,
                               String maskedMessage, String evidenceRef, String occurredAt) {}
    public record CredentialSummary(String credentialId, String channelCode, String credentialType,
                                    String fingerprint, String expiresAt) {}
}
