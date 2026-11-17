package com.med.icr.domain;

import com.med.icr.util.Masking;

import java.time.Instant;

/** 接口失败事件：字段口径与 ESB /api/v1/interface-failures 一致。 */
public final class FailureEvent {
    private final String eventId;
    private final String channelCode;
    private final String errorClass;
    private final String errorMessage;
    private final Instant occurredAt;
    private final String credentialFingerprint;
    private final String traceId;
    private final String endpoint;
    private final String upstreamService;
    private final String credentialId;

    public FailureEvent(String eventId, String channelCode, String errorClass, String errorMessage,
                        Instant occurredAt, String credentialFingerprint, String traceId,
                        String endpoint, String upstreamService, String credentialId) {
        this.eventId = eventId;
        this.channelCode = channelCode;
        this.errorClass = errorClass;
        this.errorMessage = errorMessage;
        this.occurredAt = occurredAt;
        this.credentialFingerprint = credentialFingerprint;
        this.traceId = traceId;
        this.endpoint = endpoint;
        this.upstreamService = upstreamService;
        this.credentialId = credentialId;
    }

    public static FailureEvent of(String eventId, String channel, String errorClass, String message,
                                  Instant occurredAt, String fingerprint, String traceId) {
        return new FailureEvent(eventId, channel, errorClass, message, occurredAt, fingerprint,
                traceId, null, null, null);
    }

    public String eventId() { return eventId; }
    public String channelCode() { return channelCode; }
    public String errorClass() { return errorClass; }
    public String errorMessage() { return errorMessage; }
    public Instant occurredAt() { return occurredAt; }
    public String credentialFingerprint() { return credentialFingerprint; }
    public String traceId() { return traceId; }
    public String endpoint() { return endpoint; }
    public String upstreamService() { return upstreamService; }
    public String credentialId() { return credentialId; }

    /** 证据引用 id：优先 trace_id，缺失时用事件 id。 */
    public String evidenceRef() {
        return traceId != null && !traceId.isBlank() ? traceId : eventId;
    }
    public boolean hasFingerprint() {
        return credentialFingerprint != null && !credentialFingerprint.isBlank();
    }
    public boolean isSchemaFieldError() {
        return ErrorClass.parse(errorClass) == ErrorClass.FIELD_SCHEMA
                || (errorClass != null && errorClass.toLowerCase().contains("field"));
    }
    public FailureEvent withCredentialId(String id) {
        return new FailureEvent(eventId, channelCode, errorClass, errorMessage, occurredAt,
                credentialFingerprint, traceId, endpoint, upstreamService, id);
    }
    public FailureEvent withMaskedMessage() {
        return new FailureEvent(eventId, channelCode, errorClass, Masking.apply(errorMessage), occurredAt,
                credentialFingerprint, traceId, endpoint, upstreamService, credentialId);
    }
    public FailureEvent withMessage(String message) {
        return new FailureEvent(eventId, channelCode, errorClass, message, occurredAt,
                credentialFingerprint, traceId, endpoint, upstreamService, credentialId);
    }
}
