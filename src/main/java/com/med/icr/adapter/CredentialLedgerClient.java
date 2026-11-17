package com.med.icr.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.med.icr.config.AppConfig;
import com.med.icr.domain.Credential;
import com.med.icr.domain.CredentialType;
import com.med.icr.store.CredentialDao;
import com.med.icr.util.Json;
import com.med.icr.util.Times;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 凭据台账真实 GET：只映射元数据，出现疑似明文字段则拒绝解析该字段并告警。 */
public final class CredentialLedgerClient {
    public static final String PATH = "/api/v1/integration-credentials";
    private static final List<String> PLAINTEXT_FIELDS = List.of("secret", "password", "private_key", "credential_secret");

    public record LedgerResult(List<Credential> credentials, int plaintextWarnings,
                               boolean readOnlyFallback, String errorCode) {}

    private final String baseUrl;
    private final String token;
    private final HttpClient http;
    private final Duration timeout;
    private final CredentialDao cacheDao;
    private int plaintextWarnings;

    public CredentialLedgerClient(AppConfig config) {
        this(config.require(AppConfig.CREDENTIAL_BASE_URL), config.require(AppConfig.CREDENTIAL_TOKEN), null);
    }

    public CredentialLedgerClient(String baseUrl, String token) {
        this(baseUrl, token, null);
    }

    public CredentialLedgerClient(String baseUrl, String token, CredentialDao cacheDao) {
        this(baseUrl, token, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                Duration.ofSeconds(8), cacheDao);
    }

    public CredentialLedgerClient(String baseUrl, String token, HttpClient http, Duration timeout, CredentialDao cacheDao) {
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("CREDENTIAL_BASE_URL 未设置");
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
        this.http = http;
        this.timeout = timeout;
        this.cacheDao = cacheDao;
    }

    public List<Credential> fetch() { return fetchWithWarnings().credentials(); }

    public LedgerResult fetchWithWarnings() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + PATH))
                .timeout(timeout)
                .header("authorization", "bearer " + (token == null ? "" : token))
                .GET().build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            return fallback(AdapterException.UNREACHABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback(AdapterException.UNREACHABLE);
        }
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new AdapterException(AdapterException.AUTH_FAILED, status, "台账鉴权失败");
        }
        if (status >= 500) {
            throw new AdapterException(AdapterException.SERVER_ERROR, status, "台账服务端错误");
        }
        if (status < 200 || status >= 300) {
            throw new AdapterException(AdapterException.SERVER_ERROR, status, "台账非预期状态码");
        }
        JsonNode rows;
        try {
            JsonNode root = Json.MAPPER.readTree(response.body());
            rows = root.isArray() ? root : root.path("credentials");
            if (!rows.isArray()) throw new IllegalStateException("结构不符");
        } catch (IOException | IllegalStateException e) {
            throw new AdapterException(AdapterException.BAD_STRUCTURE, status, "台账响应结构不符");
        }
        List<Credential> out = new ArrayList<>();
        int warnings = 0;
        for (JsonNode row : rows) {
            for (String field : PLAINTEXT_FIELDS) {
                if (row.has(field)) {
                    // 拒绝解析该字段，只记告警，绝不落库
                    warnings++;
                }
            }
            List<String> channels = new ArrayList<>();
            for (JsonNode c : row.path("channel_codes")) channels.add(c.asText());
            List<String> downstream = new ArrayList<>();
            for (JsonNode d : row.path("downstream_systems")) downstream.add(d.asText());
            String exp = row.path("expires_at").asText(null);
            String verified = row.path("last_verified_at").asText(null);
            out.add(new Credential(row.path("credential_id").asText(), row.path("channel_code").asText(""),
                    CredentialType.parse(row.path("credential_type").asText("")),
                    exp == null ? null : Times.parse(exp), row.path("owner_dept").asText(""),
                    row.path("owner").asText(""), row.path("rotation_entry").asText(""),
                    verified == null ? null : Times.parse(verified),
                    row.path("fingerprint").asText(null), null, channels, downstream));
        }
        plaintextWarnings += warnings;
        if (cacheDao != null) for (Credential c : out) cacheDao.upsert(c);
        return new LedgerResult(out, plaintextWarnings, false, null);
    }

    /** 台账不可达：只读本地缓存快照，且禁止写回。 */
    private LedgerResult fallback(String errorCode) {
        List<Credential> cached = cacheDao == null ? List.of() : cacheDao.all();
        return new LedgerResult(cached, plaintextWarnings, true, errorCode);
    }

    public int plaintextWarnings() { return plaintextWarnings; }
    public String baseUrl() { return baseUrl; }
}
