package com.med.icr.attribution;

import com.med.icr.domain.Credential;
import com.med.icr.domain.FailureEvent;
import com.med.icr.domain.RootCauseCard;
import com.med.icr.util.Times;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 确定性规则基线：先按 fingerprint 分桶，缺指纹时按 error_class 分桶，再按窗口相交合并。
 * 同一输入多次运行逐字节相同；单条字段口径错不升级为凭据级结论。
 */
public final class RuleAttributor {
    public static final int DEFAULT_MAX_EVIDENCE = 10;

    public record Options(Duration window, int maxEvidence) {
        public static Options defaults() { return new Options(WindowAggregator.DEFAULT_WINDOW, DEFAULT_MAX_EVIDENCE); }
    }

    private final WindowAggregator aggregator = new WindowAggregator();

    /** T3 最窄主干：不依赖台账，直接输出结论卡。 */
    public List<RootCauseCard> attribute(List<FailureEvent> events) {
        return attribute(events, List.of(), Options.defaults());
    }

    public List<RootCauseCard> attribute(List<FailureEvent> events, List<Credential> ledger) {
        return attribute(events, ledger, Options.defaults());
    }

    public List<RootCauseCard> attribute(List<FailureEvent> events, List<Credential> ledger, Options options) {
        List<FailureEvent> sorted = new ArrayList<>(events);
        sorted.sort(Comparator.comparing(FailureEvent::occurredAt)
                .thenComparing(e -> e.eventId() == null ? "" : e.eventId()));
        Map<String, List<FailureEvent>> buckets = new LinkedHashMap<>();
        for (FailureEvent e : sorted) {
            String key = e.hasFingerprint() ? "fp:" + e.credentialFingerprint() : "ec:" + e.errorClass();
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
        }
        List<RootCauseCard> cards = new ArrayList<>();
        for (Map.Entry<String, List<FailureEvent>> entry : new java.util.TreeMap<>(buckets).entrySet()) {
            String key = entry.getKey();
            List<FailureEvent> bucket = entry.getValue();
            if (bucket.size() == 1 && bucket.get(0).isSchemaFieldError()) continue;
            List<WindowAggregator.Window> windows =
                    aggregator.aggregateByKey(bucket, e -> key, options.window(), true);
            for (WindowAggregator.Window w : windows) {
                List<FailureEvent> group = w.events();
                if (group.isEmpty()) continue;
                if (group.size() == 1 && group.get(0).isSchemaFieldError()) continue;
                cards.add(buildCard(key, group, ledger, options, w.start()));
            }
        }
        cards.sort(Comparator.<RootCauseCard, String>comparing(
                        c -> c.windowStart() == null ? "" : Times.format(c.windowStart()))
                .thenComparing(RootCauseCard::credentialId, Comparator.nullsLast(String::compareTo)));
        return cards;
    }

    private RootCauseCard buildCard(String key, List<FailureEvent> group, List<Credential> ledger,
                                   Options options, java.time.Instant windowStart) {
        String fingerprint = key.startsWith("fp:") ? key.substring(3) : null;
        String credentialId = null;
        if (fingerprint != null) {
            for (Credential c : ledger) {
                if (fingerprint.equals(c.fingerprint())) { credentialId = c.credentialId(); break; }
            }
        }
        if (credentialId == null) {
            for (FailureEvent e : group) {
                if (e.credentialId() != null && !e.credentialId().isBlank()) { credentialId = e.credentialId(); break; }
            }
        }
        TreeSet<String> channels = new TreeSet<>();
        TreeSet<String> errorClasses = new TreeSet<>();
        List<String> refs = new ArrayList<>();
        boolean hasFingerprint = false;
        for (FailureEvent e : group) {
            channels.add(e.channelCode() == null ? "-" : e.channelCode());
            errorClasses.add(e.errorClass() == null ? "-" : e.errorClass());
            hasFingerprint |= e.hasFingerprint();
            refs.add(e.evidenceRef());
        }
        refs.sort(String::compareTo);
        if (refs.size() > options.maxEvidence()) refs = new ArrayList<>(refs.subList(0, options.maxEvidence()));
        boolean crossChannel = channels.size() > 1;
        String severity = crossChannel ? "通道级" : "单条级";
        ConfidenceScorer.Confidence confidence =
                ConfidenceScorer.score(group.size(), errorClasses.size(), hasFingerprint, crossChannel);
        String rootCause = "接口通道 " + String.join(" / ", channels) + " 的 " + group.size()
                + " 条失败集中在同一张对接凭据上（错误类：" + String.join("、", errorClasses) + "）";
        String channelsText = String.join(" / ", channels);
        String action = "联系责任科室与厂商换证，换证完成后重跑连通性自检";
        return new RootCauseCard(rootCause, credentialId, refs, action,
                confidence.lower(), confidence.upper(), severity, false, windowStart,
                group.size(), channelsText);
    }
}
