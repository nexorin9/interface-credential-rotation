package com.med.icr.topk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 到期清单导出：UTF-8 带 BOM（Excel 直开不乱码），表头固定，无凭据明文。 */
public final class ExpiryListExporter {
    public static final String HEADER = "凭据号,类型,剩余天数,通道,下游,责任科室,到期日";
    private static final String BOM = "\uFEFF";

    private ExpiryListExporter() {}

    public static String toCsv(List<RotationPlanner.RotationRow> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(HEADER).append('\n');
        for (RotationPlanner.RotationRow r : rows) {
            sb.append(r.credentialId()).append(',')
              .append(r.type()).append(',')
              .append(r.daysLeft()).append(',')
              .append(escape(r.channelCodes())).append(',')
              .append(escape(String.join(";", r.downstream()))).append(',')
              .append(escape(r.ownerDept())).append(',')
              .append(r.expiresAt()).append('\n');
        }
        return sb.toString();
    }

    public static void export(Path target, List<RotationPlanner.RotationRow> rows) throws IOException {
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Files.writeString(target, BOM + toCsv(rows), StandardCharsets.UTF_8);
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace(",", ";").replace("\n", " ").replace("\r", " ");
    }
}
