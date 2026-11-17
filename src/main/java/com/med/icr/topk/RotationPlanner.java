package com.med.icr.topk;

import com.med.icr.domain.Credential;
import com.med.icr.domain.ExpiryGrade;
import com.med.icr.util.Times;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/** 「今天必须先换哪张」：剩余天数升序 → 影响通道数降序 → 到期时间升序，同级次序确定。 */
public final class RotationPlanner {
    public record RotationRow(String credentialId, String type, long daysLeft, int channelCount,
                              List<String> downstream, String ownerDept, String rotationEntry,
                              String expiresAt, ExpiryGrade grade, String channelCodes) {}

    private final TopKFilter filter;

    public RotationPlanner() { this(new TopKFilter()); }
    public RotationPlanner(TopKFilter filter) { this.filter = filter; }

    public List<RotationRow> plan(List<Credential> credentials, Instant now, int warnDays) {
        filter.updateThreshold(warnDays);
        Instant queryEnd = now.plusSeconds((long) warnDays * 86400L);
        List<RotationRow> rows = new ArrayList<>();
        for (Credential c : credentials) {
            ExpiryGrade.Grade grade = ExpiryGrade.determine(c, now, warnDays);
            if (grade.grade() == ExpiryGrade.NO_EXPIRY || grade.grade() == ExpiryGrade.GREY) continue;
            if (!filter.mayQualify(grade.daysLeft())) continue;
            if (!filter.mayQualifyRange(now, queryEnd, c.expiresAt())) continue;
            TreeSet<String> downstream = new TreeSet<>(c.downstreamSystems());
            TreeSet<String> channels = new TreeSet<>(c.channelCodes());
            rows.add(new RotationRow(c.credentialId(), c.credentialType().code(), grade.daysLeft(),
                    c.channelCount(), List.copyOf(downstream), c.ownerDept(), c.rotationEntry(),
                    Times.format(c.expiresAt()), grade.grade(), String.join(";", channels)));
        }
        rows.sort(Comparator.comparingLong(RotationRow::daysLeft)
                .thenComparing(Comparator.comparingInt(RotationRow::channelCount).reversed())
                .thenComparing(RotationRow::expiresAt)
                .thenComparing(RotationRow::credentialId));
        return rows;
    }

    public List<RotationRow> plan(List<Credential> credentials, Instant now) {
        return plan(credentials, now, 7);
    }

    public TopKFilter filter() { return filter; }
}
