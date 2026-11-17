package com.med.icr.domain;

import java.util.Map;
import java.util.Set;

/** 凭据状态机：跃迁表封闭，任何非法跃迁都抛带码异常。 */
public final class CredentialStateMachine {
    public static final String PROBE_VERIFIED = "PROBE_VERIFIED";

    private static final Map<CredentialState, Set<CredentialState>> LEGAL = Map.of(
            CredentialState.ACTIVE, Set.of(CredentialState.EXPIRING, CredentialState.EXPIRED),
            CredentialState.EXPIRING, Set.of(CredentialState.EXPIRED),
            CredentialState.EXPIRED, Set.of(CredentialState.PENDING_ROTATION),
            CredentialState.PENDING_ROTATION, Set.of(CredentialState.ROTATED, CredentialState.ACTIVE),
            CredentialState.ROTATED, Set.of(CredentialState.VERIFIED),
            CredentialState.VERIFIED, Set.of());

    private CredentialStateMachine() {}

    public static boolean isLegal(CredentialState from, CredentialState to) {
        return LEGAL.getOrDefault(from, Set.of()).contains(to);
    }

    public static void validate(CredentialState from, CredentialState to) {
        if (!isLegal(from, to)) {
            throw new IllegalStateTransitionException(IllegalStateTransitionException.CRED_CODE,
                    from == null ? "null" : from.name(), to == null ? "null" : to.name());
        }
    }

    public static CredentialState transition(CredentialState from, CredentialState to) {
        validate(from, to);
        return to;
    }

    /**
     * 「已验证」的唯一入口。除了 ROTATED → VERIFIED 且触发源为真实探测成功，
     * 其他任何调用都拒绝；业务代码不得直接 setState。
     */
    public static CredentialState transitionToVerified(CredentialState from, String trigger, boolean probeVerified) {
        if (!PROBE_VERIFIED.equals(trigger) || !probeVerified
                || !isLegal(from, CredentialState.VERIFIED)) {
            throw new IllegalStateTransitionException(IllegalStateTransitionException.CRED_CODE,
                    from == null ? "null" : from.name(), CredentialState.VERIFIED.name());
        }
        return CredentialState.VERIFIED;
    }

    public static Credential withState(Credential credential, CredentialState to) {
        return credential.withState(transition(credential.state(), to));
    }
}
