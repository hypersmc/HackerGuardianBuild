package me.hackerguardian.bungee.utils;

import me.hackerguardian.Util.TicketPayload;
import me.hackerguardian.bungee.HackerGuardianB;
import me.hackerguardian.bungee.moderation.BanRow;
import me.hackerguardian.database.CoreDatabaseSchema;
import me.hackerguardian.database.DatabaseSettings;
import me.hackerguardian.database.DatabaseType;
import me.hackerguardian.database.HikariDatabase;
import net.md_5.bungee.config.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Bungee/FlameCord owner of the shared HackerGuardian SQL pool. */
public final class BDatabase {

    private final HackerGuardianB plugin;
    private final Logger logger;
    private HikariDatabase database;

    public BDatabase(HackerGuardianB plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public boolean init() {
        final DatabaseSettings settings;
        try {
            settings = readSettings(plugin.getConfiguration());
        } catch (Exception e) {
            logger.severe("Invalid database configuration: " + e.getMessage());
            return false;
        }

        if (settings.hasPlaceholderCredentials()) {
            printFirstBootMessage();
            return false;
        }

        try {
            database = new HikariDatabase(settings);
            database.start();
            try (Connection c = database.connection()) {
                CoreDatabaseSchema.ensure(c);
            }
            logger.info("Database pool initialized: " + settings.type()
                    + " @ " + settings.host() + ":" + settings.port() + "/" + settings.database());
            return true;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Unable to initialize " + settings.type() + " database", e);
            shutdown();
            return false;
        }
    }

    private DatabaseSettings readSettings(Configuration cfg) {
        DatabaseType type = DatabaseType.parse(cfg.getString("Database.type", "MYSQL"));
        return new DatabaseSettings(
                type,
                cfg.getString("Database.host", "127.0.0.1"),
                cfg.getInt("Database.port", type.defaultPort()),
                cfg.getString("Database.name", "hackerguardian"),
                cfg.getString("Database.username", "changeme"),
                cfg.getString("Database.password", "changeme"),
                cfg.getBoolean("Database.ssl", false),
                cfg.getInt("Database.pool.maximum_size", 10),
                cfg.getInt("Database.pool.minimum_idle", 2),
                cfg.getLong("Database.pool.connection_timeout_ms", 10_000L),
                cfg.getLong("Database.pool.validation_timeout_ms", 5_000L),
                cfg.getLong("Database.pool.idle_timeout_ms", 60_000L),
                cfg.getLong("Database.pool.max_lifetime_ms", 600_000L)
        );
    }

    private void printFirstBootMessage() {
        logger.warning("============================================================");
        logger.warning("HackerGuardian proxy database setup is required before first start.");
        logger.warning("Edit: " + new java.io.File(plugin.getDataFolder(), "configbungee.yml").getPath());
        logger.warning("Supported database types: MYSQL, POSTGRESQL");
        logger.warning("Default ports: MySQL 3306, PostgreSQL 5432");
        logger.warning("Set Database.username/password, then restart the proxy.");
        logger.warning("============================================================");
    }

    public Connection getConnection() throws SQLException {
        if (database == null) throw new SQLException("Database pool is not initialized");
        return database.connection();
    }

    public DataSource getDataSource() {
        return database == null ? null : database.dataSource();
    }

    public DatabaseType getDatabaseType() {
        return database == null ? null : database.type();
    }

    public void insertTicket(TicketPayload payload) throws SQLException {
        String sql = "INSERT INTO hg_player_tickets " +
                "(ticket_id, player_uuid, player_name, issued_at, expires_at, used_at, target_server) " +
                "VALUES (?,?,?,?,?,NULL,?)";

        try (Connection db = getConnection();
             PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setString(1, payload.ticketId);
            ps.setString(2, payload.playerUuid);
            ps.setString(3, payload.playerName);
            ps.setLong(4, payload.issuedAt);
            ps.setLong(5, payload.expiresAt);
            ps.setString(6, payload.targetServer);
            ps.executeUpdate();
        }
    }

    public void cleanupExpired() {
        String cleanup = "DELETE FROM hg_player_tickets " +
                "WHERE expires_at < ? OR (used_at IS NOT NULL AND used_at < ?)";
        long cutoff = System.currentTimeMillis() - 60_000L;

        try (Connection db = getConnection();
             PreparedStatement ps = db.prepareStatement(cleanup)) {
            ps.setLong(1, cutoff);
            ps.setLong(2, cutoff);
            ps.executeUpdate();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error cleaning expired secure-link tickets: " + e.getMessage());
        }
    }

    public BanRow getActivePlayerBanWide(String playerUuid, long now) throws SQLException {
        String sql = "SELECT id, reason, expires_at FROM hg_punishments " +
                "WHERE type='BAN' AND target_uuid=? AND active=TRUE " +
                "AND (expires_at IS NULL OR expires_at > ?) AND scope='WIDE' " +
                "ORDER BY created_at DESC LIMIT 1";
        return findBan(sql, playerUuid, now, null);
    }

    public BanRow getActivePlayerBanServer(String playerUuid, long now, String serverName) throws SQLException {
        String sql = "SELECT id, reason, expires_at FROM hg_punishments " +
                "WHERE type='BAN' AND target_uuid=? AND active=TRUE " +
                "AND (expires_at IS NULL OR expires_at > ?) " +
                "AND scope='SERVER' AND scope_server=? " +
                "ORDER BY created_at DESC LIMIT 1";
        return findBan(sql, playerUuid, now, serverName);
    }

    public BanRow getActiveIpBanWide(String ip, long now) throws SQLException {
        String sql = "SELECT id, reason, expires_at FROM hg_ip_bans " +
                "WHERE ip=? AND active=TRUE " +
                "AND (expires_at IS NULL OR expires_at > ?) AND scope='WIDE' " +
                "ORDER BY created_at DESC LIMIT 1";
        return findBan(sql, ip, now, null);
    }

    public BanRow getActiveIpBanServer(String ip, long now, String serverName) throws SQLException {
        String sql = "SELECT id, reason, expires_at FROM hg_ip_bans " +
                "WHERE ip=? AND active=TRUE " +
                "AND (expires_at IS NULL OR expires_at > ?) " +
                "AND scope='SERVER' AND scope_server=? " +
                "ORDER BY created_at DESC LIMIT 1";
        return findBan(sql, ip, now, serverName);
    }

    private BanRow findBan(String sql, String key, long now, String serverName) throws SQLException {
        try (Connection db = getConnection();
             PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setLong(2, now);
            if (serverName != null) ps.setString(3, serverName);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                long expires = rs.getLong("expires_at");
                Long expiresAt = rs.wasNull() ? null : expires;
                return new BanRow(rs.getLong("id"), rs.getString("reason"), expiresAt);
            }
        }
    }

    public String extractIp(net.md_5.bungee.api.connection.ProxiedPlayer player) {
        try {
            if (player.getSocketAddress() instanceof java.net.InetSocketAddress isa) {
                if (isa.getAddress() != null) return isa.getAddress().getHostAddress();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public void shutdown() {
        if (database != null) {
            database.close();
            database = null;
            logger.info("Database pool closed.");
        }
    }
}
