package com.med.icr.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** since 游标持久化：重启后从上次位置续拉，不重拉也不漏拉。 */
public final class CursorDao {
    public static final String ESB_SINCE = "esb_since";
    private final Database db;
    public CursorDao(Database db) { this.db = db; }

    public String get(String name, String fallback) {
        try (PreparedStatement ps = db.connection()
                .prepareStatement("SELECT value FROM subscription_cursor WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("value") : fallback;
            }
        } catch (SQLException e) { throw new IllegalStateException("游标读取失败", e); }
    }

    public void set(String name, String value) {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "INSERT INTO subscription_cursor(name, value) VALUES(?,?)" +
                " ON CONFLICT(name) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, name);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("游标写入失败", e); }
    }
}
