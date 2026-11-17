package com.med.icr.ui;

import com.med.icr.domain.RootCauseCard;
import com.med.icr.topk.ExpiryListExporter;
import com.med.icr.topk.RotationPlanner;
import com.med.icr.util.Masking;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 导出：到期清单 CSV（复用同一 Exporter）与根因结论正文（可复制 / 存文本），全部脱敏。 */
public final class SnapshotExporter {
    private SnapshotExporter() {}

    public static String cardText(RootCauseCard card) {
        return Masking.apply(card == null ? "无结论" : card.render());
    }

    public static void exportCardText(Path target, RootCauseCard card) throws IOException {
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Files.writeString(target, cardText(card), StandardCharsets.UTF_8);
    }

    public static void exportExpiryCsv(Path target, List<RotationPlanner.RotationRow> rows) throws IOException {
        ExpiryListExporter.export(target, rows);
    }
}
