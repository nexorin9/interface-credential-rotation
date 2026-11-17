package com.med.icr.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.med.icr.domain.IdempotencyKey;
import com.med.icr.domain.Ticket;
import com.med.icr.domain.TicketStateMachine;
import com.med.icr.util.Json;
import com.med.icr.util.Times;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/** 处置单幂等写：人工确认之后才外发；同一故障窗口不会重复派单。 */
public final class TicketClient {
    public static final String PATH = "/api/v1/credential-tickets";

    public record WriteResult(boolean success, String ticketNo, String errorCode, boolean retryable,
                              int attempts, Ticket localTicket, String idempotencyKey) {}

    private final String baseUrl;
    private final String token;
    private final HttpClient http;
    private final Duration timeout;
    private final long backoffMillis;

    public TicketClient(String baseUrl, String token) {
        this(baseUrl, token, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                Duration.ofSeconds(8), 20L);
    }

    public TicketClient(String baseUrl, String token, HttpClient http, Duration timeout, long backoffMillis) {
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("CREDENTIAL_BASE_URL 未设置");
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
        this.http = http;
        this.timeout = timeout;
        this.backoffMillis = backoffMillis;
    }

    public String buildBody(Ticket ticket) {
        ObjectNode body = Json.MAPPER.createObjectNode();
        body.put("credential_id", ticket.credentialId());
        body.put("root_cause", ticket.rootCause());
        ArrayNode refs = body.putArray("evidence_refs");
        ticket.evidenceRefs().forEach(refs::add);
        body.put("due_at", ticket.dueAt() == null ? null : Times.format(ticket.dueAt()));
        body.put("state", ticket.state().name());
        return body.toString();
    }

    public WriteResult dispatch(Ticket ticket, Instant windowStart) {
        String key = ticket.idempotencyKey() == null
                ? IdempotencyKey.compute(ticket.credentialId(), windowStart)
                : ticket.idempotencyKey();
        String body = buildBody(ticket);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + PATH))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("authorization", "bearer " + (token == null ? "" : token))
                .header("idempotency-key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        int attempts = 0;
        String lastError = null;
        for (int retry = 0; retry <= 2; retry++) {
            attempts++;
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    String ticketNo = parseTicketNo(response.body(), key);
                    Ticket local = ticket;
                    if (ticket.state() == com.med.icr.domain.TicketState.PENDING) {
                        TicketStateMachine.transition(ticket.state(), com.med.icr.domain.TicketState.DISPATCHED);
                        local = ticket.withState(com.med.icr.domain.TicketState.DISPATCHED);
                    }
                    local = local.withTicketNo(ticketNo);
                    return new WriteResult(true, ticketNo, null, false, attempts, local, key);
                }
                lastError = "HTTP_" + status;
                if (status < 500 && status != 429) break;
            } catch (IOException e) {
                lastError = AdapterException.UNREACHABLE;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastError = AdapterException.UNREACHABLE;
                break;
            }
            if (retry < 2) backoff(retry);
        }
        // 写回失败：本地票据状态保持未派发，不静默改成已派发
        return new WriteResult(false, null, lastError == null ? AdapterException.WRITE_FAILED : lastError,
                true, attempts, ticket, key);
    }

    private String parseTicketNo(String body, String key) {
        try {
            JsonNode root = Json.MAPPER.readTree(body);
            String no = root.path("ticket_no").asText(null);
            return no == null ? "TKT-" + key.substring(0, 12) : no;
        } catch (IOException e) {
            return "TKT-" + key.substring(0, 12);
        }
    }

    private void backoff(int attempt) {
        try { Thread.sleep(Math.min(200L, backoffMillis * (1L << attempt))); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public String baseUrl() { return baseUrl; }
}
