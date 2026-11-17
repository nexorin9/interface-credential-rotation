package com.med.icr.ui;

import com.med.icr.pipeline.QueueWatermark;

import java.util.function.BooleanSupplier;

/** 加载 / 空 / 错误三态切换 + toast 反馈 + 破坏性操作二次确认。 */
public final class UiStateController {
    public enum ErrorKind { ESB_DISCONNECTED, LEDGER_UNREACHABLE, MODEL_UNAVAILABLE, PROBE_FAILED }

    private UiState state = UiState.EMPTY;
    private String lastToast = "";

    public UiState state() { return state; }
    public void setState(UiState state) { this.state = state; }

    public boolean buttonsDisabled() {
        return state == UiState.LOADING;
    }

    public String emptyMessage(String context) {
        return switch (context == null ? "" : context) {
            case "credential" -> "无凭据台账：请先同步台账或导入样例";
            case "event" -> "无失败事件：事件流已接入，当前窗口没有新失败";
            case "card" -> "无结论卡：等待同一凭据上的一批失败事件";
            default -> UiState.EMPTY.description();
        };
    }

    public String errorMessage(ErrorKind kind) {
        return switch (kind) {
            case ESB_DISCONNECTED -> "事件流断连：按 since 游标续拉，恢复后自动补事件";
            case LEDGER_UNREACHABLE -> "台账不可达：当前只读本地缓存快照，写回已禁用";
            case MODEL_UNAVAILABLE -> "本次未使用模型归因（已回落规则基线），不是真实错误";
            case PROBE_FAILED -> "连通性自检未通过：凭据停在已轮换，请修好后重跑自检";
        };
    }

    public String toast(String event) {
        lastToast = switch (event) {
            case "ticket_ok" -> "派单成功，处置单已写回";
            case "write_failed" -> "写回失败，可重试（本地状态未改）";
            case "scan_done" -> "到期扫描完成，清单已更新";
            default -> event;
        };
        return lastToast;
    }

    public String lastToast() { return lastToast; }

    public boolean destructiveConfirmed(BooleanSupplier secondConfirmation) {
        return secondConfirmation != null && secondConfirmation.getAsBoolean();
    }

    public boolean nearRejectStyle(QueueWatermark.Snapshot snapshot) {
        return snapshot.nearReject();
    }
}
