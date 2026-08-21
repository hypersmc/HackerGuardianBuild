package me.hackerguardian.main.moderation.punish;

import me.hackerguardian.database.DatabaseType;
import me.hackerguardian.database.SqlSchema;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class PunishmentRepository {

    private final DataSource ds;

    public PunishmentRepository(DataSource ds) {
        this.ds = ds;
    }

    public void ensureTables() throws SQLException {
        try (Connection c = ds.getConnection()) {
            DatabaseType type = SqlSchema.detectType(c);
            String generatedId = type.generatedIdColumn();

            try (PreparedStatement ps = c.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS hg_punishments (" +
                            "id " + generatedId + "," +
                            "type VARCHAR(16) NOT NULL," +
                            "target_uuid CHAR(36) NOT NULL," +
                            "target_name VARCHAR(16) NOT NULL," +
                            "actor_uuid CHAR(36) NOT NULL," +
                            "actor_name VARCHAR(16) NOT NULL," +
                            "reason TEXT NOT NULL," +
                            "created_at BIGINT NOT NULL," +
                            "expires_at BIGINT NULL," +
                            "active BOOLEAN NOT NULL DEFAULT TRUE," +
                            "scope VARCHAR(16) NOT NULL DEFAULT 'SERVER'," +
                            "scope_server VARCHAR(64) NULL" +
                            ")"
            )) {
                ps.executeUpdate();
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS hg_ip_bans (" +
                            "id " + generatedId + "," +
                            "ip VARCHAR(64) NOT NULL," +
                            "actor_uuid CHAR(36) NOT NULL," +
                            "actor_name VARCHAR(16) NOT NULL," +
                            "reason TEXT NOT NULL," +
                            "created_at BIGINT NOT NULL," +
                            "expires_at BIGINT NULL," +
                            "active BOOLEAN NOT NULL DEFAULT TRUE," +
                            "scope VARCHAR(16) NOT NULL DEFAULT 'SERVER'," +
                            "scope_server VARCHAR(64) NULL" +
                            ")"
            )) {
                ps.executeUpdate();
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS hg_moderation_log (" +
                            "id " + generatedId + "," +
                            "action VARCHAR(16) NOT NULL," +
                            "target_uuid CHAR(36) NULL," +
                            "target_name VARCHAR(16) NULL," +
                            "target_ip VARCHAR(64) NULL," +
                            "actor_uuid CHAR(36) NOT NULL," +
                            "actor_name VARCHAR(16) NOT NULL," +
                            "reason TEXT NULL," +
                            "created_at BIGINT NOT NULL," +
                            "expires_at BIGINT NULL," +
                            "related_id BIGINT NULL," +
                            "scope VARCHAR(16) NOT NULL DEFAULT 'SERVER'," +
                            "scope_server VARCHAR(64) NULL" +
                            ")"
            )) {
                ps.executeUpdate();
            }

            SqlSchema.ensureIndex(c, "hg_punishments", "idx_hg_punishments_target_type_active",
                    "target_uuid, type, active");
            SqlSchema.ensureIndex(c, "hg_punishments", "idx_hg_punishments_active_expires",
                    "active, expires_at");
            SqlSchema.ensureIndex(c, "hg_punishments", "idx_hg_punishments_created_at", "created_at");
            SqlSchema.ensureIndex(c, "hg_punishments", "idx_hg_punishments_scope", "scope, scope_server");

            // Deliberately non-unique. We retain historical inactive IP-ban rows.
            SqlSchema.ensureIndex(c, "hg_ip_bans", "idx_hg_ip_bans_ip_active", "ip, active");
            SqlSchema.ensureIndex(c, "hg_ip_bans", "idx_hg_ip_bans_active_expires", "active, expires_at");
            SqlSchema.ensureIndex(c, "hg_ip_bans", "idx_hg_ip_bans_scope", "scope, scope_server");

            SqlSchema.ensureIndex(c, "hg_moderation_log", "idx_hg_mod_log_target_uuid", "target_uuid");
            SqlSchema.ensureIndex(c, "hg_moderation_log", "idx_hg_mod_log_target_ip", "target_ip");
            SqlSchema.ensureIndex(c, "hg_moderation_log", "idx_hg_mod_log_action", "action");
            SqlSchema.ensureIndex(c, "hg_moderation_log", "idx_hg_mod_log_created_at", "created_at");
            SqlSchema.ensureIndex(c, "hg_moderation_log", "idx_hg_mod_log_scope", "scope, scope_server");
        }
    }

    public long createPunishment(PunishmentType type,
                                 String targetUuid, String targetName,
                                 String actorUuid, String actorName,
                                 String reason,
                                 long createdAt, Long expiresAt,
                                 PunishScope scope, String scopeServer) throws SQLException {

        String sql =
                "INSERT INTO hg_punishments (type, target_uuid, target_name, actor_uuid, actor_name, reason, created_at, expires_at, active, scope, scope_server) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?, ?)";

        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            ps.setString(1, type.name());
            ps.setString(2, targetUuid);
            ps.setString(3, targetName);
            ps.setString(4, actorUuid);
            ps.setString(5, actorName);
            ps.setString(6, reason);
            ps.setLong(7, createdAt);
            if (expiresAt == null) ps.setNull(8, Types.BIGINT); else ps.setLong(8, expiresAt);

            ps.setString(9, scope.name());
            if (scope == PunishScope.SERVER) ps.setString(10, scopeServer);
            else ps.setNull(10, Types.VARCHAR);

            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
            throw new SQLException("No generated key for punishment insert");
        }
    }

    public boolean deactivateActivePunishments(PunishmentType type, String targetUuid, long nowMs) throws SQLException {
        String sql = "UPDATE hg_punishments SET active = FALSE WHERE type = ? AND target_uuid = ? AND active = TRUE";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, type.name());
            ps.setString(2, targetUuid);
            return ps.executeUpdate() > 0;
        }
    }

    public long createIpBan(String ip, String actorUuid,
                            String actorName, String reason,
                            long createdAt, Long expiresAt,
                            PunishScope scope, String scopeServer) throws SQLException {
        String sql =
                "INSERT INTO hg_ip_bans (ip, actor_uuid, actor_name, reason, created_at, expires_at, active, scope, scope_server) " +
                        "VALUES (?, ?, ?, ?, ?, ?, TRUE, ?, ?)";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, ip);
            ps.setString(2, actorUuid);
            ps.setString(3, actorName);
            ps.setString(4, reason);
            ps.setLong(5, createdAt);
            if (expiresAt == null) ps.setNull(6, Types.BIGINT); else ps.setLong(6, expiresAt);

            ps.setString(7, scope.name());
            if (scope == PunishScope.SERVER) ps.setString(8, scopeServer);
            else ps.setNull(8, Types.VARCHAR);

            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
                throw new SQLException("No generated key for ip ban insert");
            }
        }
    }

    public boolean deactivateIpBan(String ip) throws SQLException {
        String sql = "UPDATE hg_ip_bans SET active = FALSE WHERE ip = ? AND active = TRUE";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, ip);
            return ps.executeUpdate() > 0;
        }
    }

    public long logAction(ModerationActionType action,
                          String targetUuidOrNull,
                          String targetNameOrNull,
                          String targetIpOrNull,
                          String actorUuid,
                          String actorName,
                          String reasonOrNull,
                          long createdAt,
                          Long expiresAtOrNull,
                          Long relatedIdOrNull,
                          PunishScope scope, String scopeServer) throws SQLException {

        String sql =
                "INSERT INTO hg_moderation_log " +
                        "(action, target_uuid, target_name, target_ip, actor_uuid, actor_name, reason, created_at, expires_at, related_id, scope, scope_server) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            ps.setString(1, action.name());

            if (targetUuidOrNull == null) ps.setNull(2, Types.CHAR); else ps.setString(2, targetUuidOrNull);
            if (targetNameOrNull == null) ps.setNull(3, Types.VARCHAR); else ps.setString(3, targetNameOrNull);
            if (targetIpOrNull == null) ps.setNull(4, Types.VARCHAR); else ps.setString(4, targetIpOrNull);

            ps.setString(5, actorUuid);
            ps.setString(6, actorName);

            if (reasonOrNull == null) ps.setNull(7, Types.LONGVARCHAR); else ps.setString(7, reasonOrNull);

            ps.setLong(8, createdAt);

            if (expiresAtOrNull == null) ps.setNull(9, Types.BIGINT); else ps.setLong(9, expiresAtOrNull);
            if (relatedIdOrNull == null) ps.setNull(10, Types.BIGINT); else ps.setLong(10, relatedIdOrNull);

            ps.setString(11, scope.name());
            if (scope == PunishScope.SERVER) ps.setString(12, scopeServer);
            else ps.setNull(12, Types.VARCHAR);

            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
                throw new SQLException("No generated key for moderation log insert");
            }
        }
    }

    public Optional<PunishmentRow> getActivePunishment(PunishmentType type, String targetUuid, long nowMs,
                                                       boolean behindProxy, String serverName) throws SQLException {

        String scopeClause = behindProxy
                ? " AND (scope = 'WIDE' OR (scope = 'SERVER' AND scope_server = ?)) "
                : " AND (scope = 'SERVER' AND (scope_server = ? OR scope_server IS NULL)) ";

        String sql =
                "SELECT * FROM hg_punishments " +
                        "WHERE type = ? AND target_uuid = ? AND active = TRUE " +
                        "AND (expires_at IS NULL OR expires_at > ?) " +
                        scopeClause +
                        "ORDER BY created_at DESC LIMIT 1";

        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {

            ps.setString(1, type.name());
            ps.setString(2, targetUuid);
            ps.setLong(3, nowMs);
            ps.setString(4, serverName);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(PunishmentRow.from(rs));
            }
        }
    }

    public Optional<IpBanRow> getActiveIpBan(String ip, long nowMs,
                                             boolean behindProxy, String serverName) throws SQLException {

        String scopeClause = behindProxy
                ? " AND (scope = 'WIDE' OR (scope = 'SERVER' AND scope_server = ?)) "
                : " AND (scope = 'SERVER' AND (scope_server = ? OR scope_server IS NULL)) ";

        String sql =
                "SELECT * FROM hg_ip_bans " +
                        "WHERE ip = ? AND active = TRUE AND (expires_at IS NULL OR expires_at > ?) " +
                        scopeClause +
                        "ORDER BY created_at DESC LIMIT 1";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, ip);
            ps.setLong(2, nowMs);
            ps.setString(3, serverName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(IpBanRow.from(rs));
            }
        }
    }

    public List<PunishmentRow> listPunishmentsForTarget(String targetUuid, int limit) throws SQLException {
        String sql = "SELECT * FROM hg_punishments WHERE target_uuid = ? ORDER BY created_at DESC LIMIT ?";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, targetUuid);
            ps.setInt(2, limit);
            List<PunishmentRow> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(PunishmentRow.from(rs));
            }
            return out;
        }
    }

    public int cleanupExpired(long nowMs) throws SQLException {
        int total = 0;
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE hg_punishments SET active = FALSE WHERE active = TRUE AND expires_at IS NOT NULL AND expires_at <= ?"
            )) {
                ps.setLong(1, nowMs);
                total += ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE hg_ip_bans SET active = FALSE WHERE active = TRUE AND expires_at IS NOT NULL AND expires_at <= ?"
            )) {
                ps.setLong(1, nowMs);
                total += ps.executeUpdate();
            }
        }
        return total;
    }

    public record IpBanRow(
            long id,
            String ip,
            String actorUuid,
            String actorName,
            String reason,
            long createdAt,
            Long expiresAt,
            boolean active
    ) {
        static IpBanRow from(ResultSet rs) throws SQLException {
            long exp = rs.getLong("expires_at");
            Long expiresAt = rs.wasNull() ? null : exp;

            return new IpBanRow(
                    rs.getLong("id"),
                    rs.getString("ip"),
                    rs.getString("actor_uuid"),
                    rs.getString("actor_name"),
                    rs.getString("reason"),
                    rs.getLong("created_at"),
                    expiresAt,
                    rs.getBoolean("active")
            );
        }
    }
}
