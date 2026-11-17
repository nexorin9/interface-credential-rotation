package com.med.icr.attribution;

import com.med.icr.domain.Credential;
import com.med.icr.domain.FailureEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 双键绑定：指纹为主、通道号为辅；冲突或缺任一 → 拒绝归因，落 unresolved 分区。 */
public final class CredentialBinder {
    public record Decision(FailureEvent event, boolean resolved, String credentialId, String reason) {}
    public record BoundEvent(FailureEvent event, String credentialId) {}
    public record BindingResult(List<BoundEvent> bound, List<Decision> unresolved, List<Decision> decisions) {}

    public BindingResult bind(List<FailureEvent> events, List<Credential> ledger) {
        Map<String, Set<String>> fpToIds = new HashMap<>();
        Map<String, Set<String>> idToFps = new HashMap<>();
        Map<String, Credential> byId = new HashMap<>();
        for (Credential c : ledger) {
            byId.put(c.credentialId(), c);
            if (c.fingerprint() != null && !c.fingerprint().isBlank()) {
                fpToIds.computeIfAbsent(c.fingerprint(), k -> new HashSet<>()).add(c.credentialId());
                idToFps.computeIfAbsent(c.credentialId(), k -> new HashSet<>()).add(c.fingerprint());
            }
        }
        List<BoundEvent> bound = new ArrayList<>();
        List<Decision> unresolved = new ArrayList<>();
        List<Decision> decisions = new ArrayList<>();
        for (FailureEvent e : events) {
            Decision d = bindOne(e, fpToIds, idToFps, byId);
            decisions.add(d);
            if (d.resolved()) bound.add(new BoundEvent(e, d.credentialId()));
            else unresolved.add(d);
        }
        return new BindingResult(bound, unresolved, decisions);
    }

    private Decision bindOne(FailureEvent e, Map<String, Set<String>> fpToIds,
                             Map<String, Set<String>> idToFps, Map<String, Credential> byId) {
        if (!e.hasFingerprint()) {
            return new Decision(e, false, null, "missing_fingerprint");
        }
        Set<String> ids = fpToIds.getOrDefault(e.credentialFingerprint(), Set.of());
        if (ids.size() > 1) return new Decision(e, false, null, "fingerprint_conflict");
        if (ids.isEmpty()) return new Decision(e, false, null, "fingerprint_not_in_ledger");
        String id = ids.iterator().next();
        if (idToFps.getOrDefault(id, Set.of()).size() > 1) {
            return new Decision(e, false, null, "credential_fingerprint_conflict");
        }
        Credential c = byId.get(id);
        if (c != null && e.channelCode() != null && !c.channelCodes().isEmpty()
                && !c.channelCodes().contains(e.channelCode())) {
            return new Decision(e, false, null, "channel_code_mismatch");
        }
        return new Decision(e, true, id, "fingerprint_primary");
    }
}
