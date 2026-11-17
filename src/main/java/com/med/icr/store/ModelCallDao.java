package com.med.icr.store;

import com.med.icr.util.Masking;
import com.med.icr.util.Times;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 模型调用元数据：只落脱敏结论摘要与证据 id 列表，不落未脱敏报文原文。 */
public final class ModelCallDao {
    public record Entry(String modelName, String baseUrlHost, String requestId, int inputEventCount,
                        Instant windowStart, Instant windowEnd, long elapsedMillis, boolean degraded,
                        String failureClass, String conclusionSummary, Instant calledAt) {}

    private final Database db;
    public ModelCallDao(Database db) { this.db = db; }

    public void write(Entry e) {
        String sql = "INSERT INTO model_call(model_name, base_url_host, request_id, input_event_count," +
                "window_start, window_end, elapsed_ms, degraded, failure_class, conclusion_summary, called_at)" +
                " VALUES(?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, e.modelName());
            ps.setString(2, e.baseUrlHost());
            ps.setString(3, e.requestId());
            ps.setInt(4, e.inputEventCount());
            ps.setString(5, e.windowStart() == null ? null : Times.format(e.windowStart()));
            ps.setString(6, e.windowEnd() == null ? null : Times.format(e.windowEnd()));
            ps.setLong(7, e.elapsedMillis());
            ps.setInt(8, e.degraded() ? 1 : 0);
            ps.setString(9, e.failureClass());
            // 结论摘要在落库前再过一次脱敏，防止调用方误带原文
            ps.setString(10, Masking.apply(e.conclusionSummary()));
            ps.setString(11, Times.format(e.calledAt()));
            ps.executeUpdate();
        } catch (SQLException ex) { throw new IllegalStateException("模型调用审计写入失败", ex); }
    }

    public List<Entry> findByWindow(Instant from, Instant to) {
        return query("SELECT * FROM model_call WHERE called_at >= ? AND called_at <= ? ORDER BY id",
                Times.format(from), Times.format(to));
    }

    public List<Entry> all() { return query("SELECT * FROM model_call ORDER BY id", null, null); }

    private List<Entry> query(String sql, String from, String to) {
        List<Entry> out = new ArrayList<>();
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            if (from != null) ps.setString(1, from);
            if (to != null) ps.setString(2, to);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String ws = rs.getString("window_start");
                    String we = rs.getString("window_end");
                    out.add(new Entry(rs.getString("model_name"), rs.getString("base_url_host"),
                            rs.getString("request_id"), rs.getInt("input_event_count"),
                            ws == null ? null : Times.parse(ws), we == null ? null : Times.parse(we),
                            rs.getLong("elapsed_ms"), rs.getInt("degraded") == 1,
                            rs.getString("failure_class"), rs.getString("conclusion_summary"),
                            Times.parse(rs.getString("called_at"))));
                }
            }
        } catch (SQLException e) { throw new IllegalStateException(e); }
        return out;
    }

    /** 供测试核对：库中所有文本是否含某段原始报文。 */
    public boolean containsRaw(String needle) {
        for (Entry e : all()) {
            if ((e.conclusionSummary() != null && e.conclusionSummary().contains(needle))
                    || (e.failureClass() != null && e.failureClass().contains(needle))) return true;
        }
        return false;
    }

    public int count() {
        try (PreparedStatement ps = db.connection().prepareStatement("SELECT COUNT(*) FROM model_call");
             ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        catch (SQLException e) { throw new IllegalStateException(e); }
    }
}
