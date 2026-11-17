package com.med.icr.domain;

import com.med.icr.util.Times;

import java.time.Clock;
import java.time.Instant;

/** 到期分级：红 = 已过期 / 橙 = 7 天内 / 灰 = 正常 / 无期限。UTC 口径，Clock 可注入。 */
public enum ExpiryGrade {
    RED("已过期"), ORANGE("7 天内"), GREY("正常"), NO_EXPIRY("无期限");
    private final String label;
    ExpiryGrade(String label) { this.label = label; }
    public String label() { return label; }

    public record Grade(ExpiryGrade grade, long daysLeft) {}

    public static Grade determine(Credential credential, Instant now, int warnDays) {
        Instant expiresAt = credential.expiresAt();
        if (expiresAt == null) return new Grade(NO_EXPIRY, -1);
        long days = Times.daysUntil(now, expiresAt);
        if (days < 0) return new Grade(RED, days);
        if (days <= warnDays) return new Grade(ORANGE, days);
        return new Grade(GREY, days);
    }

    public static Grade determine(Credential credential, Clock clock) {
        return determine(credential, clock.instant(), 7);
    }

    public static Grade determine(Credential credential, Instant now) {
        return determine(credential, now, 7);
    }
}
