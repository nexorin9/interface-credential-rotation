package com.med.icr.domain;

/** 统一错误类口径（RFC 9457 / FHIR OperationOutcome.issue.code 归一后的院内子集）。 */
public enum ErrorClass {
    CERT_EXPIRED("cert_expired"),
    TLS_HANDSHAKE("tls_handshake"),
    HOSTNAME_MISMATCH("hostname_mismatch"),
    AUTH_REVOKED("auth_revoked"),
    CONNECTION_RESET("connection_reset"),
    FIELD_SCHEMA("field_schema"),
    MODEL_UNAVAILABLE("model_unavailable"),
    DUPLICATE_REPORT("duplicate_report"),
    UNKNOWN("unknown");
    private final String code;
    ErrorClass(String code) { this.code = code; }
    public String code() { return code; }

    public static ErrorClass parse(String raw) {
        if (raw == null) return UNKNOWN;
        for (ErrorClass e : values()) if (e.code.equalsIgnoreCase(raw.trim())) return e;
        return UNKNOWN;
    }

    /** 单条级失败的典型口径：字段口径错，不得升级为凭据级结论。 */
    public boolean isSingleEventLevel() {
        return this == FIELD_SCHEMA || this == DUPLICATE_REPORT || this == UNKNOWN;
    }
}
