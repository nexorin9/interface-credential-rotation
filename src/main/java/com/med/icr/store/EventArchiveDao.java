package com.med.icr.store;

import com.med.icr.domain.FailureEvent;
import com.med.icr.util.Times;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 失败事件归档：无界档留证，按 occurred_at 保留期清理（默认 90 天）。 */
public final class EventArchiveDao {
    public static final int DEFAULT_RETENTION_DAYS = 90;
    private final Database db;
    public EventArchiveDao(Database db) { this.db = db; }

    public int archive(List<FailureEvent> events) {
        String sql = "INSERT OR IGNORE INTO failure_event_archive(event_id, channel_code, error_class," +
                "error_message, occurred_at, credential_fingerprint, trace_id, endpoint," +
                "upstream_service, credential_id) VALUES(?,?,?,?,?,?,?,?,?,?)";
        int written = 0;
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            for (FailureEvent e : events) {
                ps.setString(1, e.eventId());
                ps.setString(2, e.channelCode());
                ps.setString(3, e.errorClass());
                ps.setString(4, e.errorMessage());
                ps.setString(5, Times.format(e.occurredAt()));
                ps.setString(6, e.credentialFingerprint());
                ps.setString(7, e.traceId());
                ps.setString(8, e.endpoint());
                ps.setString(9, e.upstreamService());
                ps.setString(10, e.credentialId());
                written += ps.executeUpdate();
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("事件归档失败", ex);
        }
        return written;
    }

    public int purge(int retentionDays, Instant now) {
        Instant cutoff = now.minusSeconds((long) retentionDays * 86400L);
        try (PreparedStatement ps = db.connection()
                .prepareStatement("DELETE FROM failure_event_archive WHERE occurred_at < ?")) {
            ps.setString(1, Times.format(cutoff));
            return ps.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("归档清理失败", e); }
    }

    public List<FailureEvent> all() {
        List<FailureEvent> out = new ArrayList<>();
        try (PreparedStatement ps = db.connection()
                .prepareStatement("SELECT * FROM failure_event_archive ORDER BY occurred_at, event_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new FailureEvent(rs.getString("event_id"), rs.getString("channel_code"),
                        rs.getString("error_class"), rs.getString("error_message"),
                        Times.parse(rs.getString("occurred_at")), rs.getString("credential_fingerprint"),
                        rs.getString("trace_id"), rs.getString("endpoint"), rs.getString("upstream_service"),
                        rs.getString("credential_id")));
            }
        } catch (SQLException e) { throw new IllegalStateException(e); }
        return out;
    }

    public int count() {
        try (PreparedStatement ps = db.connection().prepareStatement("SELECT COUNT(*) FROM failure_event_archive");
             ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        catch (SQLException e) { throw new IllegalStateException(e); }
    }
}
