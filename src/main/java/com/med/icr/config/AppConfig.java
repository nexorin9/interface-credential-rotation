package com.med.icr.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置面：11 项环境变量。凭据位一律从环境变量读，程序自身不扫描 .env，
 * 不硬编码任何 base URL 或密钥，也不带本地回环地址 / 示例域名默认值。
 */
public final class AppConfig {
    public static final String ESB_BASE_URL = "ESB_BASE_URL";
    public static final String ESB_TOKEN = "ESB_TOKEN";
    public static final String CREDENTIAL_BASE_URL = "CREDENTIAL_BASE_URL";
    public static final String CREDENTIAL_TOKEN = "CREDENTIAL_TOKEN";
    public static final String MODEL_BASE_URL = "MODEL_BASE_URL";
    public static final String MODEL_API_KEY = "MODEL_API_KEY";
    public static final String MODEL_NAME = "MODEL_NAME";
    public static final String MODEL_TIMEOUT_MS = "MODEL_TIMEOUT_MS";
    public static final String MODEL_MAX_RETRIES = "MODEL_MAX_RETRIES";
    public static final String MODEL_MAX_INPUT_EVENTS = "MODEL_MAX_INPUT_EVENTS";
    public static final String FINGERPRINT_KEY = "FINGERPRINT_KEY";

    /** doctor 打印的 11 行清单，顺序固定。 */
    public static final List<String> KEYS = List.of(
            ESB_BASE_URL, ESB_TOKEN, CREDENTIAL_BASE_URL, CREDENTIAL_TOKEN,
            MODEL_BASE_URL, MODEL_API_KEY, MODEL_NAME, MODEL_TIMEOUT_MS,
            MODEL_MAX_RETRIES, MODEL_MAX_INPUT_EVENTS, FINGERPRINT_KEY);

    /** 缺一即无法启动：地址、令牌与指纹密钥。模型相关项缺省时关闭模型路径。 */
    public static final List<String> REQUIRED = List.of(
            ESB_BASE_URL, ESB_TOKEN, CREDENTIAL_BASE_URL, CREDENTIAL_TOKEN, FINGERPRINT_KEY);

    private static final List<String> SECRET_KEYS = List.of(ESB_TOKEN, CREDENTIAL_TOKEN, MODEL_API_KEY, FINGERPRINT_KEY);

    private final Map<String, String> values;

    private AppConfig(Map<String, String> values) {
        this.values = new LinkedHashMap<>(values);
    }

    public static AppConfig fromEnvironment() {
        return from(System.getenv());
    }

    /** 缺失必需项时抛配置异常；密钥类变量只提示「未设置」，绝不回显当前值。 */
    public static AppConfig from(Map<String, String> env) {
        Map<String, String> copy = new LinkedHashMap<>();
        for (String key : KEYS) {
            String v = env.get(key);
            if (v != null && !v.isBlank()) copy.put(key, v);
        }
        List<String> missing = new ArrayList<>();
        for (String key : REQUIRED) {
            if (!copy.containsKey(key)) missing.add(key);
        }
        if (!missing.isEmpty()) {
            throw new ConfigException("配置项未设置: " + String.join(", ", missing)
                    + "（密钥类变量仅提示未设置，不回显任何值）");
        }
        return new AppConfig(copy);
    }

    /** 运维把 .env 落地后显式指定；应用自身不扫描该文件。 */
    public static AppConfig loadFromFile(Path path) throws IOException {
        Map<String, String> env = new LinkedHashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int eq = trimmed.indexOf('=');
            if (eq < 1) continue;
            env.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
        }
        return from(env);
    }

    /** 不校验必需项，供 doctor 打印齐全性表使用。 */
    public static AppConfig lenient(Map<String, String> env) {
        Map<String, String> copy = new LinkedHashMap<>();
        for (String key : KEYS) {
            String v = env.get(key);
            if (v != null && !v.isBlank()) copy.put(key, v);
        }
        return new AppConfig(copy);
    }

    public boolean isSet(String key) { return values.containsKey(key); }
    public String get(String key) { return values.get(key); }
    public String getOrDefault(String key, String fallback) { return values.getOrDefault(key, fallback); }
    public String require(String key) {
        String v = values.get(key);
        if (v == null) throw new ConfigException("配置项未设置: " + key);
        return v;
    }
    public int intOrDefault(String key, int fallback) {
        try { return Integer.parseInt(values.getOrDefault(key, Integer.toString(fallback))); }
        catch (NumberFormatException e) { return fallback; }
    }
    public Map<String, String> values() { return Map.copyOf(values); }
    public boolean modelConfigured() {
        return isSet(MODEL_BASE_URL) && isSet(MODEL_API_KEY) && isSet(MODEL_NAME);
    }

    public static boolean isSecret(String key) { return SECRET_KEYS.contains(key); }

    public static String purpose(String key) {
        return switch (key) {
            case ESB_BASE_URL -> "接口引擎基地址（失败事件流）";
            case ESB_TOKEN -> "事件流访问令牌";
            case CREDENTIAL_BASE_URL -> "凭据台账基地址";
            case CREDENTIAL_TOKEN -> "台账读写令牌";
            case MODEL_BASE_URL -> "推理端点基地址";
            case MODEL_API_KEY -> "推理端点令牌";
            case MODEL_NAME -> "模型名";
            case MODEL_TIMEOUT_MS -> "单次调用超时（毫秒）";
            case MODEL_MAX_RETRIES -> "5xx / 429 最大重试次数";
            case MODEL_MAX_INPUT_EVENTS -> "单次归因最多输入事件数";
            case FINGERPRINT_KEY -> "指纹 HMAC 密钥（无默认值）";
            default -> "未登记的配置项";
        };
    }

    /** doctor 的一行：变量名 | 是否设置 | 用途。绝不回显值。 */
    public static final class ConfigRow {
        public final String name;
        public final boolean set;
        public final String purpose;
        public final boolean secret;
        public ConfigRow(String name, boolean set, String purpose, boolean secret) {
            this.name = name; this.set = set; this.purpose = purpose; this.secret = secret;
        }
        public String render() {
            return String.format("%-26s %-10s %s", name, set ? "是" : "未设置", purpose);
        }
    }

    public static List<ConfigRow> describe(Map<String, String> env) {
        AppConfig cfg = lenient(env);
        List<ConfigRow> rows = new ArrayList<>();
        for (String key : KEYS) rows.add(new ConfigRow(key, cfg.isSet(key), purpose(key), isSecret(key)));
        return rows;
    }

    public static class ConfigException extends RuntimeException {
        public ConfigException(String message) { super(message); }
    }
}
