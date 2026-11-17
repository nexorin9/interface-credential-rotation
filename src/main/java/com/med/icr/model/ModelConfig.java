package com.med.icr.model;

import com.med.icr.config.AppConfig;

import java.net.URI;
import java.util.Map;

/** 模型配置面：全部来自环境变量，无默认密钥；业务层不耦合任何厂商 SDK。 */
public final class ModelConfig {
    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final int timeoutMs;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int maxRetries;
    private final int maxInputEvents;

    public ModelConfig(String baseUrl, String apiKey, String modelName, int timeoutMs,
                       int connectTimeoutMs, int readTimeoutMs, int maxRetries, int maxInputEvents) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.timeoutMs = timeoutMs;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.maxRetries = maxRetries;
        this.maxInputEvents = maxInputEvents;
    }

    public static ModelConfig fromEnvironment() { return from(System.getenv()); }

    public static ModelConfig from(Map<String, String> env) {
        String baseUrl = env.get(AppConfig.MODEL_BASE_URL);
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new ConfigException("配置项未设置: MODEL_BASE_URL");
        }
        return new ModelConfig(baseUrl, env.getOrDefault(AppConfig.MODEL_API_KEY, ""),
                env.getOrDefault(AppConfig.MODEL_NAME, ""),
                parseInt(env, AppConfig.MODEL_TIMEOUT_MS, 20000),
                parseInt(env, "MODEL_CONNECT_TIMEOUT_MS", 5000),
                parseInt(env, "MODEL_READ_TIMEOUT_MS", parseInt(env, AppConfig.MODEL_TIMEOUT_MS, 20000)),
                parseInt(env, AppConfig.MODEL_MAX_RETRIES, 2),
                parseInt(env, AppConfig.MODEL_MAX_INPUT_EVENTS, 200));
    }

    public static ModelConfig of(String baseUrl, String apiKey, String modelName,
                                 int timeoutMs, int maxRetries, int maxInputEvents) {
        return new ModelConfig(baseUrl, apiKey, modelName, timeoutMs, 5000, timeoutMs,
                maxRetries, maxInputEvents);
    }

    private static int parseInt(Map<String, String> env, String key, int fallback) {
        try { return Integer.parseInt(env.getOrDefault(key, Integer.toString(fallback))); }
        catch (NumberFormatException e) { return fallback; }
    }

    public String baseUrl() { return baseUrl; }
    public String apiKey() { return apiKey; }
    public String modelName() { return modelName; }
    public int timeoutMs() { return timeoutMs; }
    public int connectTimeoutMs() { return connectTimeoutMs; }
    public int readTimeoutMs() { return readTimeoutMs; }
    public int maxRetries() { return maxRetries; }
    public int maxInputEvents() { return maxInputEvents; }
    public boolean usable() {
        return baseUrl != null && !baseUrl.isBlank() && apiKey != null && !apiKey.isBlank()
                && modelName != null && !modelName.isBlank();
    }
    public String host() {
        try { return URI.create(baseUrl).getHost(); } catch (RuntimeException e) { return "-"; }
    }

    public static class ConfigException extends RuntimeException {
        public ConfigException(String message) { super(message); }
    }
}
