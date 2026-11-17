package com.med.icr.domain;

import java.util.Map;
import java.util.Set;

/** 处置单状态机：待确认 → 已派发 → 已关闭，另允许撤销未派发单直接关单。 */
public final class TicketStateMachine {
    private static final Map<TicketState, Set<TicketState>> LEGAL = Map.of(
            TicketState.PENDING, Set.of(TicketState.DISPATCHED, TicketState.CLOSED),
            TicketState.DISPATCHED, Set.of(TicketState.CLOSED),
            TicketState.CLOSED, Set.of());
    private TicketStateMachine() {}

    public static boolean isLegal(TicketState from, TicketState to) {
        return LEGAL.getOrDefault(from, Set.of()).contains(to);
    }
    public static TicketState transition(TicketState from, TicketState to) {
        if (!isLegal(from, to)) {
            throw new IllegalStateTransitionException(IllegalStateTransitionException.TICKET_CODE,
                    from == null ? "null" : from.name(), to == null ? "null" : to.name());
        }
        return to;
    }
}
