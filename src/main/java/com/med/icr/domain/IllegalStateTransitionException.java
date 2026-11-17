package com.med.icr.domain;

/** 状态机非法跃迁统一异常：带错误码与 from / to，便于审计与 UI 提示。 */
public class IllegalStateTransitionException extends RuntimeException {
    public static final String CRED_CODE = "CRED_STATE_ILLEGAL_TRANSITION";
    public static final String TICKET_CODE = "TICKET_STATE_ILLEGAL_TRANSITION";
    public final String code;
    public final String from;
    public final String to;

    public IllegalStateTransitionException(String code, String from, String to) {
        super(code + " from=" + from + " to=" + to);
        this.code = code;
        this.from = from;
        this.to = to;
    }
}
