package com.med.icr.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 处置单：人工确认后才生成，幂等键唯一。 */
public final class Ticket {
    private final String credentialId;
    private final String rootCause;
    private final List<String> evidenceRefs;
    private final Instant dueAt;
    private final TicketState state;
    private final String idempotencyKey;
    private final String ticketNo;

    public Ticket(String credentialId, String rootCause, List<String> evidenceRefs, Instant dueAt,
                  TicketState state, String idempotencyKey, String ticketNo) {
        this.credentialId = credentialId;
        this.rootCause = rootCause;
        this.evidenceRefs = evidenceRefs == null ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(evidenceRefs));
        this.dueAt = dueAt;
        this.state = state == null ? TicketState.PENDING : state;
        this.idempotencyKey = idempotencyKey;
        this.ticketNo = ticketNo;
    }

    public String credentialId() { return credentialId; }
    public String rootCause() { return rootCause; }
    public List<String> evidenceRefs() { return evidenceRefs; }
    public Instant dueAt() { return dueAt; }
    public TicketState state() { return state; }
    public String idempotencyKey() { return idempotencyKey; }
    public String ticketNo() { return ticketNo; }

    public Ticket withState(TicketState newState) {
        return new Ticket(credentialId, rootCause, evidenceRefs, dueAt, newState, idempotencyKey, ticketNo);
    }
    public Ticket withTicketNo(String no) {
        return new Ticket(credentialId, rootCause, evidenceRefs, dueAt, state, idempotencyKey, no);
    }
}
