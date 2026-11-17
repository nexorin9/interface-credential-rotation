package com.med.icr.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.med.icr.config.AppConfig;
import com.med.icr.domain.FailureEvent;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** ESB 失败事件流真实 HTTP 客户端：GET /api/v1/interface-failures?since=&limit=，分页取到空页。 */
public final class EsbFailureClient {
    public static final String PATH = "/api/v1/interface-failures";
    public static final int MAX_LIMIT = 200;

    private final String baseUrl;
    private final String token;
    private final HttpClient http;
    private final Duration timeout;
    private final FailureEventParser parser = new FailureEventParser();

    public EsbFailureClient(AppConfig config) {
        this(config.require(AppConfig.ESB_BASE_URL), config.require(AppConfig.ESB_TOKEN),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), Duration.ofSeconds(8));
    }

    public EsbFailureClient(String baseUrl, String token) {
        this(baseUrl, token, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                Duration.ofSeconds(8));
    }

    public EsbFailureClient(String baseUrl, String token, HttpClient http, Duration timeout) {
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("ESB_BASE_URL 未设置");
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
        this.http = http;
        this.timeout = timeout;
    }

    public record Page(List<FailureEvent> events, String nextSince) {}

    public Page fetchPage(String since, int limit) {
        int capped = Math.min(Math.max(limit, 1), MAX_LIMIT);
        String url = baseUrl + PATH + "?since=" + java.net.URLEncoder.encode(since, java.nio.charset.StandardCharsets.UTF_8)
                + "&limit=" + capped;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("authorization", "bearer " + (token == null ? "" : token))
                .GET().build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new EsbClientException(EsbClientException.UNREACHABLE, 0, "网络不可达 / 超时: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EsbClientException(EsbClientException.UNREACHABLE, 0, "请求被中断");
        }
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new EsbClientException(EsbClientException.AUTH_FAILED, status, "鉴权失败，检查 ESB_TOKEN");
        }
        if (status == 404) {
            throw new EsbClientException(EsbClientException.NOT_FOUND, status, "端点不存在: " + PATH);
        }
        if (status >= 500) {
            throw new EsbClientException(EsbClientException.SERVER_ERROR, status, "服务端错误");
        }
        if (status < 200 || status >= 300) {
            throw new EsbClientException(EsbClientException.SERVER_ERROR, status, "非预期状态码");
        }
        JsonNode root;
        try {
            root = Json.MAPPER.readTree(response.body());
        } catch (IOException e) {
            throw new EsbClientException(EsbClientException.BAD_STRUCTURE, status, "响应非法 JSON");
        }
        JsonNode array;
        String nextSince = null;
        if (root == null) {
            throw new EsbClientException(EsbClientException.BAD_STRUCTURE, status, "响应为空");
        }
        if (root.isArray()) {
            array = root;
        } else if (root.isObject() && root.has("events") && root.get("events").isArray()) {
            array = root.get("events");
            if (root.hasNonNull("next_since")) nextSince = root.get("next_since").asText();
        } else {
            throw new EsbClientException(EsbClientException.BAD_STRUCTURE, status, "响应结构不符");
        }
        List<String> lines = new ArrayList<>();
        for (JsonNode n : array) lines.add(n.toString());
        FailureEventParser.ParseResult parsed = parser.parseLines(lines);
        if (!parsed.rejected().isEmpty()) {
            throw new EsbClientException(EsbClientException.BAD_STRUCTURE, status,
                    "响应含 " + parsed.rejected().size() + " 条结构不符事件");
        }
        List<FailureEvent> events = parsed.events();
        if (nextSince == null && events.size() == capped) {
            Instant latest = events.stream().map(FailureEvent::occurredAt).max(Instant::compareTo).orElse(Instant.now());
            nextSince = Times.format(latest.plusSeconds(1));
        }
        return new Page(events, nextSince);
    }

    /** 按 next_since 或 limit 截断继续取，直到返回空页。 */
    public List<FailureEvent> fetch(String since) {
        List<FailureEvent> all = new ArrayList<>();
        String cursor = since;
        Set<String> seen = new HashSet<>();
        seen.add(cursor);
        for (int round = 0; round < 50; round++) {
            Page page = fetchPage(cursor, MAX_LIMIT);
            all.addAll(page.events());
            String next = page.nextSince();
            if (page.events().isEmpty() || next == null || next.isBlank() || !seen.add(next)) break;
            cursor = next;
        }
        return all;
    }

    public String baseUrl() { return baseUrl; }
}
