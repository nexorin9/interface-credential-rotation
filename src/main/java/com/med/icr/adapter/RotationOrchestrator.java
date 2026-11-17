package com.med.icr.adapter;

import com.med.icr.domain.Credential;
import com.med.icr.domain.CredentialState;
import com.med.icr.domain.CredentialStateMachine;
import com.med.icr.domain.Ticket;
import com.med.icr.domain.TicketState;
import com.med.icr.domain.TicketStateMachine;

/** 闭环编排：探测成功 + 结论写回成功 + 派单成功，才允许推进「已验证」并关单。 */
public final class RotationOrchestrator {
    public record Outcome(Credential credential, Ticket ticket, boolean verified,
                          boolean conclusionWrittenBack, String note) {}

    public Outcome complete(Credential rotated, Ticket ticket, ProbeResult probe,
                            boolean verificationWritten, boolean ticketWriteSucceeded) {
        if (!probe.passed()) {
            return new Outcome(rotated, ticket, false, false,
                    "探测失败（" + probe.failureClass() + "），凭据状态停在已轮换");
        }
        if (!verificationWritten) {
            return new Outcome(rotated, ticket, false, false, "结论未回写，凭据状态停在已轮换");
        }
        if (!ticketWriteSucceeded) {
            return new Outcome(rotated, ticket, false, true, "写回失败，可重试；票据保持待确认");
        }
        Credential verified = rotated.withState(CredentialStateMachine.transitionToVerified(
                rotated.state(), CredentialStateMachine.PROBE_VERIFIED, true));
        Ticket closed = ticket;
        if (ticket.state() == TicketState.DISPATCHED) {
            closed = ticket.withState(TicketStateMachine.transition(ticket.state(), TicketState.CLOSED));
        }
        return new Outcome(verified, closed, true, true, "自检通过，凭据已验证并关单");
    }

    /** 探测失败时的显式不变量：状态停在「已轮换」。 */
    public boolean staysRotatedOnProbeFailure(Credential before, Credential after) {
        return before.state() == CredentialState.ROTATED && after.state() == CredentialState.ROTATED;
    }
}
