package com.med.icr.ui;

import java.util.List;

/** 设计 token：色板 / 间距栅格 / 字体层级 / 控件四态，JavaFX 样式表统一从这里取常量。 */
public final class AppTheme {
    public static final String PRIMARY = "#1F4E79";
    public static final String ALERT_RED = "#C0392B";
    public static final String WARNING_ORANGE = "#E67E22";
    public static final String NORMAL_GREEN = "#27AE60";
    public static final String BACKGROUND = "#F5F6F8";
    public static final String CARD_WHITE = "#FFFFFF";
    public static final String BORDER = "#E1E4E8";
    public static final String TIMELINE_LINE = "#C7CDD4";

    public static final int GRID = 8;
    public static final int CARD_PADDING = 16;
    public static final int CARD_GAP = 12;
    public static final int TABLE_ROW_HEIGHT = 26;
    public static final int ROW_HEIGHT = 28;
    public static final int TITLE_SIZE = 28;

    public static final String FONT_CJK = "Microsoft YaHei, \"PingFang SC\", SansSerif";
    public static final String FONT_MONO = "\"JetBrains Mono\", monospace";
    public static final String STYLESHEET = "/com/med/icr/ui/app.css";
    public static final List<String> CONTROL_STATES = List.of("hover", "active", "disabled", "loading");

    private AppTheme() {}

    public static String colorForGrade(com.med.icr.domain.ExpiryGrade grade) {
        return switch (grade) {
            case RED -> ALERT_RED;
            case ORANGE -> WARNING_ORANGE;
            case NO_EXPIRY -> TIMELINE_LINE;
            default -> NORMAL_GREEN;
        };
    }
}
