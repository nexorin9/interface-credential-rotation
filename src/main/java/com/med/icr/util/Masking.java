package com.med.icr.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 报错原文脱敏：进入队列、归档、审计、导出之前必过这一层。 */
public final class Masking {
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)\\b(token|password|passwd|secret|api[_-]?key|private[_-]?key|authorization)\\b(\\s*[:=]\\s*)(\\S+)");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)");
    private static final Pattern LONG_DIGITS = Pattern.compile("(?<!\\d)\\d{12,20}(?!\\d)");

    private Masking() {}

    public static String apply(String raw) {
        if (raw == null) return "";
        String s = KEY_VALUE.matcher(raw).replaceAll(m -> {
            String key = m.group(1);
            String sep = m.group(2);
            return key + sep + "***MASKED***";
        });
        s = PHONE.matcher(s).replaceAll("***MASKED_PHONE***");
        s = ID_CARD.matcher(s).replaceAll("***MASKED_ID***");
        s = LONG_DIGITS.matcher(s).replaceAll("***MASKED***");
        return s;
    }

    public static boolean containsPlaintextMarker(String s) {
        if (s == null || s.isEmpty()) return false;
        Matcher m = KEY_VALUE.matcher(s);
        while (m.find()) {
            String v = m.group(3);
            if (v != null && !v.contains("MASKED") && v.length() > 3) return true;
        }
        return false;
    }
}
