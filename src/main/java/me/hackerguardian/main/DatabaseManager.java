package me.hackerguardian.main;

import me.hackerguardian.Util.TicketPayload;
import me.hackerguardian.database.CoreDatabaseSchema;
import me.hackerguardian.database.DatabaseSettings;
import me.hackerguardian.database.DatabaseType;
import me.hackerguardian.database.HikariDatabase;
import org.bukkit.Bukkit;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Paper-side owner of HackerGuardian's shared SQL pool. */
public final class DatabaseManager {

    private final HackerGuardian plugin;
    private HikariDatabase database;

    public DatabaseManager(HackerGuardian plugin) {
        this.plugin = plugin;
    }

    public boolean init() {
        final DatabaseSettings settings;
        try {
            settings = readSettings();
        } catch (Exception e) {
            plugin.getLogger().severe("Invalid database.yml: " + e.getMessage());
            Bukkit.getPluginManager().disablePlugin(plugin);
            return false;
        }

        if (settings.hasPlaceholderCredentials()) {
            printFirstBootMessage();
            Bukkit.getPluginManager().disablePlugin(plugin);
            return false;
        }

        try {
            database = new HikariDatabase(settings);
            database.start();
            try (Connection c = database.connection()) {
                CoreDatabaseSchema.ensure(c);
            }
            plugin.getLogger().info("Database pool initialized: " + settings.type()
                    + " @ " + settings.host() + ":" + settings.port() + "/" + settings.database());
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("Unable to initialize " + settings.type() + " database: " + e.getMessage());
            if (plugin.getConfig().getBoolean("debug")) e.printStackTrace();
            shutdown();
            Bukkit.getPluginManager().disablePlugin(plugin);
            return false;
        }
    }

    private DatabaseSettings readSettings() {
        DatabaseType type = DatabaseType.parse(plugin.getConfig().getString("Database.type", "MYSQL"));
        return new DatabaseSettings(
                type,
                plugin.getConfig().getString("Database.host", "127.0.0.1"),
                plugin.getConfig().getInt("Database.port", type.defaultPort()),
                plugin.getConfig().getString("Database.name", "hackerguardian"),
                plugin.getConfig().getString("Database.username", "changeme"),
                plugin.getConfig().getString("Database.password", "changeme"),
                plugin.getConfig().getBoolean("Database.ssl", false),
                plugin.getConfig().getInt("Database.pool.maximum_size", 10),
                plugin.getConfig().getInt("Database.pool.minimum_idle", 2),
                plugin.getConfig().getLong("Database.pool.connection_timeout_ms", 10_000L),
                plugin.getConfig().getLong("Database.pool.validation_timeout_ms", 5_000L),
                plugin.getConfig().getLong("Database.pool.idle_timeout_ms", 60_000L),
                plugin.getConfig().getLong("Database.pool.max_lifetime_ms", 600_000L)
        );
    }

    private void printFirstBootMessage() {
        plugin.getLogger().warning("============================================================");
        plugin.getLogger().warning("HackerGuardian database setup is required before first start.");
        plugin.getLogger().warning("Edit: " + new java.io.File(plugin.getDataFolder(), "database.yml").getPath());
        plugin.getLogger().warning("Supported database types: MYSQL, POSTGRESQL");
        plugin.getLogger().warning("Default ports: MySQL 3306, PostgreSQL 5432");
        plugin.getLogger().warning("Set Database.username/password, then restart the server.");
        plugin.getLogger().warning("============================================================");
    }

    public void shutdown() {
        if (database != null) {
            database.close();
            database = null;
            plugin.getLogger().info("Database pool closed.");
        }
    }

    public DataSource getDataSource() {
        return database == null ? null : database.dataSource();
    }

    public DatabaseType getDatabaseType() {
        return database == null ? null : database.type();
    }

    public boolean consumeTicket(TicketPayload payload, long nowMs) {
        String sql = "UPDATE hg_player_tickets SET used_at = ? " +
                "WHERE ticket_id = ? AND player_uuid = ? AND target_server = ? " +
                "AND used_at IS NULL AND expires_at > ?";

        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, nowMs);
            ps.setString(2, payload.ticketId);
            ps.setString(3, payload.playerUuid);
            ps.setString(4, payload.targetServer);
            ps.setLong(5, nowMs);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            plugin.getLogger().warning("consumeTicket SQL error: " + e.getMessage());
            return false;
        }
    }

    public void cleanupTickets(long olderThanMs) {
        long cutoff = System.currentTimeMillis() - olderThanMs;
        String sql = "DELETE FROM hg_player_tickets " +
                "WHERE expires_at < ? OR (used_at IS NOT NULL AND used_at < ?)";

        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, cutoff);
            ps.setLong(2, cutoff);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("cleanupTickets SQL error: " + e.getMessage());
        }
    }
}
