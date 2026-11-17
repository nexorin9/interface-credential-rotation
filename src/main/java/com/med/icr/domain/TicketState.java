package com.med.icr.domain;

/** 处置单生命周期三态。 */
public enum TicketState {
    PENDING("待确认"), DISPATCHED("已派发"), CLOSED("已关闭");
    private final String label;
    TicketState(String label) { this.label = label; }
    public String label() { return label; }
}
