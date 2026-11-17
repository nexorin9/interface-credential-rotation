package com.med.icr;

import com.med.icr.ingest.EsbClientException;
import com.med.icr.ingest.EsbFailureClient;
import com.med.icr.ingest.FailureEventParser;
import com.med.icr.support.Fixtures;
import com.med.icr.support.StubHttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** T12 / T18：正式事件流客户端协议、错误语义、分页与解析器容错。 */
final class IngestSuites {}

class EsbFailureClientTest {
    @Test void realGetUsesOfficialPathQueryAndAuthHeader() throws Exception {
        String event = Fixtures.eventJson("E-1", "CH-01", "cert_expired", Fixtures.T0, "fp1");
        try (StubHttpServer stub = new StubHttpServer().setDefault(200, "[" + event + "]")) {
            EsbFailureClient.Page page = new EsbFailureClient(stub.url(), "esb-token")
                    .fetchPage("2026-11-09T00:00:00Z", 1000);
            assertEquals(1, page.events().size());
            assertEquals("/api/v1/interface-failures", EsbFailureClient.PATH);
            StubHttpServer.Request req = stub.lastRequest();
            assertEquals("GET", req.method());
            assertTrue(req.uri().contains(EsbFailureClient.PATH));
            assertTrue(req.uri().contains("since="));
            assertTrue(req.uri().contains("limit=200"), "limit 必须收敛到 ≤200");
            assertTrue(req.headers().toLowerCase().contains("authorization"));
            assertTrue(req.headers().contains("esb-token"));
        }
    }

    @Test void paginationFollowsNextSinceUntilEmptyPage() throws Exception {
        String event = Fixtures.eventJson("E-1", "CH-01", "cert_expired", Fixtures.T0, "fp1");
        try (StubHttpServer stub = new StubHttpServer()) {
            stub.enqueue(200, "{\"events\":[" + event + "],\"next_since\":\"2026-11-09T08:10:00Z\"}");
            stub.enqueue(200, "{\"events\":[],\"next_since\":null}");
            List<?> all = new EsbFailureClient(stub.url(), "token").fetch("2026-11-09T00:00:00Z");
            assertEquals(1, all.size());
            assertEquals(2, stub.requestCount());
            assertTrue(stub.requests().get(1).uri().contains("2026-11-09T08"));
        }
    }

    @Test void unauthorizedReturnsAuthCode() throws Exception {
        try (StubHttpServer stub = new StubHttpServer().setDefault(401, "{}")) {
            EsbClientException ex = assertThrows(EsbClientException.class,
                    () -> new EsbFailureClient(stub.url(), "bad").fetchPage("2026-11-09T00:00:00Z", 10));
            assertEquals(EsbClientException.AUTH_FAILED, ex.code);
            assertEquals(401, ex.status);
        }
    }

    @Test void notFoundAndServerErrorHaveDistinctCodes() throws Exception {
        try (StubHttpServer stub = new StubHttpServer()) {
            stub.enqueue(404, "{}");
            stub.enqueue(500, "{}");
            EsbFailureClient client = new EsbFailureClient(stub.url(), "token");
            assertEquals(EsbClientException.NOT_FOUND,
                    assertThrows(EsbClientException.class, () -> client.fetchPage("x", 1)).code);
            assertEquals(EsbClientException.SERVER_ERROR,
                    assertThrows(EsbClientException.class, () -> client.fetchPage("x", 1)).code);
        }
    }

    @Test void malformedJsonIsBadStructureNotSilentEmpty() throws Exception {
        try (StubHttpServer stub = new StubHttpServer()) {
            stub.enqueue(200, "not-json");
            stub.enqueue(200, "{\"unexpected\":true}");
            EsbFailureClient client = new EsbFailureClient(stub.url(), "token");
            assertEquals(EsbClientException.BAD_STRUCTURE,
                    assertThrows(EsbClientException.class, () -> client.fetchPage("x", 1)).code);
            assertEquals(EsbClientException.BAD_STRUCTURE,
                    assertThrows(EsbClientException.class, () -> client.fetchPage("x", 1)).code);
        }
    }

    @Test void noHardcodedLocalhostBaseUrlInProductionCode() throws IOException {
        List<String> hits = new java.util.ArrayList<>();
        try (Stream<Path> paths = Files.walk(Path.of("src/main"))) {
            for (Path p : paths.filter(Files::isRegularFile).toList()) {
                String text = Files.readString(p);
                if (text.contains("localhost") || text.contains("127.0.0.1")) hits.add(p.toString());
            }
        }
        assertTrue(hits.isEmpty(), "src/main 下不得硬编码本地默认 base URL: " + hits);
    }
}

class FailureEventParserTest {
    @Test void badLinesGoToRejectedWithoutBreakingTheBatch() {
        FailureEventParser.ParseResult r = new FailureEventParser().parseLines(List.of(
                Fixtures.eventJson("E-1", "CH-01", "cert_expired", Fixtures.T0, "fp1"),
                "not-an-object",
                "{\"channel_code\":\"CH-01\"}"));
        assertEquals(1, r.events().size());
        assertEquals(2, r.rejected().size());
        assertEquals("TRACE-E-1", r.events().get(0).evidenceRef());
        assertTrue(r.rejected().get(1).reason().contains("缺少必需字段"));
    }

    @Test void missingFieldsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new FailureEventParser().parseLine("{}"));
        assertThrows(IllegalArgumentException.class, () -> new FailureEventParser().parseLine(""));
    }
}
