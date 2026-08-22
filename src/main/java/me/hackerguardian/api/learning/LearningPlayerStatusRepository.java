package me.hackerguardian.api.learning;

import me.hackerguardian.database.DatabaseType;
import me.hackerguardian.database.SqlSchema;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Secret-free Learning Mode player projection used by the proxy API. */
public final class LearningPlayerStatusRepository {

    private final DataSource dataSource;

    public LearningPlayerStatusRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void ensureTable() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "CREATE TABLE IF NOT EXISTS hg_learning_player_status ("
                             + "server_name VARCHAR(64) NOT NULL,"
                             + "player_uuid CHAR(36) NOT NULL,"
                             + "player_name VARCHAR(16) NOT NULL,"
                             + "trusted BOOLEAN NOT NULL DEFAULT FALSE,"
                             + "active_hours DOUBLE PRECISION NOT NULL DEFAULT 0,"
                             + "first_trusted_ms BIGINT NOT NULL DEFAULT 0,"
                             + "last_seen_ms BIGINT NOT NULL DEFAULT 0,"
                             + "last_probe_ms BIGINT NOT NULL DEFAULT 0,"
                             + "baseline_mature BOOLEAN NOT NULL DEFAULT FALSE,"
                             + "updated_at_ms BIGINT NOT NULL,"
                             + "PRIMARY KEY (server_name, player_uuid)"
                             + ")"
             )) {
            statement.executeUpdate();
            SqlSchema.ensureIndex(connection, "hg_learning_player_status", "idx_hg_learning_status_player", "player_uuid");
            SqlSchema.ensureIndex(connection, "hg_learning_player_status", "idx_hg_learning_status_trusted", "trusted");
            SqlSchema.ensureIndex(connection, "hg_learning_player_status", "idx_hg_learning_status_seen", "last_seen_ms");
        }
    }

    public void upsertAll(Collection<PlayerStatus> statuses) throws SQLException {
        if (statuses == null || statuses.isEmpty()) return;
        try (Connection connection = dataSource.getConnection()) {
            DatabaseType type = SqlSchema.detectType(connection);
            String sql;
            if (type == DatabaseType.POSTGRESQL) {
                sql = "INSERT INTO hg_learning_player_status "
                        + "(server_name, player_uuid, player_name, trusted, active_hours, first_trusted_ms, last_seen_ms, last_probe_ms, baseline_mature, updated_at_ms) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (server_name, player_uuid) DO UPDATE SET "
                        + "player_name=EXCLUDED.player_name, trusted=EXCLUDED.trusted, active_hours=EXCLUDED.active_hours, "
                        + "first_trusted_ms=EXCLUDED.first_trusted_ms, last_seen_ms=EXCLUDED.last_seen_ms, "
                        + "last_probe_ms=EXCLUDED.last_probe_ms, baseline_mature=EXCLUDED.baseline_mature, updated_at_ms=EXCLUDED.updated_at_ms";
            } else {
                sql = "INSERT INTO hg_learning_player_status "
                        + "(server_name, player_uuid, player_name, trusted, active_hours, first_trusted_ms, last_seen_ms, last_probe_ms, baseline_mature, updated_at_ms) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE player_name=VALUES(player_name), trusted=VALUES(trusted), "
                        + "active_hours=VALUES(active_hours), first_trusted_ms=VALUES(first_trusted_ms), "
                        + "last_seen_ms=VALUES(last_seen_ms), last_probe_ms=VALUES(last_probe_ms), "
                        + "baseline_mature=VALUES(baseline_mature), updated_at_ms=VALUES(updated_at_ms)";
            }

            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (PlayerStatus status : statuses) {
                    statement.setString(1, status.serverName());
                    statement.setString(2, status.playerUuid());
                    statement.setString(3, status.playerName() == null ? "" : status.playerName());
                    statement.setBoolean(4, status.trusted());
                    statement.setDouble(5, status.activeHours());
                    statement.setLong(6, status.firstTrustedMs());
                    statement.setLong(7, status.lastSeenMs());
                    statement.setLong(8, status.lastProbeMs());
                    statement.setBoolean(9, status.baselineMature());
                    statement.setLong(10, status.updatedAtMs());
                    statement.addBatch();
                }
                statement.executeBatch();
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                if (e instanceof SQLException sqlException) throw sqlException;
                throw new SQLException("Unable to publish learning player status", e);
            } finally {
                connection.setAutoCommit(oldAutoCommit);
            }
        }
    }

    public List<PlayerStatus> list(String serverNameOrNull, boolean trustedOnly, int limit) throws SQLException {
        int boundedLimit = Math.max(1, Math.min(limit, 5000));
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<String> params = new ArrayList<>();
        if (serverNameOrNull != null && !serverNameOrNull.isBlank()) {
            where.append(" AND server_name=?");
            params.add(serverNameOrNull);
        }
        if (trustedOnly) where.append(" AND trusted=TRUE");

        String sql = "SELECT server_name, player_uuid, player_name, trusted, active_hours, first_trusted_ms, "
                + "last_seen_ms, last_probe_ms, baseline_mature, updated_at_ms FROM hg_learning_player_status"
                + where + " ORDER BY active_hours DESC, last_seen_ms DESC LIMIT ?";
        List<PlayerStatus> out = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (String param : params) statement.setString(index++, param);
            statement.setInt(index, boundedLimit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    out.add(new PlayerStatus(
                            result.getString("server_name"),
                            result.getString("player_uuid"),
                            result.getString("player_name"),
                            result.getBoolean("trusted"),
                            result.getDouble("active_hours"),
                            result.getLong("first_trusted_ms"),
                            result.getLong("last_seen_ms"),
                            result.getLong("last_probe_ms"),
                            result.getBoolean("baseline_mature"),
                            result.getLong("updated_at_ms")
                    ));
                }
            }
        }
        return out;
    }

    public PlayerStatus get(String playerUuid, String serverNameOrNull) throws SQLException {
        String sql = "SELECT server_name, player_uuid, player_name, trusted, active_hours, first_trusted_ms, "
                + "last_seen_ms, last_probe_ms, baseline_mature, updated_at_ms FROM hg_learning_player_status "
                + "WHERE player_uuid=?" + (serverNameOrNull == null ? "" : " AND server_name=?")
                + " ORDER BY updated_at_ms DESC LIMIT 1";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid);
            if (serverNameOrNull != null) statement.setString(2, serverNameOrNull);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                return new PlayerStatus(
                        result.getString("server_name"),
                        result.getString("player_uuid"),
                        result.getString("player_name"),
                        result.getBoolean("trusted"),
                        result.getDouble("active_hours"),
                        result.getLong("first_trusted_ms"),
                        result.getLong("last_seen_ms"),
                        result.getLong("last_probe_ms"),
                        result.getBoolean("baseline_mature"),
                        result.getLong("updated_at_ms")
                );
            }
        }
    }

    public record PlayerStatus(
            String serverName,
            String playerUuid,
            String playerName,
            boolean trusted,
            double activeHours,
            long firstTrustedMs,
            long lastSeenMs,
            long lastProbeMs,
            boolean baselineMature,
            long updatedAtMs
    ) {}
}
