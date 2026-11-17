package com.med.icr.attribution;

/** 人工确认闸门：未确认 = 草稿；勾选 + 二次确认才允许生成处置单。 */
public final class ConfirmationGate {
    private boolean confirmed;
    private boolean secondConfirmed;

    public void confirm() { confirmed = true; }
    public void secondConfirm() { secondConfirmed = true; }
    public void cancel() { confirmed = false; secondConfirmed = false; }
    public boolean confirmed() { return confirmed; }
    public boolean canDispatch() { return confirmed && secondConfirmed; }
}
