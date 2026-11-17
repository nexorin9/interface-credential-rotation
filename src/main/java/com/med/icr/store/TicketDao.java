package com.med.icr.store;

import com.med.icr.domain.Ticket;
import com.med.icr.domain.TicketState;
import com.med.icr.util.Times;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 处置单 DAO：幂等键唯一索引挡重复建单，冲突时返回既有单号。 */
public final class TicketDao {
    private final Database db;
    public TicketDao(Database db) { this.db = db; }

    public record InsertResult(boolean created, Ticket ticket) {}

    public InsertResult insertIfAbsent(Ticket ticket) {
        String sql = "INSERT OR IGNORE INTO ticket(idempotency_key, ticket_no, credential_id, root_cause," +
                "evidence_refs, due_at, state, created_at) VALUES(?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, ticket.idempotencyKey());
            ps.setString(2, ticket.ticketNo());
            ps.setString(3, ticket.credentialId());
            ps.setString(4, ticket.rootCause());
            ps.setString(5, String.join(";", ticket.evidenceRefs()));
            ps.setString(6, ticket.dueAt() == null ? null : Times.format(ticket.dueAt()));
            ps.setString(7, ticket.state().name());
            ps.setString(8, Times.format(Instant.now()));
            int rows = ps.executeUpdate();
            Ticket existing = findByIdempotencyKey(ticket.idempotencyKey());
            return new InsertResult(rows == 1, existing == null ? ticket : existing);
        } catch (SQLException e) {
            throw new IllegalStateException("处置单写入失败", e);
        }
    }

    public Ticket findByIdempotencyKey(String key) {
        try (PreparedStatement ps = db.connection()
                .prepareStatement("SELECT * FROM ticket WHERE idempotency_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    public List<Ticket> all() {
        List<Ticket> out = new ArrayList<>();
        try (PreparedStatement ps = db.connection().prepareStatement("SELECT * FROM ticket ORDER BY idempotency_key");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(map(rs));
        } catch (SQLException e) { throw new IllegalStateException(e); }
        return out;
    }

    public int count() {
        try (PreparedStatement ps = db.connection().prepareStatement("SELECT COUNT(*) FROM ticket");
             ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        catch (SQLException e) { throw new IllegalStateException(e); }
    }

    private Ticket map(ResultSet rs) throws SQLException {
        String due = rs.getString("due_at");
        String refs = rs.getString("evidence_refs");
        String state = rs.getString("state");
        return new Ticket(rs.getString("credential_id"), rs.getString("root_cause"),
                refs == null || refs.isBlank() ? List.of() : Arrays.asList(refs.split(";")),
                due == null ? null : Times.parse(due),
                state == null ? TicketState.PENDING : TicketState.valueOf(state),
                rs.getString("idempotency_key"), rs.getString("ticket_no"));
    }
}
