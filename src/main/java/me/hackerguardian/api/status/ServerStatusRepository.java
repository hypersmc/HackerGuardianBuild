package me.hackerguardian.api.status;

import me.hackerguardian.database.DatabaseType;
import me.hackerguardian.database.SqlSchema;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Shared, secret-free operational heartbeat table used by the proxy API. */
public final class ServerStatusRepository {

    private final DataSource dataSource;

    public ServerStatusRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void ensureTable() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "CREATE TABLE IF NOT EXISTS hg_server_status ("
                             + "server_name VARCHAR(64) PRIMARY KEY,"
                             + "plugin_version VARCHAR(64) NOT NULL,"
                             + "minecraft_version VARCHAR(64) NOT NULL,"
                             + "players_online INT NOT NULL DEFAULT 0,"
                             + "detection_enabled BOOLEAN NOT NULL DEFAULT FALSE,"
                             + "learning_enabled BOOLEAN NOT NULL DEFAULT FALSE,"
                             + "synthetic_probes BOOLEAN NOT NULL DEFAULT FALSE,"
                             + "last_seen_ms BIGINT NOT NULL"
                             + ")"
             )) {
            statement.executeUpdate();
        }
    }

    public void heartbeat(Status status) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseType type = SqlSchema.detectType(connection);
            String sql;
            if (type == DatabaseType.POSTGRESQL) {
                sql = "INSERT INTO hg_server_status "
                        + "(server_name, plugin_version, minecraft_version, players_online, detection_enabled, learning_enabled, synthetic_probes, last_seen_ms) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (server_name) DO UPDATE SET "
                        + "plugin_version=EXCLUDED.plugin_version, minecraft_version=EXCLUDED.minecraft_version, "
                        + "players_online=EXCLUDED.players_online, detection_enabled=EXCLUDED.detection_enabled, "
                        + "learning_enabled=EXCLUDED.learning_enabled, synthetic_probes=EXCLUDED.synthetic_probes, "
                        + "last_seen_ms=EXCLUDED.last_seen_ms";
            } else {
                sql = "INSERT INTO hg_server_status "
                        + "(server_name, plugin_version, minecraft_version, players_online, detection_enabled, learning_enabled, synthetic_probes, last_seen_ms) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE "
                        + "plugin_version=VALUES(plugin_version), minecraft_version=VALUES(minecraft_version), "
                        + "players_online=VALUES(players_online), detection_enabled=VALUES(detection_enabled), "
                        + "learning_enabled=VALUES(learning_enabled), synthetic_probes=VALUES(synthetic_probes), "
                        + "last_seen_ms=VALUES(last_seen_ms)";
            }

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, status);
                statement.executeUpdate();
            }
        }
    }

    public void markOffline(String serverName) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE hg_server_status SET players_online=0, last_seen_ms=0 WHERE server_name=?"
             )) {
            statement.setString(1, serverName);
            statement.executeUpdate();
        }
    }

    public List<Status> list() throws SQLException {
        List<Status> statuses = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT server_name, plugin_version, minecraft_version, players_online, "
                             + "detection_enabled, learning_enabled, synthetic_probes, last_seen_ms "
                             + "FROM hg_server_status ORDER BY server_name ASC"
             );
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                statuses.add(new Status(
                        result.getString("server_name"),
                        result.getString("plugin_version"),
                        result.getString("minecraft_version"),
                        result.getInt("players_online"),
                        result.getBoolean("detection_enabled"),
                        result.getBoolean("learning_enabled"),
                        result.getBoolean("synthetic_probes"),
                        result.getLong("last_seen_ms")
                ));
            }
        }
        return statuses;
    }

    private static void bind(PreparedStatement statement, Status status) throws SQLException {
        statement.setString(1, status.serverName());
        statement.setString(2, status.pluginVersion());
        statement.setString(3, status.minecraftVersion());
        statement.setInt(4, status.playersOnline());
        statement.setBoolean(5, status.detectionEnabled());
        statement.setBoolean(6, status.learningEnabled());
        statement.setBoolean(7, status.syntheticProbes());
        statement.setLong(8, status.lastSeenMs());
    }

    public record Status(
            String serverName,
            String pluginVersion,
            String minecraftVersion,
            int playersOnline,
            boolean detectionEnabled,
            boolean learningEnabled,
            boolean syntheticProbes,
            long lastSeenMs
    ) {}
}
