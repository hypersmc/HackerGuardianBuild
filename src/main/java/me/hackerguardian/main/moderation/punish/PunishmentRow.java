package me.hackerguardian.main.moderation.punish;

import java.sql.ResultSet;
import java.sql.SQLException;

public record PunishmentRow(
        long id,
        PunishmentType type,
        String targetUuid,
        String targetName,
        String actorUuid,
        String actorName,
        String reason,
        long createdAt,
        Long expiresAt,
        boolean active
) {
    public static PunishmentRow from(ResultSet rs) throws SQLException {
        String typeStr = rs.getString("type");
        PunishmentType type = PunishmentType.valueOf(typeStr);

        long exp = rs.getLong("expires_at");
        Long expiresAt = rs.wasNull() ? null : exp;

        return new PunishmentRow(
                rs.getLong("id"),
                type,
                rs.getString("target_uuid"),
                rs.getString("target_name"),
                rs.getString("actor_uuid"),
                rs.getString("actor_name"),
                rs.getString("reason"),
                rs.getLong("created_at"),
                expiresAt,
                rs.getBoolean("active")
        );
    }

    public boolean isExpired(long nowMs) {
        return expiresAt != null && nowMs >= expiresAt;
    }
}