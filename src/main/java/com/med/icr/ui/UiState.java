package com.med.icr.ui;

/** UI 三态：加载 / 空 / 错误（外加就绪态），每态给下一步动作。 */
public enum UiState {
    LOADING("订阅中 / 探测中，相关按钮暂不可用"),
    EMPTY("暂无数据，请先接入事件源或同步凭据台账"),
    ERROR("服务不可用，查看原因后重试"),
    READY("工作台已就绪");
    private final String description;
    UiState(String description) { this.description = description; }
    public String description() { return description; }
}
