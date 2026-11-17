package com.med.icr.store;

import com.med.icr.util.Times;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 安全审计：谁在何时以何理由动了哪张凭据（季度审计导出的数据源）。 */
public final class AuditDao {
    public record Entry(String actor, String reason, Instant occurredAt, String objectType,
                        String objectId, String action, String result) {}

    private final Database db;
    public AuditDao(Database db) { this.db = db; }

    public void write(Entry e) {
        String sql = "INSERT INTO audit_log(actor, reason, occurred_at, object_type, object_id, action, result)" +
                " VALUES(?,?,?,?,?,?,?)";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, e.actor());
            ps.setString(2, e.reason());
            ps.setString(3, Times.format(e.occurredAt()));
            ps.setString(4, e.objectType());
            ps.setString(5, e.objectId());
            ps.setString(6, e.action());
            ps.setString(7, e.result());
            ps.executeUpdate();
        } catch (SQLException ex) { throw new IllegalStateException("审计写入失败", ex); }
    }

    public List<Entry> findByObject(String objectId) {
        return query("SELECT * FROM audit_log WHERE object_id = ? ORDER BY id", objectId, null, null);
    }

    public List<Entry> findByWindow(Instant from, Instant to) {
        return query("SELECT * FROM audit_log WHERE occurred_at >= ? AND occurred_at <= ? ORDER BY id",
                null, Times.format(from), Times.format(to));
    }

    public List<Entry> all() {
        return query("SELECT * FROM audit_log ORDER BY id", null, null, null);
    }

    private List<Entry> query(String sql, String objectId, String from, String to) {
        List<Entry> out = new ArrayList<>();
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            int i = 1;
            if (objectId != null) ps.setString(i++, objectId);
            if (from != null) ps.setString(i++, from);
            if (to != null) ps.setString(i, to);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Entry(rs.getString("actor"), rs.getString("reason"),
                            Times.parse(rs.getString("occurred_at")), rs.getString("object_type"),
                            rs.getString("object_id"), rs.getString("action"), rs.getString("result")));
                }
            }
        } catch (SQLException e) { throw new IllegalStateException(e); }
        return out;
    }

    public int count() {
        try (PreparedStatement ps = db.connection().prepareStatement("SELECT COUNT(*) FROM audit_log");
             ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        catch (SQLException e) { throw new IllegalStateException(e); }
    }
}
