package com.med.icr;

import com.fasterxml.jackson.databind.JsonNode;
import com.med.icr.adapter.CredentialLedgerClient;
import com.med.icr.adapter.HttpProbe;
import com.med.icr.adapter.ProbeResult;
import com.med.icr.adapter.RotationOrchestrator;
import com.med.icr.adapter.TicketClient;
import com.med.icr.adapter.TlsProbe;
import com.med.icr.adapter.VerificationClient;
import com.med.icr.domain.CredentialState;
import com.med.icr.domain.IdempotencyKey;
import com.med.icr.domain.Ticket;
import com.med.icr.domain.TicketState;
import com.med.icr.ingest.EsbFailureClient;
import com.med.icr.support.Fixtures;
import com.med.icr.support.StubHttpServer;
import com.med.icr.support.StubX509Certificate;
import com.med.icr.util.Json;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateExpiredException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** T35–T41：台账读、幂等写、结论回写、TLS / HTTP 探测、写回不变量、四端点契约。 */
final class AdapterSuites {}

class CredentialLedgerClientTest {
    private static final String ROW = "{\"credential_id\":\"CRED-0007\",\"channel_code\":\"CH-01\","
            + "\"credential_type\":\"cert\",\"expires_at\":\"2026-10-15T00:00:00Z\","
            + "\"fingerprint\":\"fp-7\",\"channel_codes\":[\"CH-01\"],"
            + "\"downstream_systems\":[\"医保平台\"],\"owner_dept\":\"信息科\","
            + "\"secret\":\"RAW-SECRET-VALUE\"}";

    @Test void plaintextFieldIsRejectedAndWarned() throws Exception {
        try (StubHttpServer stub = new StubHttpServer().setDefault(200, "[" + ROW + "]")) {
            CredentialLedgerClient client = new CredentialLedgerClient(stub.url(), "cred-token");
            CredentialLedgerClient.LedgerResult r = client.fetchWithWarnings();
            assertEquals(1, r.credentials().size());
            assertEquals(1, r.plaintextWarnings(), "含明文字段的响应必须记告警");
            assertFalse(r.readOnlyFallback());
            assertEquals("fp-7", r.credentials().get(0).fingerprint());
            assertEquals(CredentialLedgerClient.PATH, "/api/v1/integration-credentials");
            assertTrue(stub.lastRequest().uri().contains(CredentialLedgerClient.PATH));
            assertEquals("GET", stub.lastRequest().method());
        }
    }

    @Test void cleanResponseHasNoWarnings() throws Exception {
        try (StubHttpServer stub = new StubHttpServer().setDefault(200, "[" + ROW.replace(",\"secret\":\"RAW-SECRET-VALUE\"", "") + "]")) {
            assertEquals(0, new CredentialLedgerClient(stub.url(), "t").fetchWithWarnings().plaintextWarnings());
        }
    }

    @Test void unreachableLedgerFallsBackToReadOnly() {
        CredentialLedgerClient.LedgerResult r = new CredentialLedgerClient("http://127.0.0.1:1", "t")
                .fetchWithWarnings();
        assertTrue(r.readOnlyFallback());
        assertTrue(r.credentials().isEmpty());
    }
}

class TicketClientTest {
    @Test void successfulWriteAdvancesLocalTicketAndSendsIdempotencyKey() throws Exception {
        try (StubHttpServer stub = new StubHttpServer().setDefault(200, "{\"ticket_no\":\"TKT-0001\"}")) {
            TicketClient client = new TicketClient(stub.url(), "token");
            Ticket draft = Fixtures.ticket("CRED-0007", IdempotencyKey.compute("CRED-0007", Fixtures.T0));
            TicketClient.WriteResult r = client.dispatch(draft, Fixtures.T0);
            assertTrue(r.success());
            assertEquals("TKT-0001", r.ticketNo());
            assertEquals(TicketState.DISPATCHED, r.localTicket().state());
            assertEquals(IdempotencyKey.compute("CRED-0007", Fixtures.T0), r.idempotencyKey());
            assertTrue(stub.lastRequest().headers().toLowerCase().contains("idempotency-key"));
            assertTrue(stub.lastRequest().body().contains("credential_id"));
            assertEquals(TicketClient.PATH, "/api/v1/credential-tickets");
        }
    }

    @Test void retryExhaustionKeepsTicketPending() throws Exception {
        try (StubHttpServer stub = new StubHttpServer().setDefault(500, "{}")) {
            TicketClient client = new TicketClient(stub.url(), "token");
            Ticket draft = Fixtures.ticket("CRED-0007", null);
            TicketClient.WriteResult r = client.dispatch(draft, Fixtures.T0);
            assertFalse(r.success());
            assertEquals(3, r.attempts());
            assertEquals(TicketState.PENDING, r.localTicket().state(), "写回失败不静默改成已派发");
            assertNull(r.localTicket().ticketNo());
        }
    }

    @Test void sameWindowAlwaysUsesSameKey() throws Exception {
        try (StubHttpServer stub = new StubHttpServer().setDefault(200, "{\"ticket_no\":\"TKT-1\"}")) {
            TicketClient client = new TicketClient(stub.url(), "token");
            Ticket t = Fixtures.ticket("CRED-0007", null);
            String k1 = client.dispatch(t, Fixtures.T0).idempotencyKey();
            String k2 = client.dispatch(t, Fixtures.T0).idempotencyKey();
            assertEquals(k1, k2);
        }
    }
}

class VerificationClientTest {
    @Test void failedProbeHasNoVerifiedAt() throws Exception {
        VerificationClient client = new VerificationClient("https://cred.intra.example", "token");
        ProbeResult passed = ProbeResult.passed(30, Fixtures.T0, "https://x/health");
        JsonNode ok = Json.MAPPER.readTree(client.buildBody("CRED-0007", "CH-01", passed));
        assertEquals(ProbeResult.PASSED, ok.get("probe_result").asText());
        assertFalse(ok.get("verified_at").isNull());
        ProbeResult failed = ProbeResult.failed("NETWORK_UNREACHABLE", "HTTP_UNREACHABLE",
                Fixtures.T0, "https://x/health", "timeout");
        JsonNode bad = Json.MAPPER.readTree(client.buildBody("CRED-0007", "CH-01", failed));
        assertEquals(ProbeResult.FAILED, bad.get("probe_result").asText());
        assertTrue(bad.get("verified_at").isNull(), "未通过场景 verified_at 必须为空");
    }

    @Test void writeUsesOfficialPathAndReportsConclusion() throws Exception {
        try (StubHttpServer stub = new StubHttpServer().setDefault(200, "{}")) {
            VerificationClient client = new VerificationClient(stub.url(), "token");
            VerificationClient.Result r = client.write("CRED-0007", "CH-01",
                    ProbeResult.failed("CERT_EXPIRED", "CERT_EXPIRED", Fixtures.T0, "x", "过期"));
            assertTrue(r.written());
            assertFalse(r.probePassed());
            assertFalse(r.conclusionNotWrittenBack());
            assertTrue(stub.lastRequest().uri().contains(VerificationClient.PATH));
            assertEquals("POST", stub.lastRequest().method());
        }
    }
}

class TlsProbeTest {
    @Test void expiredAndHostnameMismatchGetDifferentCodes() {
        Instant now = Fixtures.T0;
        StubX509Certificate expired = new StubX509Certificate(
                Date.from(now.minusSeconds(10L * 86400)), Date.from(now.minusSeconds(86400L)),
                "his.intra.example", null);
        ProbeResult a = TlsProbe.evaluate(expired, "his.intra.example", now, "his:443");
        assertEquals(TlsProbe.CERT_EXPIRED, a.code());
        assertFalse(a.passed());
        StubX509Certificate mismatch = StubX509Certificate.validFor("other.invalid", now);
        ProbeResult b = TlsProbe.evaluate(mismatch, "his.intra.example", now, "his:443");
        assertEquals(TlsProbe.HOSTNAME_MISMATCH, b.code());
        assertNotEquals(a.code(), b.code());
    }

    @Test void validCertificateWithCnOrSanPassesWithDaysLeft() {
        Instant now = Fixtures.T0;
        ProbeResult cn = TlsProbe.evaluate(StubX509Certificate.validFor("his.intra.example", now),
                "his.intra.example", now, "his:443");
        assertTrue(cn.passed());
        assertEquals("PROBE_OK", cn.code());
        assertEquals(30, cn.certificateDaysLeft());
        assertTrue(TlsProbe.evaluate(StubX509Certificate.withSans(now, "his.intra.example"),
                "his.intra.example", now, "his:443").passed());
    }

    @Test void handshakeFailuresClassifyToDistinctCodes() {
        assertEquals(TlsProbe.CERT_EXPIRED, TlsProbe.classify(new CertificateExpiredException("expired")));
        assertEquals(TlsProbe.HOSTNAME_MISMATCH,
                TlsProbe.classify(new SSLHandshakeException("No subject alternative names present")));
        assertEquals(TlsProbe.TRUST_CHAIN_UNTRUSTED,
                TlsProbe.classify(new SSLHandshakeException("unable to find valid certification path")));
        assertEquals(TlsProbe.TIMEOUT, TlsProbe.classify(new java.net.SocketTimeoutException("timed out")));
    }

    @Test void refusedConnectionIsAFailedProbeNotAnException() {
        ProbeResult r = new TlsProbe(150).probe("127.0.0.1", 1);
        assertFalse(r.passed());
        assertNotNull(r.failureClass());
    }
}

class HttpProbeTest {
    @Test void twoHundredIsPassedAndFiveHundredIsFailed() throws Exception {
        try (StubHttpServer stub = new StubHttpServer()) {
            stub.enqueue(200, "ok");
            stub.enqueue(500, "error");
            HttpProbe probe = new HttpProbe();
            assertTrue(probe.probe(stub.url()).passed());
            assertFalse(probe.probe(stub.url()).passed());
            assertTrue(stub.requests().get(0).uri().endsWith("/health"));
        }
    }

    @Test void connectionTimeoutConvergesToFailedWithoutThrowing() throws Exception {
        HttpProbe probe = new HttpProbe();
        ProbeResult r = probe.probe("http://127.0.0.1:1");
        assertFalse(r.passed());
        VerificationClient client = new VerificationClient("https://cred.intra.example", "token");
        JsonNode body = Json.MAPPER.readTree(client.buildBody("CRED-0007", "CH-01", r));
        assertEquals(ProbeResult.FAILED, body.get("probe_result").asText());
        assertTrue(body.get("verified_at").isNull());
    }
}

class WriteBackInvariantTest {
    @Test void probeFailureLeavesCredentialAtRotated() {
        var rotated = Fixtures.credential("CRED-0007", "fp-7", Fixtures.T0.plusSeconds(86400L))
                .withState(CredentialState.ROTATED);
        Ticket dispatched = Fixtures.ticket("CRED-0007", "key").withState(TicketState.DISPATCHED);
        ProbeResult failed = ProbeResult.failed("CERT_EXPIRED", "CERT_EXPIRED", Fixtures.T0, "x", "证书过期");
        RotationOrchestrator orchestrator = new RotationOrchestrator();
        RotationOrchestrator.Outcome o = orchestrator.complete(rotated, dispatched, failed, false, false);
        assertFalse(o.verified());
        assertFalse(o.conclusionWrittenBack());
        assertEquals(CredentialState.ROTATED, o.credential().state());
        assertEquals(TicketState.DISPATCHED, o.ticket().state());
        assertTrue(orchestrator.staysRotatedOnProbeFailure(rotated, o.credential()));
    }

    @Test void onlySuccessfulProbePlusBothWritesCanCloseTheLoop() {
        var rotated = Fixtures.credential("CRED-0007", "fp-7", Fixtures.T0.plusSeconds(86400L))
                .withState(CredentialState.ROTATED);
        Ticket dispatched = Fixtures.ticket("CRED-0007", "key").withState(TicketState.DISPATCHED);
        RotationOrchestrator orchestrator = new RotationOrchestrator();
        assertFalse(orchestrator.complete(rotated, dispatched,
                ProbeResult.passed(30, Fixtures.T0, "x"), false, true).verified());
        assertFalse(orchestrator.complete(rotated, dispatched,
                ProbeResult.passed(30, Fixtures.T0, "x"), true, false).verified());
        RotationOrchestrator.Outcome ok = orchestrator.complete(rotated, dispatched,
                ProbeResult.passed(30, Fixtures.T0, "x"), true, true);
        assertTrue(ok.verified());
        assertEquals(CredentialState.VERIFIED, ok.credential().state());
        assertEquals(TicketState.CLOSED, ok.ticket().state());
    }
}

class EndpointContractTest {
    @Test void fourOfficialEndpointsEachHaveOneAssertion() throws Exception {
        String ledgerRow = "{\"credential_id\":\"CRED-0007\",\"channel_code\":\"CH-01\","
                + "\"credential_type\":\"cert\",\"expires_at\":\"2026-10-15T00:00:00Z\","
                + "\"fingerprint\":\"fp-7\"}";
        try (StubHttpServer esb = new StubHttpServer().setDefault(200, "[]");
             StubHttpServer ledger = new StubHttpServer().setDefault(200, "[" + ledgerRow + "]");
             StubHttpServer tickets = new StubHttpServer().setDefault(200, "{\"ticket_no\":\"TKT-1\"}");
             StubHttpServer verifications = new StubHttpServer().setDefault(200, "{}")) {
            assertEquals(0, new EsbFailureClient(esb.url(), "t").fetchPage("2026-11-09T00:00:00Z", 200)
                    .events().size());
            assertEquals("/api/v1/interface-failures", esb.lastRequest().uri().split("\\?")[0]);

            assertEquals(1, new CredentialLedgerClient(ledger.url(), "t").fetch().size());
            assertEquals("/api/v1/integration-credentials", ledger.lastRequest().uri());

            String key = IdempotencyKey.compute("CRED-0007", Fixtures.T0);
            Ticket draft = Fixtures.ticket("CRED-0007", key);
            assertTrue(new TicketClient(tickets.url(), "t").dispatch(draft, Fixtures.T0).success());
            assertEquals("/api/v1/credential-tickets", tickets.lastRequest().uri());
            assertTrue(tickets.lastRequest().headers().toLowerCase().contains("idempotency-key"));

            assertTrue(new VerificationClient(verifications.url(), "t")
                    .write("CRED-0007", "CH-01", ProbeResult.passed(30, Fixtures.T0, "x")).written());
            assertEquals("/api/v1/credential-verifications", verifications.lastRequest().uri());
        }
    }

    @Test void productionCodeHasNoLocalDefaultAndNoTestDoubles() throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(Path.of("src/main"))) {
            for (Path p : paths.filter(Files::isRegularFile).toList()) {
                String name = p.getFileName().toString();
                String text = Files.readString(p);
                if (name.startsWith("Stub") || text.contains("StubHttpServer")
                        || text.contains("localhost") || text.contains("127.0.0.1")) {
                    hits.add(p.toString());
                }
            }
        }
        assertTrue(hits.isEmpty(), "默认路径不得指向替身，替身类只能在 src/test: " + hits);
        assertTrue(Files.isDirectory(Path.of("src/test/java/com/med/icr/support")));
    }
}
