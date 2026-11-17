package com.med.icr.attribution;

import com.med.icr.domain.FailureEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 滑动时间窗归并：半开区间 [start, start+window)，右边界事件开新窗；单条字段口径错不升级。 */
public final class WindowAggregator {
    public static final Duration DEFAULT_WINDOW = Duration.ofMinutes(5);

    public record Window(Instant start, Instant end, List<FailureEvent> events) {}

    public List<Window> aggregate(List<FailureEvent> events) {
        return aggregate(events, DEFAULT_WINDOW);
    }

    public List<Window> aggregate(List<FailureEvent> events, Duration window) {
        return aggregateByKey(events, e -> "ALL", window, false);
    }

    /** 按凭据 / 指纹 / 错误类分桶后各自做窗口归并。 */
    public List<Window> aggregateByKey(List<FailureEvent> events,
                                       Function<FailureEvent, String> keyFn,
                                       Duration window, boolean skipSingleLevelGroup) {
        List<FailureEvent> sorted = new ArrayList<>(events);
        sorted.sort(Comparator.comparing(FailureEvent::occurredAt)
                .thenComparing(e -> e.eventId() == null ? "" : e.eventId()));
        Map<String, List<FailureEvent>> buckets = new LinkedHashMap<>();
        for (FailureEvent e : sorted) buckets.computeIfAbsent(keyFn.apply(e), k -> new ArrayList<>()).add(e);
        List<Window> result = new ArrayList<>();
        for (List<FailureEvent> bucket : buckets.values()) {
            if (skipSingleLevelGroup && bucket.size() == 1 && bucket.get(0).isSchemaFieldError()) continue;
            Instant start = null;
            Instant end = null;
            List<FailureEvent> current = new ArrayList<>();
            for (FailureEvent e : bucket) {
                if (start == null || !e.occurredAt().isBefore(end)) {
                    if (start != null) result.add(new Window(start, end, List.copyOf(current)));
                    start = e.occurredAt();
                    end = start.plus(window);
                    current = new ArrayList<>();
                }
                current.add(e);
            }
            if (start != null) result.add(new Window(start, end, List.copyOf(current)));
        }
        return result;
    }
}
