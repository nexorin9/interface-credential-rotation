package com.med.icr.adapter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/** 真实 HTTP 探测：GET /health 读状态码；网络异常统一收敛为「未通过」，不抛裸异常。 */
public final class HttpProbe {
    private final HttpClient http;
    private final Duration timeout;

    public HttpProbe() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(), Duration.ofSeconds(5));
    }

    public HttpProbe(HttpClient http, Duration timeout) {
        this.http = http;
        this.timeout = timeout;
    }

    public ProbeResult probe(String baseUrl) {
        String endpoint = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String url = endpoint.endsWith("/health") ? endpoint : endpoint + "/health";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) return ProbeResult.passed(-1, Instant.now(), url);
            if (status == 401 || status == 403) return ProbeResult.passedWithAuthWarning(Instant.now(), url);
            return ProbeResult.failed("HTTP_STATUS", "HTTP_" + status, Instant.now(), url,
                    "非 2xx 状态码");
        } catch (IOException e) {
            return ProbeResult.failed("NETWORK_UNREACHABLE", "HTTP_UNREACHABLE", Instant.now(), url,
                    e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ProbeResult.failed("NETWORK_UNREACHABLE", "HTTP_INTERRUPTED", Instant.now(), url, "请求被中断");
        } catch (RuntimeException e) {
            return ProbeResult.failed("NETWORK_UNREACHABLE", "HTTP_UNREACHABLE", Instant.now(), url,
                    e.getClass().getSimpleName());
        }
    }
}
