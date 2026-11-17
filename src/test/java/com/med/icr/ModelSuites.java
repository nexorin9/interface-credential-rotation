package com.med.icr;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.med.icr.attribution.ModelAttributionPipeline;
import com.med.icr.attribution.AttributionPipeline;
import com.med.icr.domain.FailureEvent;
import com.med.icr.model.Gateways;
import com.med.icr.model.ModelAudit;
import com.med.icr.model.ModelConfig;
import com.med.icr.model.ModelGateway;
import com.med.icr.model.ModelRequest;
import com.med.icr.model.ModelResponse;
import com.med.icr.model.ModelUnavailableException;
import com.med.icr.model.OpenAiCompatibleGateway;
import com.med.icr.store.Database;
import com.med.icr.store.ModelCallDao;
import com.med.icr.support.Fixtures;
import com.med.icr.support.StubHttpServer;
import com.med.icr.util.Json;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** T25–T27 / T30：单一网关、OpenAI-compatible 协议、超时重试降级语义、模型调用审计。 */
final class ModelSuites {}

class ModelGatewayTest {
    @Test void gatewayIsInjectableAndReplaceable() {
        ModelGateway stub = request -> new ModelResponse("r", "CRED-0007", 0.7,
                List.of("TRACE-E-1"), "a", "CHANNEL_CRITICAL");
        Gateways.inject(stub);
        assertSame(stub, Gateways.get());
        Gateways.reset();
    }

    @Test void businessLayerUsesInjectedGatewayWithoutVendorSdk() {
        ModelGateway stub = request -> new ModelResponse("r", "CRED-0007", 0.7,
                request.events().stream().map(ModelRequest.EventSummary::evidenceRef).toList(),
                "a", "CHANNEL_CRITICAL");
        Gateways.inject(stub);
        try {
            List<FailureEvent> events = List.of(Fixtures.event("E-1", "CH-01", "cert_expired", Fixtures.T0, "fp"));
            AttributionPipeline.Result r = new ModelAttributionPipeline(Gateways.get())
                    .run(AttributionPipeline.Context.of(events, List.of(), true));
            assertTrue(r.compliant());
            assertTrue(r.card().modelUsed());
        } finally {
            Gateways.reset();
        }
    }

    @Test void businessPackagesHaveNoVendorNames() throws IOException {
        List<String> hits = new ArrayList<>();
        for (String pkg : List.of("attribution", "pipeline", "ui", "cli")) {
            Path dir = Path.of("src/main/java/com/med/icr", pkg);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> paths = Files.walk(dir)) {
                for (Path p : paths.filter(Files::isRegularFile).toList()) {
                    String text = Files.readString(p).toLowerCase();
                    if (text.contains("openai") || text.contains("anthropic")) hits.add(p.toString());
                }
            }
        }
        assertTrue(hits.isEmpty(), "业务层不得耦合厂商名或 SDK: " + hits);
    }
}

class OpenAiCompatibleGatewayTest {
    private static final String STRUCTURED = "{\"root_cause\":\"证书过期\",\"credential_id\":\"CRED-0007\","
            + "\"confidence\":0.81,\"evidence_refs\":[\"TRACE-E-1\"],"
            + "\"suggested_action\":\"换证\",\"severity\":\"CHANNEL_CRITICAL\"}";

    private static ModelRequest request() {
        return new ModelRequest(
                List.of(new ModelRequest.EventSummary("E-1", "CH-01", "cert_expired",
                        "证书已过期", "TRACE-E-1", "2026-11-09T08:00:00Z")),
                List.of(new ModelRequest.CredentialSummary("CRED-0007", "CH-01", "cert", "fp-7", null)),
                "仅依据脱敏摘要推断");
    }

    private static String openAiResponse() {
        ObjectNode root = Json.MAPPER.createObjectNode();
        ArrayNode choices = root.putArray("choices");
        choices.addObject().putObject("message").put("content", STRUCTURED);
        return root.toString();
    }

    @Test void requestBodyCarriesJsonSchemaWithSpecFields() {
        OpenAiCompatibleGateway gw = new OpenAiCompatibleGateway(
                ModelConfig.of("https://model.intra.example", "key", "icr-model", 5000, 2, 200));
        String body = gw.buildRequestBody(request());
        assertTrue(body.contains("response_format"));
        assertTrue(body.contains("json_schema"));
        for (String field : List.of("root_cause", "credential_id", "confidence",
                "evidence_refs", "suggested_action", "severity")) {
            assertTrue(body.contains("\"" + field + "\""), "缺字段 " + field);
        }
        assertTrue(body.contains("icr_attribution"));
        assertTrue(body.contains("\"strict\":true"));
        assertEquals("/v1/chat/completions", OpenAiCompatibleGateway.PATH);
    }

    @Test void validResponseParsesThroughGateway() throws Exception {
        try (StubHttpServer stub = new StubHttpServer().setDefault(200, openAiResponse())) {
            OpenAiCompatibleGateway gw = new OpenAiCompatibleGateway(
                    ModelConfig.of(stub.url(), "key", "icr-model", 5000, 2, 200),
                    java.net.http.HttpClient.newHttpClient(), 1L);
            ModelResponse r = gw.infer(request());
            assertEquals("CRED-0007", r.credentialId());
            assertEquals(0.81, r.confidence());
            StubHttpServer.Request req = stub.lastRequest();
            assertEquals("POST", req.method());
            assertTrue(req.uri().contains("/v1/chat/completions"));
            assertTrue(req.body().contains("response_format"));
        }
    }

    @Test void unusableConfigDoesNotSilentlyUseModel() {
        ModelConfig cfg = ModelConfig.of("https://model.intra.example", "", "", 5000, 2, 200);
        assertFalse(cfg.usable());
        assertEquals("model.intra.example", cfg.host());
    }
}

class ModelErrorSemanticsTest {
    private static ModelRequest request() {
        return new ModelRequest(List.of(new ModelRequest.EventSummary("E-1", "CH-01", "cert_expired",
                "err", "TRACE-E-1", "2026-11-09T08:00:00Z")), List.of(), "hint");
    }

    @Test void fiveHundredRetriesTwiceThenGivesUp() throws Exception {
        try (StubHttpServer stub = new StubHttpServer().setDefault(500, "{}")) {
            OpenAiCompatibleGateway gw = new OpenAiCompatibleGateway(
                    ModelConfig.of(stub.url(), "key", "m", 5000, 2, 200),
                    java.net.http.HttpClient.newHttpClient(), 1L);
            ModelUnavailableException ex = assertThrows(ModelUnavailableException.class, () -> gw.infer(request()));
            assertEquals(ModelUnavailableException.SERVER_ERROR, ex.code);
            assertEquals(2, gw.lastRetryCount());
            assertEquals(3, stub.requestCount());
        }
    }

    @Test void fourHundredDegradesImmediatelyWithoutRetry() throws Exception {
        try (StubHttpServer stub = new StubHttpServer().setDefault(400, "{}")) {
            OpenAiCompatibleGateway gw = new OpenAiCompatibleGateway(
                    ModelConfig.of(stub.url(), "key", "m", 5000, 2, 200),
                    java.net.http.HttpClient.newHttpClient(), 1L);
            ModelUnavailableException ex = assertThrows(ModelUnavailableException.class, () -> gw.infer(request()));
            assertEquals(ModelUnavailableException.CONFIG_ERROR, ex.code);
            assertEquals(0, gw.lastRetryCount());
            assertEquals(1, stub.requestCount());
        }
    }

    @Test void rateLimitRetriesThenSucceeds() throws Exception {
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.putArray("choices").addObject().putObject("message").put("content",
                "{\"root_cause\":\"r\",\"credential_id\":\"CRED-0007\",\"confidence\":0.5,"
                        + "\"evidence_refs\":[\"TRACE-E-1\"],\"suggested_action\":\"a\","
                        + "\"severity\":\"EVENT_NON_CRITICAL\"}");
        try (StubHttpServer stub = new StubHttpServer()) {
            stub.enqueue(429, "{}");
            stub.enqueue(200, root.toString());
            OpenAiCompatibleGateway gw = new OpenAiCompatibleGateway(
                    ModelConfig.of(stub.url(), "key", "m", 5000, 2, 200),
                    java.net.http.HttpClient.newHttpClient(), 1L);
            assertEquals("CRED-0007", gw.infer(request()).credentialId());
            assertEquals(1, gw.lastRetryCount());
            assertEquals(2, stub.requestCount());
        }
    }
}

class ModelAuditTest {
    @Test void auditRowHasHostAndDegradationFlagButNoRawError() {
        try (Database db = Database.inMemory()) {
            ModelCallDao dao = new ModelCallDao(db);
            ModelConfig cfg = ModelConfig.of("https://model.intra.example/v1", "key", "icr-model", 5000, 2, 200);
            ModelAudit audit = new ModelAudit(dao, cfg);
            ModelRequest req = new ModelRequest(List.of(new ModelRequest.EventSummary("E-1", "CH-01",
                    "cert_expired", "masked", "TRACE-E-1", "2026-11-09T08:00:00Z")), List.of(), "hint");
            audit.record(req, Fixtures.T0, Fixtures.T0.plusSeconds(300), 137L, true,
                    "MODEL_UNREACHABLE", "token=RAW-TOKEN-ABCDEF 导致结论降级", "REQ-1");
            assertEquals(1, dao.count());
            ModelCallDao.Entry e = dao.all().get(0);
            assertEquals("model.intra.example", e.baseUrlHost());
            assertTrue(e.degraded());
            assertEquals(1, e.inputEventCount());
            assertFalse(dao.containsRaw("RAW-TOKEN-ABCDEF"));
            Instant now = Instant.now();
            assertEquals(1, audit.export(now.minusSeconds(60), now.plusSeconds(60)).size());
        }
    }
}
