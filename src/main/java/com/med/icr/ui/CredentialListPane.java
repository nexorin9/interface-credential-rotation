package com.med.icr.ui;

import com.med.icr.topk.RotationPlanner;

import java.util.ArrayList;
import java.util.List;

/** 左栏「即将到期清单」数据绑定：与 CLI 共用同一 RotationPlanner，不写两套逻辑。 */
public final class CredentialListPane {
    public record Row(String credentialId, String daysLeft, String channels, String downstream,
                      String ownerDept, String gradeColor) {}

    public static List<Row> rows(List<RotationPlanner.RotationRow> planned) {
        List<Row> out = new ArrayList<>();
        for (RotationPlanner.RotationRow r : planned) {
            out.add(new Row(r.credentialId(), Long.toString(r.daysLeft()), r.channelCodes(),
                    String.join(";", r.downstream()), r.ownerDept(), AppTheme.colorForGrade(r.grade())));
        }
        return out;
    }

    public static String renderText(List<RotationPlanner.RotationRow> planned) {
        StringBuilder sb = new StringBuilder("即将到期清单（今天先换哪张）\n");
        sb.append("凭据号        剩余天数  通道  下游  责任科室\n");
        for (Row r : rows(planned)) {
            sb.append(String.format("%-14s %-9s %-8s %-20s %s%n", r.credentialId(), r.daysLeft(),
                    r.channels(), r.downstream(), r.ownerDept()));
        }
        return sb.toString();
    }
}
