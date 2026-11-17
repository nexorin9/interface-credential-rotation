package com.med.icr.domain;

/** 凭据类型，与台账 credential_type 字段口径一致。 */
public enum CredentialType {
    PASSWORD("password"), CERT("cert"), API_KEY("api_key"), UNKNOWN("unknown");
    private final String code;
    CredentialType(String code) { this.code = code; }
    public String code() { return code; }
    public static CredentialType parse(String raw) {
        if (raw == null) return UNKNOWN;
        for (CredentialType t : values()) if (t.code.equalsIgnoreCase(raw.trim())) return t;
        return UNKNOWN;
    }
}
