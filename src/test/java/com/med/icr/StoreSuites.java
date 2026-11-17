package com.med.icr;

import com.med.icr.domain.Credential;
import com.med.icr.domain.FailureEvent;
import com.med.icr.domain.IdempotencyKey;
import com.med.icr.domain.Ticket;
import com.med.icr.domain.TicketState;
import com.med.icr.pipeline.ArchiveRetention;
import com.med.icr.store.AuditDao;
import com.med.icr.store.CredentialDao;
import com.med.icr.store.Database;
import com.med.icr.store.EventArchiveDao;
import com.med.icr.store.ModelCallDao;
import com.med.icr.store.Schema;
import com.med.icr.store.TicketDao;
import com.med.icr.support.Fixtures;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** T4 / T10 / T19：SQLite schema、五张主表 DAO、幂等唯一索引、保留期清理。 */
final class StoreSuites {}

class CredentialDaoTest {
    @Test void upsertIsIdempotentOnCredentialId() {
        try (Database db = Database.inMemory()) {
            CredentialDao dao = new CredentialDao(db);
            Instant exp = Fixtures.T0.plusSeconds(3L * 86400);
            dao.upsert(Fixtures.credential("CRED-0007", "fp-0007", exp, "CH-01", "CH-02"));
            dao.upsert(Fixtures.credential("CRED-0007", "fp-0007", exp, "CH-01", "CH-02"));
            dao.upsert(Fixtures.credential("CRED-0003", "fp-0003", exp.plusSeconds(20L * 86400)));
            assertEquals(2, dao.count(), "credential 表行数应与写入的不同凭据数一致");
            assertEquals(1, dao.findByFingerprint("fp-0007").size());
            assertEquals(2, dao.findByFingerprint("fp-0007").get(0).channelCodes().size());
        }
    }

    @Test void findExpiringCoversExpiredAndNearExpiry() {
        try (Database db = Database.inMemory()) {
            CredentialDao dao = new CredentialDao(db);
            dao.upsert(Fixtures.credential("CRED-0001", "fp1", Fixtures.T0.minusSeconds(86400L)));
            dao.upsert(Fixtures.credential("CRED-0002", "fp2", Fixtures.T0.plusSeconds(3L * 86400)));
            dao.upsert(Fixtures.credential("CRED-0003", "fp3", Fixtures.T0.plusSeconds(60L * 86400)));
            List<Credential> expiring = dao.findExpiring(Fixtures.T0, 7);
            assertEquals(2, expiring.size());
            assertTrue(expiring.stream().anyMatch(c -> c.credentialId().equals("CRED-0001")));
        }
    }

    @Test void schemaCreatesAllFiveMainTables() {
        try (Database db = Database.inMemory()) {
            assertEquals(5, Schema.TABLES.length);
            for (String table : Schema.TABLES) {
                assertDoesNotThrow(() -> db.connection().prepareStatement("SELECT COUNT(*) FROM " + table).executeQuery().close());
            }
        } catch (Exception e) {
            fail("主表应全部建齐: " + e);
        }
    }
}

class EventArchiveDaoTest {
    @Test void archiveKeepsAllEventsAndPurgeRemovesOldRows() {
        try (Database db = Database.inMemory()) {
            EventArchiveDao dao = new EventArchiveDao(db);
            List<FailureEvent> events = List.of(
                    Fixtures.event("E-1", "CH-01", "cert_expired", Fixtures.T0, "fp1"),
                    Fixtures.event("E-2", "CH-02", "tls_handshake", Fixtures.T0.plusSeconds(60), "fp1"),
                    Fixtures.event("E-3", "CH-03", "field_schema", Fixtures.T0.plusSeconds(120), null));
            assertEquals(3, dao.archive(events));
            assertEquals(3, dao.count());
            assertEquals(0, dao.archive(events), "event_id 主键应挡住重复归档");
            int deleted = dao.purge(EventArchiveDao.DEFAULT_RETENTION_DAYS,
                    Instant.parse("2027-12-01T00:00:00Z"));
            assertEquals(3, deleted);
            assertEquals(0, dao.count(), "旧行清理后不可再查");
            assertEquals(90, EventArchiveDao.DEFAULT_RETENTION_DAYS);
        }
    }
}

class TicketDaoTest {
    @Test void sameWindowTenWritesProduceSingleTicket() {
        try (Database db = Database.inMemory()) {
            TicketDao dao = new TicketDao(db);
            String key = IdempotencyKey.compute("CRED-0007", Fixtures.T0);
            Ticket first = Fixtures.ticket("CRED-0007", key).withTicketNo("TKT-0001");
            assertTrue(dao.insertIfAbsent(first).created());
            for (int i = 0; i < 9; i++) {
                TicketDao.InsertResult r = dao.insertIfAbsent(
                        Fixtures.ticket("CRED-0007", key).withTicketNo("TKT-DUPLICATE-" + i));
                assertFalse(r.created());
                assertEquals("TKT-0001", r.ticket().ticketNo(), "重复提交必须返回既有单号");
            }
            assertEquals(1, dao.count());
            assertEquals(TicketState.PENDING, dao.findByIdempotencyKey(key).state());
        }
    }
}

class AuditDaoTest {
    @Test void auditEntriesQueryableByObjectAndWindow() {
        try (Database db = Database.inMemory()) {
            AuditDao dao = new AuditDao(db);
            dao.write(new AuditDao.Entry("运维A", "换证窗口到期", Fixtures.T0,
                    "credential", "CRED-0007", "dispatch", "ok"));
            dao.write(new AuditDao.Entry("system", "归档清理", Fixtures.T0.plusSeconds(3600),
                    "archive", "failure_event_archive", "purge", "deleted=3"));
            assertEquals(2, dao.count());
            assertEquals(1, dao.findByObject("CRED-0007").size());
            assertEquals(2, dao.findByWindow(Fixtures.T0.minusSeconds(1), Fixtures.T0.plusSeconds(7200)).size());
            assertEquals(0, dao.findByWindow(Fixtures.T0.plusSeconds(10000), Fixtures.T0.plusSeconds(20000)).size());
        }
    }
}

class ModelCallDaoTest {
    @Test void rawErrorSnippetNeverLandsInDatabase() {
        try (Database db = Database.inMemory()) {
            ModelCallDao dao = new ModelCallDao(db);
            dao.write(new ModelCallDao.Entry("icr-model", "model.intra.example", "REQ-1", 12,
                    Fixtures.T0, Fixtures.T0.plusSeconds(300), 180L, true,
                    "MODEL_UNREACHABLE", "token=RAW-TOKEN-99999 导致结论降级", Fixtures.T0));
            assertEquals(1, dao.count());
            assertFalse(dao.containsRaw("RAW-TOKEN-99999"), "原始报错片段在 model_call 表命中数应为 0");
            assertEquals("model.intra.example", dao.all().get(0).baseUrlHost());
            assertTrue(dao.all().get(0).degraded());
        }
    }
}

class ArchiveRetentionTest {
    @Test void purgeWritesAuditAndReportsRemaining() {
        try (Database db = Database.inMemory()) {
            EventArchiveDao events = new EventArchiveDao(db);
            AuditDao audit = new AuditDao(db);
            events.archive(List.of(Fixtures.event("E-1", "CH-01", "cert_expired", Fixtures.T0, "fp1")));
            ArchiveRetention.Result r = ArchiveRetention.purge(events, 90,
                    Instant.parse("2027-12-01T00:00:00Z"), audit);
            assertEquals(1, r.deleted());
            assertEquals(0, r.remaining());
            assertTrue(audit.all().stream().anyMatch(e -> "purge".equals(e.action())));
        }
    }
}
