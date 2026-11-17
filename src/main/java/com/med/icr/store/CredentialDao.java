package com.med.icr.store;

import com.med.icr.domain.Credential;
import com.med.icr.domain.CredentialState;
import com.med.icr.domain.CredentialType;
import com.med.icr.util.Times;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 台账缓存 DAO：只写元数据与指纹，不写任何 secret 字段。 */
public final class CredentialDao {
    private final Database db;
    public CredentialDao(Database db) { this.db = db; }

    public void upsert(Credential c) {
        String sql = "INSERT INTO credential(credential_id, channel_code, credential_type, expires_at," +
                "owner_dept, owner, rotation_entry, last_verified_at, fingerprint, state, channel_codes, downstream)" +
                " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)" +
                " ON CONFLICT(credential_id) DO UPDATE SET channel_code=excluded.channel_code," +
                "credential_type=excluded.credential_type, expires_at=excluded.expires_at," +
                "owner_dept=excluded.owner_dept, owner=excluded.owner, rotation_entry=excluded.rotation_entry," +
                "last_verified_at=excluded.last_verified_at, fingerprint=excluded.fingerprint," +
                "state=excluded.state, channel_codes=excluded.channel_codes, downstream=excluded.downstream";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, c.credentialId());
            ps.setString(2, c.channelCode());
            ps.setString(3, c.credentialType().code());
            ps.setString(4, c.expiresAt() == null ? null : Times.format(c.expiresAt()));
            ps.setString(5, c.ownerDept());
            ps.setString(6, c.owner());
            ps.setString(7, c.rotationEntry());
            ps.setString(8, c.lastVerifiedAt() == null ? null : Times.format(c.lastVerifiedAt()));
            ps.setString(9, c.fingerprint());
            ps.setString(10, c.state().name());
            ps.setString(11, String.join(";", c.channelCodes()));
            ps.setString(12, String.join(";", c.downstreamSystems()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("凭据 upsert 失败", e);
        }
    }

    public List<Credential> findByFingerprint(String fingerprint) {
        return query("SELECT * FROM credential WHERE fingerprint = ?", fingerprint);
    }

    /** 包含已过期与临期（expires_at 非空且 ≤ now + warnDays）。 */
    public List<Credential> findExpiring(Instant now, int warnDays) {
        Instant bound = now.plusSeconds((long) warnDays * 86400L);
        List<Credential> result = new ArrayList<>();
        for (Credential c : all()) {
            if (c.expiresAt() != null && !c.expiresAt().isAfter(bound)) result.add(c);
        }
        result.sort((a, b) -> a.expiresAt().compareTo(b.expiresAt()));
        return result;
    }

    public List<Credential> all() { return query("SELECT * FROM credential ORDER BY credential_id", null); }

    public int count() {
        try (PreparedStatement ps = db.connection().prepareStatement("SELECT COUNT(*) FROM credential");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    private List<Credential> query(String sql, String param) {
        List<Credential> out = new ArrayList<>();
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            if (param != null) ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("凭据查询失败", e);
        }
        return out;
    }

    private Credential map(ResultSet rs) throws SQLException {
        String exp = rs.getString("expires_at");
        String verified = rs.getString("last_verified_at");
        String channels = rs.getString("channel_codes");
        String downstream = rs.getString("downstream");
        String state = rs.getString("state");
        return new Credential(rs.getString("credential_id"), rs.getString("channel_code"),
                CredentialType.parse(rs.getString("credential_type")),
                exp == null ? null : Times.parse(exp), rs.getString("owner_dept"), rs.getString("owner"),
                rs.getString("rotation_entry"), verified == null ? null : Times.parse(verified),
                rs.getString("fingerprint"),
                state == null ? CredentialState.ACTIVE : CredentialState.valueOf(state),
                channels == null || channels.isBlank() ? null : Arrays.asList(channels.split(";")),
                downstream == null || downstream.isBlank() ? null : Arrays.asList(downstream.split(";")));
    }
}
