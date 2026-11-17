package com.med.icr.ui;

import com.med.icr.domain.RootCauseCard;

import java.util.function.BooleanSupplier;

/** 右栏结论卡与人工确认闸门：未勾选 / 二次确认取消时一次 HTTP 请求都不能发出。 */
public final class RootCauseCardPane {
    /** 派发端口：返回值表示是否外发成功；测试可注入计数替身。 */
    @FunctionalInterface public interface DispatchPort { boolean dispatch(RootCauseCard card); }

    private RootCauseCard card;
    private boolean confirmed;
    private BooleanSupplier secondConfirmation = () -> true;
    private DispatchPort dispatchPort = c -> false;

    public void setCard(RootCauseCard card) { this.card = card; }
    public RootCauseCard card() { return card; }
    public void setConfirmed(boolean value) { this.confirmed = value; }
    public void setSecondConfirmation(BooleanSupplier supplier) { this.secondConfirmation = supplier; }
    public void setDispatchPort(DispatchPort port) { this.dispatchPort = port; }

    /** 勾选 + 二次确认才允许外发；任一环节缺失返回 false 且不调用派发端口。 */
    public boolean dispatch() {
        if (card == null || !confirmed) return false;
        if (!secondConfirmation.getAsBoolean()) return false;
        return dispatchPort.dispatch(card);
    }

    public String render() {
        if (card == null) return "尚无根因结论（等待失败事件）";
        StringBuilder sb = new StringBuilder(card.render());
        sb.append("已人工确认：").append(confirmed ? "是" : "否").append('\n');
        if (card.degraded()) sb.append("降级结论：本次未使用模型归因，置信提示已下调\n");
        return sb.toString();
    }
}
