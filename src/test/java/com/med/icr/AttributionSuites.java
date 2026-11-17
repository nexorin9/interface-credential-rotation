package com.med.icr;

import com.med.icr.attribution.AttributionBenchmark;
import com.med.icr.attribution.AttributionPipeline;
import com.med.icr.attribution.ConfidenceScorer;
import com.med.icr.attribution.CredentialBinder;
import com.med.icr.attribution.DegradationPolicy;
import com.med.icr.attribution.EvidenceRefChecker;
import com.med.icr.attribution.ModelAttributionPipeline;
import com.med.icr.attribution.RuleAttributionPipeline;
import com.med.icr.attribution.RuleAttributor;
import com.med.icr.attribution.WindowAggregator;
import com.med.icr.domain.Credential;
import com.med.icr.domain.CredentialState;
import com.med.icr.domain.FailureEvent;
import com.med.icr.domain.RootCauseCard;
import com.med.icr.model.ModelGateway;
import com.med.icr.model.ModelRequest;
import com.med.icr.model.ModelResponse;
import com.med.icr.model.ModelUnavailableException;
import com.med.icr.model.StructuredOutputValidator;
import com.med.icr.support.Fixtures;
import com.med.icr.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** T20–T24 / T28–T31：绑定、时间窗、规则基线、置信度、管道模板、降级与对比基准。 */
final class AttributionSuites {}

class CredentialBinderTest {
    @Test void fingerprintPrimaryKeyResolves() {
        Credential c = Fixtures.credential("CRED-0007", "fp-7", Fixtures.T0.plusSeconds(3L * 86400),
                "CH-01", "CH-02", "CH-03");
        CredentialBinder.BindingResult r = new CredentialBinder().bind(
                List.of(Fixtures.event("E-1", "CH-01", "cert_expired", Fixtures.T0, "fp-7")), List.of(c));
        assertEquals(1, r.bound().size());
        assertEquals("CRED-0007", r.bound().get(0).credentialId());
        assertEquals(0, r.unresolved().size());
    }

    @Test void conflictsRejectAttributionAndIncrementUnresolved() {
        Instant exp = Fixtures.T0.plusSeconds(3L * 86400);
        Credential a = Fixtures.credential("CRED-A", "fp-x", exp, "CH-01");
        Credential b = Fixtures.credential("CRED-B", "fp-x", exp, "CH-01");
        CredentialBinder binder = new CredentialBinder();
        List<FailureEvent> events = List.of(Fixtures.event("E-1", "CH-01", "cert_expired", Fixtures.T0, "fp-x"));
        assertEquals(0, binder.bind(List.of(), List.of(a, b)).unresolved().size());
        CredentialBinder.BindingResult r = binder.bind(events, List.of(a, b));
        assertTrue(r.bound().isEmpty(), "冲突样例不得生成归因结论");
        assertEquals(1, r.unresolved().size(), "unresolved 计数应递增");
        assertEquals("fingerprint_conflict", r.unresolved().get(0).reason());
    }

    @Test void missingFingerprintIdMultiFingerprintAndChannelMismatchAllUnresolved() {
        Instant exp = Fixtures.T0.plusSeconds(3L * 86400);
        CredentialBinder binder = new CredentialBinder();
        assertEquals("missing_fingerprint", binder.bind(
                List.of(Fixtures.event("E-0", "CH-01", "cert_expired", Fixtures.T0, null)),
                List.of(Fixtures.credential("C-1", "fp-1", exp))).unresolved().get(0).reason());
        assertEquals("credential_fingerprint_conflict", binder.bind(
                List.of(Fixtures.event("E-1", "CH-01", "cert_expired", Fixtures.T0, "fp-1")),
                List.of(Fixtures.credential("C-1", "fp-1", exp),
                        Fixtures.credential("C-1", "fp-2", exp))).unresolved().get(0).reason());
        assertEquals("channel_code_mismatch", binder.bind(
                List.of(Fixtures.event("E-2", "CH-09", "cert_expired", Fixtures.T0, "fp-1")),
                List.of(Fixtures.credential("C-1", "fp-1", exp, "CH-01"))).unresolved().get(0).reason());
    }
}

class WindowAggregatorTest {
    @Test void eventExactlyOnRightBoundaryOpensNewWindow() {
        Instant t0 = Fixtures.T0;
        List<FailureEvent> events = List.of(
                Fixtures.event("E-1", "CH-01", "cert_expired", t0, "fp"),
                Fixtures.event("E-2", "CH-02", "tls_handshake", t0.plusSeconds(240), "fp"),
                Fixtures.event("E-3", "CH-03", "cert_expired", t0.plusSeconds(300), "fp"),
                Fixtures.event("E-4", "CH-01", "cert_expired", t0.plusSeconds(301), "fp"));
        List<WindowAggregator.Window> windows = new WindowAggregator().aggregate(events);
        assertEquals(2, windows.size());
        assertEquals(2, windows.get(0).events().size());
        assertEquals(t0.plusSeconds(300), windows.get(1).start(), "右边界事件必须开新窗");
        assertEquals(t0.plusSeconds(600), windows.get(1).end());
        assertEquals(2, windows.get(1).events().size());
    }

    @Test void singleSchemaErrorNeverUpgradesWhenBucketingByKey() {
        List<WindowAggregator.Window> windows = new WindowAggregator().aggregateByKey(
                List.of(Fixtures.schemaError("E-1", Fixtures.T0)), e -> "ec:field_schema",
                Duration.ofMinutes(5), true);
        assertTrue(windows.isEmpty(), "单条字段口径错不升级为凭据级结论");
        assertEquals(Duration.ofMinutes(5), WindowAggregator.DEFAULT_WINDOW);
    }
}

class RuleAttributorTest {
    private static List<FailureEvent> cluster() {
        List<FailureEvent> out = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            out.add(Fixtures.event("E-" + i, "CH-0" + (i % 3 + 1),
                    i % 2 == 0 ? "cert_expired" : "tls_handshake",
                    Fixtures.T0.plusSeconds(i * 20L), "fp-7"));
        }
        return out;
    }

    @Test void identicalInputProducesByteIdenticalOutput() throws Exception {
        List<Credential> ledger = List.of(Fixtures.credential("CRED-0007", "fp-7",
                Fixtures.T0.plusSeconds(3L * 86400), "CH-01", "CH-02", "CH-03"));
        String a = render(new RuleAttributor().attribute(cluster(), ledger));
        String b = render(new RuleAttributor().attribute(cluster(), ledger));
        assertEquals(Fixtures.sha256(a), Fixtures.sha256(b));
        assertFalse(a.isBlank());
    }

    @Test void singleSchemaErrorIsNotACredentialLevelConclusion() {
        assertTrue(new RuleAttributor().attribute(List.of(Fixtures.schemaError("E-1", Fixtures.T0))).isEmpty());
    }

    @Test void ledgerMapsFingerprintToCredentialId() {
        List<Credential> ledger = List.of(Fixtures.credential("CRED-0007", "fp-7",
                Fixtures.T0.plusSeconds(3L * 86400), "CH-01", "CH-02", "CH-03"));
        List<RootCauseCard> cards = new RuleAttributor().attribute(cluster(), ledger);
        assertEquals(1, cards.size());
        assertEquals("CRED-0007", cards.get(0).credentialId());
        assertEquals("通道级", cards.get(0).severity());
        assertFalse(cards.get(0).modelUsed());
    }

    @Test void evidenceRefsSortedAndCapped() {
        List<FailureEvent> many = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            many.add(Fixtures.event("E-" + i, "CH-01", "cert_expired",
                    Fixtures.T0.plusSeconds(i * 10L), "fp-7"));
        }
        List<RootCauseCard> cards = new RuleAttributor().attribute(many);
        assertEquals(1, cards.size());
        assertTrue(cards.get(0).evidenceRefs().size() <= RuleAttributor.DEFAULT_MAX_EVIDENCE);
        List<String> sorted = new ArrayList<>(cards.get(0).evidenceRefs());
        sorted.sort(String::compareTo);
        assertEquals(sorted, cards.get(0).evidenceRefs());
    }

    private static String render(List<RootCauseCard> cards) {
        StringBuilder sb = new StringBuilder();
        for (RootCauseCard c : cards) sb.append(c.render());
        return sb.toString();
    }
}

class ConfidenceScorerTest {
    @Test void fingerprintRaisesLowerBound() {
        ConfidenceScorer.Confidence withFp = ConfidenceScorer.score(5, 2, true, true);
        ConfidenceScorer.Confidence withoutFp = ConfidenceScorer.score(5, 2, false, true);
        assertTrue(withFp.lower() > withoutFp.lower());
        assertTrue(withFp.upper() >= withFp.lower());
    }
    @Test void zeroEvidenceIsZeroAndBoundsStayInRange() {
        ConfidenceScorer.Confidence zero = ConfidenceScorer.score(0, 0, true, true);
        assertEquals(0.0, zero.lower());
        ConfidenceScorer.Confidence max = ConfidenceScorer.score(1000, 1000, true, true);
        assertTrue(max.lower() >= 0 && max.upper() <= 1);
    }
}

class EvidenceRefCheckerTest {
    @Test void refsMustExistInInputEvents() {
        List<FailureEvent> events = List.of(Fixtures.event("E-1", "CH-01", "cert_expired", Fixtures.T0, "fp"));
        EvidenceRefChecker checker = new EvidenceRefChecker();
        assertTrue(checker.check(List.of("TRACE-E-1", "E-1"), events).valid());
        EvidenceRefChecker.Result bad = checker.check(List.of("TRACE-NOT-EXIST"), events);
        assertFalse(bad.valid());
        assertEquals("evidence_ref_not_in_input", bad.reason());
        assertEquals(List.of("TRACE-NOT-EXIST"), bad.invalidRefs());
    }

    @Test void credentialIdMustExistInLedger() {
        List<Credential> ledger = List.of(Fixtures.credential("CRED-0007", "fp", null));
        assertTrue(new EvidenceRefChecker().checkCredential("CRED-0007", ledger).valid());
        assertFalse(new EvidenceRefChecker().checkCredential("CRED-9999", ledger).valid());
        RootCauseCard blank = new RootCauseCard("x", " ", List.of("TRACE-E-1"), "a", 0.1, 0.2,
                "单条级", false, Fixtures.T0, 1, "CH-01");
        assertFalse(new EvidenceRefChecker().checkCard(blank, List.of(), ledger).valid());
    }
}

class StructuredOutputValidatorTest {
    private static final String VALID = "{\"root_cause\":\"证书过期\",\"credential_id\":\"CRED-0007\","
            + "\"confidence\":0.8,\"evidence_refs\":[\"TRACE-1\"],"
            + "\"suggested_action\":\"换证\",\"severity\":\"CHANNEL_CRITICAL\"}";

    @Test void validStructureParses() throws IOException {
        ModelResponse r = StructuredOutputValidator.validate(Json.MAPPER.readTree(VALID));
        assertEquals("CRED-0007", r.credentialId());
        assertEquals(0.8, r.confidence());
    }

    @Test void outOfRangeConfidenceMissingFieldAndUnknownCredentialAreInvalid() throws IOException {
        assertThrows(ModelUnavailableException.class, () -> StructuredOutputValidator.validate(
                Json.MAPPER.readTree(VALID.replace("\"confidence\":0.8", "\"confidence\":1.8"))));
        assertThrows(ModelUnavailableException.class, () -> StructuredOutputValidator.validate(
                Json.MAPPER.readTree(VALID.replace("\"suggested_action\":\"换证\",", ""))));
        assertThrows(ModelUnavailableException.class, () -> StructuredOutputValidator.validate(
                Json.MAPPER.readTree(VALID.replace("CHANNEL_CRITICAL", "NOT_A_SEVERITY"))));
        assertThrows(ModelUnavailableException.class, () -> StructuredOutputValidator.validate(
                Json.MAPPER.readTree(VALID.replace("[\"TRACE-1\"]", "\"TRACE-1\""))));
        assertThrows(ModelUnavailableException.class, () -> StructuredOutputValidator.validate(null));
    }
}

class AttributionPipelineTest {
    private static List<FailureEvent> events() {
        return List.of(Fixtures.event("E-1", "CH-01", "cert_expired", Fixtures.T0, "fp"),
                Fixtures.event("E-2", "CH-02", "tls_handshake", Fixtures.T0.plusSeconds(30), "fp"));
    }

    @Test void inventedEvidenceRefForcesDegradation() {
        ModelGateway liar = request -> new ModelResponse("编造", "UNRESOLVED", 0.9,
                List.of("TRACE-DOES-NOT-EXIST"), "换证", "CHANNEL_CRITICAL");
        AttributionPipeline.Result r = new ModelAttributionPipeline(liar)
                .run(AttributionPipeline.Context.of(events(), List.of(), true));
        assertTrue(r.degraded());
        assertEquals("MODEL_OUTPUT_INVALID", r.errorCode());
        assertNotNull(r.card());
        assertFalse(r.card().modelUsed());
    }

    @Test void preprocessTruncatesToMaxInputEvents() {
        AtomicReference<ModelRequest> captured = new AtomicReference<>();
        ModelGateway capturing = request -> {
            captured.set(request);
            return new ModelResponse("r", "UNRESOLVED", 0.5,
                    request.events().stream().map(ModelRequest.EventSummary::evidenceRef).toList(),
                    "a", "EVENT_NON_CRITICAL");
        };
        List<FailureEvent> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) many.add(Fixtures.event("E-" + i, "CH-01", "cert_expired",
                Fixtures.T0.plusSeconds(i), "fp"));
        AttributionPipeline.Context ctx = new AttributionPipeline.Context(many, List.of(), true, null, 2);
        AttributionPipeline.Result r = new ModelAttributionPipeline(capturing).run(ctx);
        assertEquals(2, captured.get().events().size());
        assertTrue(r.compliant());
        assertTrue(r.card().modelUsed());
    }

    @Test void rulePipelineFollowsSameTemplateWithoutModel() {
        AttributionPipeline.Result r = new RuleAttributionPipeline()
                .run(AttributionPipeline.Context.of(events(), List.of(), false));
        assertTrue(r.compliant());
        assertTrue(r.degraded());
        assertEquals("RULE_BASELINE", r.errorCode());
        assertFalse(r.card().modelUsed());
    }
}

class AttributionDegradationTest {
    @Test void modelUnreachableYieldsDegradedCardAndNeverAdvancesState() {
        List<FailureEvent> events = List.of(
                Fixtures.event("E-1", "CH-01", "cert_expired", Fixtures.T0, "fp"),
                Fixtures.event("E-2", "CH-02", "tls_handshake", Fixtures.T0.plusSeconds(30), "fp"));
        Credential before = Fixtures.credential("CRED-0007", "fp", Fixtures.T0.plusSeconds(86400L))
                .withState(CredentialState.ROTATED);
        ModelGateway down = request -> {
            throw new ModelUnavailableException(ModelUnavailableException.UNREACHABLE, "端点不可达");
        };
        AttributionPipeline.Result r = new ModelAttributionPipeline(down)
                .run(AttributionPipeline.Context.of(events, List.of(), true));
        assertTrue(r.degraded());
        assertEquals("MODEL_UNREACHABLE", r.errorCode());
        assertNotNull(r.card());
        assertFalse(r.card().modelUsed());
        assertTrue(r.card().render().contains("未使用模型归因"));
        Credential after = before;
        assertEquals(CredentialState.ROTATED, after.state());
        assertTrue(new DegradationPolicy().stateUnchanged(before.state(), after.state()));
    }
}

class AttributionBenchmarkTest {
    @Test void reportIsByteStableAndMetricsRecomputeFromDetails(@TempDir Path tmp) throws Exception {
        Path events = Path.of("samples/failures-4w.jsonl");
        Path truth = Path.of("samples/failures-4w-truth.jsonl");
        Path ledger = Path.of("samples/credentials.json");
        assertTrue(Files.exists(events) && Files.exists(truth) && Files.exists(ledger));
        Path first = tmp.resolve("benchmark-1.json");
        Path second = tmp.resolve("benchmark-2.json");
        AttributionBenchmark.BenchmarkResult r1 = AttributionBenchmark.run(events, truth, ledger);
        AttributionBenchmark.writeReport(first, r1);
        AttributionBenchmark.BenchmarkResult r2 = AttributionBenchmark.run(events, truth, ledger);
        AttributionBenchmark.writeReport(second, r2);
        assertEquals(Fixtures.sha256(first), Fixtures.sha256(second), "两次生成报告 sha256 必须一致");
        assertTrue(r1.sampleSize() >= 600);
        String report = Fixtures.read(first);
        assertTrue(report.contains("precision") && report.contains("recall") && report.contains("miscluster_rate"));
        assertTrue(report.contains("offline-deterministic-stand-in"));
        AttributionBenchmark.Metrics recomputed = AttributionBenchmark.metricsFromDetails(
                AttributionBenchmark.readDetails(first), "rule");
        assertEquals(r1.rule().precision(), recomputed.precision());
        assertEquals(r1.rule().recall(), recomputed.recall());
        assertEquals(r1.sampleSize(), AttributionBenchmark.readDetails(first).size());
        assertTrue(r1.misclusterCases().stream().allMatch(c -> c.eventId() != null));
    }
}
