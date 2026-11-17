package com.med.icr.domain;

/** 凭据生命周期六态。「已验证」只有一个入口：真实探测成功。 */
public enum CredentialState {
    ACTIVE("生效"),
    EXPIRING("临期"),
    EXPIRED("已过期"),
    PENDING_ROTATION("待轮换"),
    ROTATED("已轮换"),
    VERIFIED("已验证");
    private final String label;
    CredentialState(String label) { this.label = label; }
    public String label() { return label; }
}
