package com.med.icr.ui;

import com.med.icr.domain.FailureEvent;
import com.med.icr.util.Masking;
import com.med.icr.util.Times;

import java.util.ArrayList;
import java.util.List;

/** 中栏失败事件时间轴：纵向 VBox + 圆点 Circle 表达，不引第三方图表库、不用 WebView。 */
public final class FailureTimelinePane {
    public record TimelineRow(String time, String channel, String errorClass, String maskedMessage, String ref) {}

    public static List<TimelineRow> rows(List<FailureEvent> events) {
        List<TimelineRow> out = new ArrayList<>();
        for (FailureEvent e : events) {
            out.add(new TimelineRow(Times.format(e.occurredAt()), e.channelCode(), e.errorClass(),
                    Masking.apply(e.errorMessage()), e.evidenceRef()));
        }
        return out;
    }

    public static String renderText(List<FailureEvent> events) {
        StringBuilder sb = new StringBuilder("失败事件时间轴\n");
        for (TimelineRow r : rows(events)) {
            sb.append("● ").append(r.time()).append("  ").append(r.channel()).append("  ")
              .append(r.errorClass()).append("  ").append(r.maskedMessage()).append('\n');
        }
        return sb.toString();
    }
}
