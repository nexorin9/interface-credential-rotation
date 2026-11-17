package com.med.icr.adapter;

import com.fasterxml.jackson.databind.node.ObjectNode;
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

/** 自检结论写回：只有真实探测成功才允许 verified_at 非空；未通过时写 FAILED 且 verified_at 为空。 */
public final class VerificationClient {
    public static final String PATH = "/api/v1/credential-verifications";

    public record Result(boolean written, boolean probePassed, String errorCode, boolean conclusionNotWrittenBack) {}

    private final String baseUrl;
    private final String token;
    private final HttpClient http;
    private final Duration timeout;
    private final CredentialDao cacheDao;

    public VerificationClient(String baseUrl, String token) { this(baseUrl, token, null); }

    public VerificationClient(String baseUrl, String token, CredentialDao cacheDao) {
        this(baseUrl, token, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                Duration.ofSeconds(8), cacheDao);
    }

    public VerificationClient(String baseUrl, String token, HttpClient http, Duration timeout, CredentialDao cacheDao) {
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("CREDENTIAL_BASE_URL 未设置");
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
        this.http = http;
        this.timeout = timeout;
        this.cacheDao = cacheDao;
    }

    public String buildBody(String credentialId, String channelCode, ProbeResult probe) {
        ObjectNode body = Json.MAPPER.createObjectNode();
        body.put("credential_id", credentialId);
        body.put("channel_code", channelCode);
        boolean trulyPassed = probe.passed() && "PROBE_OK".equals(probe.code());
        body.put("probe_result", trulyPassed ? ProbeResult.PASSED : ProbeResult.FAILED);
        if (trulyPassed) body.put("verified_at", Times.format(probe.probedAt()));
        else body.putNull("verified_at");
        return body.toString();
    }

    public Result write(String credentialId, String channelCode, ProbeResult probe) {
        String payload = buildBody(credentialId, channelCode, probe);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + PATH))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("authorization", "bearer " + (token == null ? "" : token))
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                // 写回失败不回滚已探测成功的本地结论，但界面须标「结论未回写」
                return new Result(false, probe.passed(), "HTTP_" + response.statusCode(), true);
            }
            if (probe.passed() && cacheDao != null) {
                cacheDao.all().stream()
                        .filter(c -> c.credentialId().equals(credentialId)).findFirst()
                        .ifPresent(c -> cacheDao.upsert(c.withLastVerifiedAt(probe.probedAt())));
            }
            return new Result(true, probe.passed(), null, false);
        } catch (IOException e) {
            return new Result(false, probe.passed(), AdapterException.UNREACHABLE, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, probe.passed(), AdapterException.UNREACHABLE, true);
        }
    }

    public String baseUrl() { return baseUrl; }
}
