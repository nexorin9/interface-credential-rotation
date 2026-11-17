package com.med.icr.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.med.icr.util.Json;
import com.med.icr.util.Masking;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** OpenAI-compatible 客户端：JSON Schema 结构化请求 + 响应解析 + 退避重试语义。 */
public final class OpenAiCompatibleGateway implements ModelGateway {
    public static final String PATH = "/v1/chat/completions";

    private final ModelConfig config;
    private final HttpClient http;
    private final long backoffMillis;
    private int lastRetryCount;

    public OpenAiCompatibleGateway(ModelConfig config) {
        this(config, HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1, config.connectTimeoutMs()))).build(), 20L);
    }

    public OpenAiCompatibleGateway(ModelConfig config, HttpClient http, long backoffMillis) {
        this.config = config;
        this.http = http;
        this.backoffMillis = backoffMillis;
    }

    @Override public String name() { return config.modelName(); }

    @Override
    public ModelResponse infer(ModelRequest request) {
        lastRetryCount = 0;
        String body = buildRequestBody(request);
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(trimTrailingSlash(config.baseUrl()) + PATH))
                .timeout(Duration.ofMillis(Math.max(1, config.readTimeoutMs())))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + (config.apiKey() == null ? "" : config.apiKey()))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        int attempts = Math.max(0, config.maxRetries());
        for (int attempt = 0; attempt <= attempts; attempt++) {
            try {
                HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status == 429 || status >= 500) {
                    if (attempt < attempts) {
                        lastRetryCount++;
                        backoff(attempt);
                        continue;
                    }
                    throw new ModelUnavailableException(
                            status == 429 ? ModelUnavailableException.RATE_LIMITED : ModelUnavailableException.SERVER_ERROR,
                            "重试 " + attempts + " 次后仍失败，状态码 " + status);
                }
                if (status >= 400) {
                    // 4xx = 配置错误（模型名错 / 鉴权错 / schema 不支持），直接降级、不重试
                    throw new ModelUnavailableException(ModelUnavailableException.CONFIG_ERROR,
                            "配置错误，状态码 " + status);
                }
                return parseResponse(response.body());
            } catch (IOException e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if (msg.contains("timed out") || msg.contains("timeout")) {
                    throw new ModelUnavailableException(ModelUnavailableException.TIMEOUT, "读超时");
                }
                throw new ModelUnavailableException(ModelUnavailableException.UNREACHABLE, "网络不可达: " + msg);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ModelUnavailableException(ModelUnavailableException.UNREACHABLE, "请求被中断");
            }
        }
        throw new ModelUnavailableException(ModelUnavailableException.UNREACHABLE, "重试耗尽");
    }

    public String buildRequestBody(ModelRequest request) {
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("model", config.modelName());
        ArrayNode messages = root.putArray("messages");
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        system.put("content", "你是医院信息科的接口失效归因助手。只依据输入事件与台账元数据，"
                + "从报错摘要推断属于哪一张凭据与哪一类根因，输出规定的结构化字段，不要编造证据引用。");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        ArrayNode events = Json.MAPPER.createArrayNode();
        int limit = Math.max(1, config.maxInputEvents());
        int count = 0;
        for (ModelRequest.EventSummary e : request.events()) {
            if (count++ >= limit) break;
            ObjectNode n = events.addObject();
            n.put("event_id", e.eventId());
            n.put("channel_code", e.channelCode());
            n.put("error_class", e.errorClass());
            n.put("message", Masking.apply(e.maskedMessage()));
            n.put("evidence_ref", e.evidenceRef());
            n.put("occurred_at", e.occurredAt());
        }
        ArrayNode credentials = Json.MAPPER.createArrayNode();
        for (ModelRequest.CredentialSummary c : request.credentials()) {
            ObjectNode n = credentials.addObject();
            n.put("credential_id", c.credentialId());
            n.put("channel_code", c.channelCode());
            n.put("credential_type", c.credentialType());
            n.put("fingerprint", c.fingerprint());
            n.put("expires_at", c.expiresAt());
        }
        ObjectNode payload = Json.MAPPER.createObjectNode();
        payload.set("events", events);
        payload.set("credentials", credentials);
        payload.put("hint", request.hint() == null ? "" : request.hint());
        try {
            user.put("content", Json.MAPPER.writeValueAsString(payload));
        } catch (IOException e) {
            throw new IllegalStateException("模型请求序列化失败", e);
        }
        ObjectNode responseFormat = root.putObject("response_format");
        responseFormat.put("type", "json_schema");
        ObjectNode schema = responseFormat.putObject("json_schema");
        schema.put("name", "icr_attribution");
        schema.put("strict", true);
        ObjectNode fields = schema.putObject("schema");
        fields.put("type", "object");
        ObjectNode properties = fields.putObject("properties");
        properties.putObject("root_cause").put("type", "string");
        properties.putObject("credential_id").put("type", "string");
        properties.putObject("confidence").put("type", "number");
        ObjectNode refs = properties.putObject("evidence_refs");
        refs.put("type", "array");
        refs.putObject("items").put("type", "string");
        properties.putObject("suggested_action").put("type", "string");
        properties.putObject("severity").put("type", "string");
        ArrayNode required = fields.putArray("required");
        for (String f : new String[]{"root_cause", "credential_id", "confidence",
                "evidence_refs", "suggested_action", "severity"}) required.add(f);
        fields.put("additionalProperties", false);
        try {
            return Json.MAPPER.writeValueAsString(root);
        } catch (IOException e) {
            throw new IllegalStateException("模型请求序列化失败", e);
        }
    }

    private ModelResponse parseResponse(String body) {
        try {
            JsonNode root = Json.MAPPER.readTree(body);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || !content.isTextual()) {
                throw new ModelUnavailableException(ModelUnavailableException.BAD_OUTPUT, "响应缺 choices[0].message.content");
            }
            JsonNode structured = Json.MAPPER.readTree(content.asText());
            return StructuredOutputValidator.validate(structured);
        } catch (ModelUnavailableException e) {
            throw e;
        } catch (IOException e) {
            throw new ModelUnavailableException(ModelUnavailableException.BAD_OUTPUT, "响应不可解析");
        }
    }

    private void backoff(int attempt) {
        long millis = Math.min(200L, backoffMillis * (1L << Math.min(attempt, 5)));
        try { Thread.sleep(millis); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public int lastRetryCount() { return lastRetryCount; }
    public ModelConfig config() { return config; }
}
