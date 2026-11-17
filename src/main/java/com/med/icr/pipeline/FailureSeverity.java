package com.med.icr.pipeline;

import com.med.icr.domain.ErrorClass;
import com.med.icr.domain.FailureEvent;

/** 致命 / 非致命分级：通道级失败停该通道，单条级失败只归档该条。 */
public enum FailureSeverity {
    CHANNEL_CRITICAL("通道级致命"),
    EVENT_NON_CRITICAL("单条级非致命");
    private final String label;
    FailureSeverity(String label) { this.label = label; }
    public String label() { return label; }

    public static FailureSeverity categorize(FailureEvent event) {
        ErrorClass ec = ErrorClass.parse(event.errorClass());
        if (ec.isSingleEventLevel()) return EVENT_NON_CRITICAL;
        return CHANNEL_CRITICAL;
    }
}
