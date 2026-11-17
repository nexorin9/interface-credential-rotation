package com.med.icr.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** 通道状态：通道级致命失败后置「已停用 + 原因」，其余通道继续消费。 */
public final class ChannelStateDao {
    public record Status(String channelCode, boolean enabled, String reason) {}

    private final Database db;
    public ChannelStateDao(Database db) { this.db = db; }

    public void setEnabled(String channel, boolean enabled, String reason) {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "INSERT INTO channel_state(channel_code, enabled, reason) VALUES(?,?,?)" +
                " ON CONFLICT(channel_code) DO UPDATE SET enabled=excluded.enabled, reason=excluded.reason")) {
            ps.setString(1, channel);
            ps.setInt(2, enabled ? 1 : 0);
            ps.setString(3, reason);
            ps.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("通道状态写入失败", e); }
    }

    public Status status(String channel) {
        try (PreparedStatement ps = db.connection()
                .prepareStatement("SELECT * FROM channel_state WHERE channel_code = ?")) {
            ps.setString(1, channel);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new Status(channel, rs.getInt("enabled") == 1, rs.getString("reason"));
                return new Status(channel, true, null);
            }
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    public boolean isEnabled(String channel) { return status(channel).enabled(); }
}
