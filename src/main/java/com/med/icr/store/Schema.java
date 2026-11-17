package com.med.icr.store;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** 五张主表 + 游标 / 通道状态辅助表，一次建齐；索引随表创建。 */
public final class Schema {
    public static final String[] TABLES = {
            "credential", "failure_event_archive", "ticket", "audit_log", "model_call"};

    private static final String[] DDL = {
        "CREATE TABLE IF NOT EXISTS credential (" +
            "credential_id TEXT PRIMARY KEY, channel_code TEXT, credential_type TEXT, expires_at TEXT," +
            "owner_dept TEXT, owner TEXT, rotation_entry TEXT, last_verified_at TEXT," +
            "fingerprint TEXT, state TEXT, channel_codes TEXT, downstream TEXT)",
        "CREATE INDEX IF NOT EXISTS idx_credential_fingerprint ON credential(fingerprint)",
        "CREATE INDEX IF NOT EXISTS idx_credential_expires ON credential(expires_at)",
        "CREATE TABLE IF NOT EXISTS failure_event_archive (" +
            "event_id TEXT PRIMARY KEY, channel_code TEXT, error_class TEXT, error_message TEXT," +
            "occurred_at TEXT, credential_fingerprint TEXT, trace_id TEXT, endpoint TEXT," +
            "upstream_service TEXT, credential_id TEXT)",
        "CREATE INDEX IF NOT EXISTS idx_archive_occurred ON failure_event_archive(occurred_at)",
        "CREATE TABLE IF NOT EXISTS ticket (" +
            "idempotency_key TEXT PRIMARY KEY, ticket_no TEXT, credential_id TEXT, root_cause TEXT," +
            "evidence_refs TEXT, due_at TEXT, state TEXT, created_at TEXT)",
        "CREATE INDEX IF NOT EXISTS idx_ticket_credential ON ticket(credential_id)",
        "CREATE TABLE IF NOT EXISTS audit_log (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, actor TEXT, reason TEXT, occurred_at TEXT," +
            "object_type TEXT, object_id TEXT, action TEXT, result TEXT)",
        "CREATE INDEX IF NOT EXISTS idx_audit_object ON audit_log(object_id)",
        "CREATE INDEX IF NOT EXISTS idx_audit_time ON audit_log(occurred_at)",
        "CREATE TABLE IF NOT EXISTS model_call (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, model_name TEXT, base_url_host TEXT, request_id TEXT," +
            "input_event_count INTEGER, window_start TEXT, window_end TEXT, elapsed_ms INTEGER," +
            "degraded INTEGER, failure_class TEXT, conclusion_summary TEXT, called_at TEXT)",
        "CREATE INDEX IF NOT EXISTS idx_model_call_time ON model_call(called_at)",
        "CREATE TABLE IF NOT EXISTS subscription_cursor (" +
            "name TEXT PRIMARY KEY, value TEXT)",
        "CREATE TABLE IF NOT EXISTS channel_state (" +
            "channel_code TEXT PRIMARY KEY, enabled INTEGER, reason TEXT)"
    };

    private Schema() {}

    public static void ensure(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            for (String sql : DDL) st.execute(sql);
        }
    }
}
