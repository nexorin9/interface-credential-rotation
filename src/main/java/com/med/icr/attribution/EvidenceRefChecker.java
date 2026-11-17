package com.med.icr.attribution;

import com.med.icr.domain.Credential;
import com.med.icr.domain.FailureEvent;
import com.med.icr.domain.RootCauseCard;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 证据引用合法性：模型引用的每个 id 必须存在于本次输入事件，否则整卡不合规。 */
public final class EvidenceRefChecker {
    public record Result(boolean valid, List<String> invalidRefs, String reason) {}

    public Result check(List<String> refs, List<FailureEvent> inputEvents) {
        Set<String> valid = new HashSet<>();
        for (FailureEvent e : inputEvents) {
            valid.add(e.evidenceRef());
            if (e.eventId() != null) valid.add(e.eventId());
            if (e.traceId() != null) valid.add(e.traceId());
        }
        List<String> invalid = new ArrayList<>();
        for (String ref : refs) if (!valid.contains(ref)) invalid.add(ref);
        return invalid.isEmpty() ? new Result(true, List.of(), "ok")
                : new Result(false, invalid, "evidence_ref_not_in_input");
    }

    public Result checkCredential(String credentialId, List<Credential> ledger) {
        boolean found = ledger.stream().anyMatch(c -> c.credentialId().equals(credentialId));
        return found ? new Result(true, List.of(), "ok")
                : new Result(false, List.of(credentialId), "credential_not_in_ledger");
    }

    public Result checkCard(RootCauseCard card, List<FailureEvent> inputEvents, List<Credential> ledger) {
        Result refs = check(card.evidenceRefs(), inputEvents);
        if (!refs.valid()) return refs;
        if (card.credentialId() == null || card.credentialId().isBlank()) {
            return new Result(false, List.of(), "credential_missing");
        }
        return checkCredential(card.credentialId(), ledger);
    }
}
