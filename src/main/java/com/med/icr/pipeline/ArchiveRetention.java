package com.med.icr.pipeline;

import com.med.icr.store.AuditDao;
import com.med.icr.store.EventArchiveDao;

import java.time.Instant;

/** 归档保留期清理任务（默认 90 天，可配），清理条数写审计。 */
public final class ArchiveRetention {
    private ArchiveRetention() {}

    public record Result(int deleted, int remaining) {}

    public static Result purge(EventArchiveDao dao, int retentionDays, Instant now, AuditDao auditDao) {
        int deleted = dao.purge(retentionDays, now);
        if (auditDao != null) {
            auditDao.write(new AuditDao.Entry("system", "归档保留期清理（" + retentionDays + " 天）", now,
                    "archive", "failure_event_archive", "purge", "deleted=" + deleted));
        }
        return new Result(deleted, dao.count());
    }
}
