package com.med.icr.util;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/** 时间口径：全项目统一 UTC + ISO8601（秒精度），避免系统时区污染分级与幂等键。 */
public final class Times {
    public static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC);

    private Times() {}

    public static String format(Instant instant) {
        return ISO.format(instant.truncatedTo(ChronoUnit.SECONDS));
    }

    public static Instant parse(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("时间字段为空");
        String v = value.trim();
        try {
            return Instant.parse(v);
        } catch (RuntimeException ignored) {
            try {
                return java.time.LocalDateTime.parse(v.replace(' ', 'T')).toInstant(ZoneOffset.UTC);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("非法 ISO8601 时间: " + value, e);
            }
        }
    }

    /** 按天向上取整：剩余 0 天表示今天到期但仍未过期。 */
    public static long daysUntil(Instant now, Instant target) {
        long millis = target.toEpochMilli() - now.toEpochMilli();
        if (millis <= 0) return millis == 0 ? 0 : (long) Math.floor(millis / 86_400_000.0);
        return (long) Math.ceil(millis / 86_400_000.0);
    }
}
