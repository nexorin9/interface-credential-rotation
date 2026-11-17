package com.med.icr.pipeline;

import com.med.icr.store.AuditDao;

import java.time.Instant;

/** 订阅启停引用计数：到 1 才开队列、到 0 才关队列；重启后按台账对账恢复。 */
public final class SubscriptionRegistry {
    private int references;
    private boolean open;
    private boolean aborted;

    public synchronized boolean acquire() {
        references++;
        if (references == 1) { open = true; aborted = false; return true; }
        return false;
    }

    public synchronized boolean release() {
        if (references == 0) return false;
        references--;
        if (references == 0 && open) {
            abort();
            open = false;
            return true;
        }
        return false;
    }

    /** 关队列前中止在途请求。 */
    public synchronized void abort() { aborted = true; }

    /** 重启对账：按台账里的订阅数恢复，结果写审计。 */
    public synchronized int reconcile(int ledgerSubscribers, AuditDao auditDao) {
        references = Math.max(0, ledgerSubscribers);
        open = references > 0;
        aborted = false;
        if (auditDao != null) {
            auditDao.write(new AuditDao.Entry("system", "重启按台账对账恢复订阅", Instant.now(),
                    "subscription", "registry", "reconcile", "subscribers=" + references));
        }
        return references;
    }

    public synchronized boolean isOpen() { return open; }
    public synchronized int references() { return references; }
    public synchronized boolean isAborted() { return aborted; }
}
