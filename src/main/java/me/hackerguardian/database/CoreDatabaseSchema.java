package me.hackerguardian.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Schema shared by proxy and backend components. */
public final class CoreDatabaseSchema {

    private CoreDatabaseSchema() {}

    public static void ensure(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "CREATE TABLE IF NOT EXISTS hg_player_tickets (" +
                        "ticket_id CHAR(36) PRIMARY KEY," +
                        "player_uuid CHAR(36) NOT NULL," +
                        "player_name VARCHAR(16) NOT NULL," +
                        "issued_at BIGINT NOT NULL," +
                        "expires_at BIGINT NOT NULL," +
                        "used_at BIGINT NULL," +
                        "target_server VARCHAR(64) NOT NULL" +
                        ")"
        )) {
            ps.executeUpdate();
        }

        SqlSchema.ensureIndex(c, "hg_player_tickets", "idx_hg_tickets_player_uuid", "player_uuid");
        SqlSchema.ensureIndex(c, "hg_player_tickets", "idx_hg_tickets_expires_at", "expires_at");
        SqlSchema.ensureIndex(c, "hg_player_tickets", "idx_hg_tickets_used_at", "used_at");
    }
}
