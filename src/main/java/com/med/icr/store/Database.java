package com.med.icr.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/** 本地状态库：单连接持有（内存库亦保证跨 DAO 可见），启动时自动建目录与全部表。 */
public final class Database implements AutoCloseable {
    private final Connection connection;
    private final String url;

    public Database(String jdbcUrl) {
        this.url = jdbcUrl;
        try {
            this.connection = DriverManager.getConnection(jdbcUrl);
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
            }
            Schema.ensure(connection);
        } catch (SQLException e) {
            throw new IllegalStateException("无法打开本地状态库: " + jdbcUrl, e);
        }
    }

    /** 默认路径 .localstate/state.db，可由 ICR_STATE_DB 覆盖。 */
    public static Database openDefault() {
        String override = System.getenv("ICR_STATE_DB");
        if (override != null && !override.isBlank()) return new Database(override);
        Path dir = Path.of(".localstate");
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            throw new IllegalStateException("无法创建 .localstate 目录", e);
        }
        return new Database("jdbc:sqlite:" + dir.resolve("state.db").toAbsolutePath());
    }

    public static Database inMemory() {
        return new Database("jdbc:sqlite::memory:");
    }

    public Connection connection() { return connection; }
    public String url() { return url; }

    @Override public void close() {
        try { connection.close(); } catch (SQLException ignored) { }
    }
}
