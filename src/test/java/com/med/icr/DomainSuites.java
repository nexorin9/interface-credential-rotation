package com.med.icr;

import com.med.icr.config.AppConfig;
import com.med.icr.domain.Credential;
import com.med.icr.domain.CredentialState;
import com.med.icr.domain.CredentialStateMachine;
import com.med.icr.domain.ExpiryGrade;
import com.med.icr.domain.FailureEvent;
import com.med.icr.domain.Fingerprint;
import com.med.icr.domain.IdempotencyKey;
import com.med.icr.domain.IllegalStateTransitionException;
import com.med.icr.domain.RootCauseCard;
import com.med.icr.domain.TicketState;
import com.med.icr.domain.TicketStateMachine;
import com.med.icr.support.Fixtures;
import com.med.icr.util.Masking;
import com.med.icr.util.Times;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** T2 / T6–T11：配置面、双状态机、幂等键、指纹、到期分级与领域不变量。 */
final class DomainSuites {}

class AppConfigTest {
    private static Map<String, String> base() {
        Map<String, String> env = new HashMap<>();
        env.put(AppConfig.ESB_BASE_URL, "https://esb.intra.example");
        env.put(AppConfig.ESB_TOKEN, "esb-token");
        env.put(AppConfig.CREDENTIAL_BASE_URL, "https://cred.intra.example");
        env.put(AppConfig.CREDENTIAL_TOKEN, "cred-token");
        env.put(AppConfig.FINGERPRINT_KEY, "hmac-key");
        return env;
    }

    @Test void elevenKeysAreDescribedAndMissingMarked() {
        assertEquals(11, AppConfig.KEYS.size());
        List<AppConfig.ConfigRow> rows = AppConfig.describe(Map.of());
        assertEquals(11, rows.size());
        assertTrue(rows.stream().noneMatch(r -> r.set));
        assertTrue(rows.stream().allMatch(r -> r.purpose != null && !r.purpose.isBlank()));
        assertEquals(AppConfig.KEYS.get(0), rows.get(0).name);
    }

    @Test void missingRequiredThrowsWithoutEchoingSecret() {
        Map<String, String> env = new HashMap<>();
        env.put(AppConfig.ESB_TOKEN, "SUPER-SECRET-TOKEN-999");
        AppConfig.ConfigException ex = assertThrows(AppConfig.ConfigException.class, () -> AppConfig.from(env));
        assertFalse(ex.getMessage().contains("SUPER-SECRET-TOKEN-999"));
        assertTrue(ex.getMessage().contains("未设置"));
        assertTrue(ex.getMessage().contains(AppConfig.ESB_BASE_URL));
    }

    @Test void modelIsOptionalAndRequireNeverEchoes() {
        AppConfig cfg = AppConfig.from(base());
        assertFalse(cfg.modelConfigured());
        assertEquals("https://esb.intra.example", cfg.require(AppConfig.ESB_BASE_URL));
        assertThrows(AppConfig.ConfigException.class, () -> cfg.require(AppConfig.MODEL_BASE_URL));
        Map<String, String> withModel = base();
        withModel.put(AppConfig.MODEL_BASE_URL, "https://model.intra.example");
        withModel.put(AppConfig.MODEL_API_KEY, "k");
        withModel.put(AppConfig.MODEL_NAME, "m");
        assertTrue(AppConfig.from(withModel).modelConfigured());
        assertEquals(5, cfg.values().size(), "只装配已设置的变量，缺省模型项不入场");
    }

    @Test void doctorRowsNeverContainValues() {
        Map<String, String> env = base();
        env.put(AppConfig.CREDENTIAL_TOKEN, "DO-NOT-PRINT-12345");
        String rendered = String.join("\n", AppConfig.describe(env).stream().map(r -> r.render()).toList());
        assertFalse(rendered.contains("DO-NOT-PRINT-12345"));
        assertTrue(rendered.contains("CREDENTIAL_TOKEN"));
    }
}

class CredentialStateMachineTest {
    @Test void t01ActiveToExpiring() { assertTrue(CredentialStateMachine.isLegal(CredentialState.ACTIVE, CredentialState.EXPIRING)); }
    @Test void t02ActiveToExpired() { assertTrue(CredentialStateMachine.isLegal(CredentialState.ACTIVE, CredentialState.EXPIRED)); }
    @Test void t03ExpiringToExpired() { assertTrue(CredentialStateMachine.isLegal(CredentialState.EXPIRING, CredentialState.EXPIRED)); }
    @Test void t04ExpiredToPendingRotation() { assertTrue(CredentialStateMachine.isLegal(CredentialState.EXPIRED, CredentialState.PENDING_ROTATION)); }
    @Test void t05PendingToRotated() { assertTrue(CredentialStateMachine.isLegal(CredentialState.PENDING_ROTATION, CredentialState.ROTATED)); }
    @Test void t06PendingRollbackToActive() { assertTrue(CredentialStateMachine.isLegal(CredentialState.PENDING_ROTATION, CredentialState.ACTIVE)); }
    @Test void t07VerifiedOnlyFromRotatedWithProbe() {
        assertEquals(CredentialState.VERIFIED, CredentialStateMachine.transitionToVerified(
                CredentialState.ROTATED, CredentialStateMachine.PROBE_VERIFIED, true));
        assertThrows(IllegalStateTransitionException.class, () -> CredentialStateMachine.transitionToVerified(
                CredentialState.ACTIVE, CredentialStateMachine.PROBE_VERIFIED, true));
        assertThrows(IllegalStateTransitionException.class, () -> CredentialStateMachine.transitionToVerified(
                CredentialState.ROTATED, "MANUAL_OVERRIDE", true));
        assertThrows(IllegalStateTransitionException.class, () -> CredentialStateMachine.transitionToVerified(
                CredentialState.ROTATED, CredentialStateMachine.PROBE_VERIFIED, false));
    }
    @Test void t08VerifiedIsTerminal() {
        for (CredentialState to : CredentialState.values()) {
            assertFalse(CredentialStateMachine.isLegal(CredentialState.VERIFIED, to));
        }
    }
    @Test void t09IllegalJumpRejected() {
        assertFalse(CredentialStateMachine.isLegal(CredentialState.ACTIVE, CredentialState.VERIFIED));
        assertFalse(CredentialStateMachine.isLegal(CredentialState.ROTATED, CredentialState.ACTIVE));
        assertThrows(IllegalStateTransitionException.class,
                () -> CredentialStateMachine.transition(CredentialState.ACTIVE, CredentialState.VERIFIED));
    }
    @Test void t10IllegalExceptionCarriesCodeAndFromTo() {
        IllegalStateTransitionException ex = assertThrows(IllegalStateTransitionException.class,
                () -> CredentialStateMachine.validate(CredentialState.EXPIRED, CredentialState.VERIFIED));
        assertEquals(IllegalStateTransitionException.CRED_CODE, ex.code);
        assertEquals("EXPIRED", ex.from);
        assertEquals("VERIFIED", ex.to);
        assertTrue(ex.getMessage().contains("CRED_STATE_ILLEGAL_TRANSITION"));
    }
}

class TicketStateMachineTest {
    @Test void pendingToDispatched() { assertEquals(TicketState.DISPATCHED, TicketStateMachine.transition(TicketState.PENDING, TicketState.DISPATCHED)); }
    @Test void dispatchedToClosed() { assertTrue(TicketStateMachine.isLegal(TicketState.DISPATCHED, TicketState.CLOSED)); }
    @Test void pendingMayBeCancelled() { assertTrue(TicketStateMachine.isLegal(TicketState.PENDING, TicketState.CLOSED)); }
    @Test void closedIsTerminal() {
        assertFalse(TicketStateMachine.isLegal(TicketState.CLOSED, TicketState.PENDING));
        IllegalStateTransitionException ex = assertThrows(IllegalStateTransitionException.class,
                () -> TicketStateMachine.transition(TicketState.CLOSED, TicketState.DISPATCHED));
        assertEquals(IllegalStateTransitionException.TICKET_CODE, ex.code);
    }
    @Test void dispatchedCannotGoBack() { assertFalse(TicketStateMachine.isLegal(TicketState.DISPATCHED, TicketState.PENDING)); }
}

class IdempotencyKeyTest {
    @Test void sameWindowIsStable() {
        String a = IdempotencyKey.compute("CRED-0007", Fixtures.T0);
        String b = IdempotencyKey.compute("CRED-0007", Fixtures.T0);
        assertEquals(a, b);
        assertEquals(64, a.length());
    }
    @Test void differentWindowDiffers() {
        assertNotEquals(IdempotencyKey.compute("CRED-0007", Fixtures.T0),
                IdempotencyKey.compute("CRED-0007", Fixtures.T0.plusSeconds(300)));
    }
    @Test void lengthPrefixDisambiguatesSeparatorInId() {
        assertNotEquals(IdempotencyKey.compute("A|B", Fixtures.T0),
                IdempotencyKey.compute("A", Fixtures.T0));
    }
    @Test void nullInputRejected() {
        assertThrows(IllegalArgumentException.class, () -> IdempotencyKey.compute(null, Fixtures.T0));
        assertThrows(IllegalArgumentException.class, () -> IdempotencyKey.compute("CRED-0007", null));
    }
}

class FingerprintTest {
    private static final String SECRET = "SYNTHETIC-SECRET-CRED-0007";

    @Test void truncatedIrreversibleAndDeterministic() {
        String fp = Fingerprint.of("CRED-0007", SECRET, Fixtures.FP_KEY);
        assertEquals(Fingerprint.STORED_LENGTH, fp.length());
        assertTrue(fp.matches("[0-9a-f]{16}"));
        assertEquals(fp, Fingerprint.of("CRED-0007", SECRET, Fixtures.FP_KEY));
        assertFalse(fp.contains(SECRET));
    }
    @Test void changingSecretOrKeyOrIdChangesFingerprint() {
        String base = Fingerprint.of("CRED-0007", SECRET, Fixtures.FP_KEY);
        assertNotEquals(base, Fingerprint.of("CRED-0007", SECRET + "-x", Fixtures.FP_KEY));
        assertNotEquals(base, Fingerprint.of("CRED-0007", SECRET, Fixtures.FP_KEY + "-x"));
        assertNotEquals(base, Fingerprint.of("CRED-0008", SECRET, Fixtures.FP_KEY));
    }
    @Test void parseRejectsMalformed() {
        String fp = Fingerprint.of("CRED-0007", SECRET, Fixtures.FP_KEY);
        assertEquals(fp, Fingerprint.parse(fp));
        assertThrows(IllegalArgumentException.class, () -> Fingerprint.parse("ABCDEF0123456789"));
        assertThrows(IllegalArgumentException.class, () -> Fingerprint.parse("short"));
        assertThrows(IllegalArgumentException.class, () -> Fingerprint.parse(null));
    }
    @Test void constantTimeEquals() {
        String fp = Fingerprint.of("CRED-0007", SECRET, Fixtures.FP_KEY);
        assertTrue(Fingerprint.constantTimeEquals(fp, new String(fp)));
        assertFalse(Fingerprint.constantTimeEquals(fp, "0000000000000000"));
        assertFalse(Fingerprint.constantTimeEquals(null, fp));
    }
    @Test void blankKeyRejected() {
        assertThrows(IllegalStateException.class, () -> Fingerprint.of("CRED-0007", SECRET, ""));
        assertThrows(IllegalStateException.class, () -> Fingerprint.of("CRED-0007", SECRET, null));
    }
    @Test void noPlaintextInRepositoryOrRuntimeArtifacts() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String root : List.of("src/main", "samples", "reports", "docs", "../logs")) {
            Path dir = Path.of(root);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> paths = Files.walk(dir)) {
                for (Path p : paths.filter(Files::isRegularFile).toList()) {
                    String name = p.getFileName().toString();
                    if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".class")
                            || name.endsWith(".jar") || name.endsWith(".db")) continue;
                    String text = Files.readString(p);
                    if (text.contains(SECRET) || text.contains("SUPER-SECRET-TOKEN-999")) {
                        violations.add(p.toString());
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "检索到凭据明文: " + violations);
    }
}

class ExpiryGradeTest {
    private static final Instant NOW = Fixtures.T0;
    private static Credential expiring(Instant at) { return Fixtures.credential("CRED-0007", null, at); }

    @Test void t01NoExpiry() {
        assertEquals(ExpiryGrade.NO_EXPIRY, ExpiryGrade.determine(expiring(null), NOW, 7).grade());
    }
    @Test void t02AlreadyExpiredIsRed() {
        assertEquals(ExpiryGrade.RED, ExpiryGrade.determine(expiring(NOW.minusSeconds(86400L)), NOW, 7).grade());
    }
    @Test void t03OneMillisecondEarlyIsRed() {
        ExpiryGrade.Grade g = ExpiryGrade.determine(expiring(NOW.minusMillis(1)), NOW, 7);
        assertEquals(ExpiryGrade.RED, g.grade());
        assertEquals(-1, g.daysLeft());
    }
    @Test void t04ExactlySevenDaysIsOrange() {
        ExpiryGrade.Grade g = ExpiryGrade.determine(expiring(NOW.plusSeconds(7L * 86400)), NOW, 7);
        assertEquals(ExpiryGrade.ORANGE, g.grade());
        assertEquals(7, g.daysLeft());
    }
    @Test void t05OneDayIsOrange() {
        assertEquals(ExpiryGrade.ORANGE, ExpiryGrade.determine(expiring(NOW.plusSeconds(86400L)), NOW, 7).grade());
    }
    @Test void t06EightDaysIsGrey() {
        assertEquals(ExpiryGrade.GREY, ExpiryGrade.determine(expiring(NOW.plusSeconds(8L * 86400)), NOW, 7).grade());
    }
    @Test void t07JustOverSevenDaysIsGrey() {
        assertEquals(ExpiryGrade.GREY,
                ExpiryGrade.determine(expiring(NOW.plusSeconds(7L * 86400).plusMillis(1)), NOW, 7).grade());
    }
    @Test void t08FixedClockOverload() {
        Clock fixed = Clock.fixed(NOW, ZoneOffset.UTC);
        assertEquals(ExpiryGrade.ORANGE,
                ExpiryGrade.determine(expiring(NOW.plusSeconds(7L * 86400)), fixed).grade());
        assertFalse(Times.format(fixed.instant()).contains("+"));
    }
}

class DomainInvariantTest {
    @Test void invariantChecklistIsEmpty() throws IOException {
        List<String> violations = new ArrayList<>();
        // 1. 幂等键可重算
        String k1 = IdempotencyKey.compute("CRED-0007", Fixtures.T0);
        String k2 = IdempotencyKey.compute("CRED-0007", Fixtures.T0);
        if (!k1.equals(k2)) violations.add("idempotency_key_not_stable");
        // 2. 「已验证」唯一入口
        try {
            CredentialStateMachine.transitionToVerified(CredentialState.ACTIVE,
                    CredentialStateMachine.PROBE_VERIFIED, true);
            violations.add("verified_reachable_without_probe_path");
        } catch (IllegalStateTransitionException expected) { /* 期望拒绝 */ }
        // 3. 结论卡永远是草稿且证据不可变
        RootCauseCard card = new RootCauseCard("根因", "CRED-0007", List.of("TRACE-1"), "换证",
                0.5, 0.7, "通道级", false, Fixtures.T0, 1, "CH-01");
        if (card.modelUsed()) violations.add("rule_card_claims_model_used");
        try { card.evidenceRefs().add("TRACE-2"); violations.add("evidence_refs_mutable"); }
        catch (UnsupportedOperationException expected) { /* 期望不可变 */ }
        // 4. 脱敏后不得留明文标记
        String masked = Masking.apply("authorization: Bearer RAWTOKENVALUE12345");
        if (Masking.containsPlaintextMarker(masked)) violations.add("masking_leaves_plaintext");
        // 5. 单条级失败不升级
        FailureEvent single = Fixtures.schemaError("E-1", Fixtures.T0);
        if (!single.isSchemaFieldError()) violations.add("schema_error_misclassified");
        // 6. 架构文档含领域不变量小节
        String arch = Fixtures.read(Path.of("docs/architecture.md"));
        if (!arch.contains("领域不变量")) violations.add("domain_invariant_section_missing");
        assertTrue(violations.isEmpty(), "不变量违例清单：" + violations);
    }
}
