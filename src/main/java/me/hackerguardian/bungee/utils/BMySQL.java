package me.hackerguardian.bungee.utils;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import me.hackerguardian.Util.TicketPayload;
import me.hackerguardian.bungee.HackerGuardianB;
import me.hackerguardian.bungee.moderation.BanRow;
import me.hackerguardian.main.utils.ErrorHandler;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.plugin.Plugin;

import javax.sql.DataSource;
import java.sql.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author JumpWatch on 19-04-2024
 * @Project HackerGuardian
 * v1.0.0
 */
public class BMySQL {

    private static HikariDataSource dataSource;

    HackerGuardianB main = HackerGuardianB.getInstance();
    Logger logger = Logger.getLogger("HGBungee_Link_Database");

    private final String host = main.configuration.getString("SQLHost");
    private final String port = main.configuration.getString("SQLPort");
    private final String database = main.configuration.getString("SQLDatabaseName");
    private final String user = main.configuration.getString("SQLUsername");
    private final String pass = main.configuration.getString("SQLPassword");

    /* ------------------------------------------------------------
     * Core setup
     * ------------------------------------------------------------ */

    public void setupCoreSystem() {

        if (this.user.equals("changeme") && this.pass.equals("changeme")) {
            logger.info("");
            logger.info("---------- Core MySQL ----------");
            logger.info("Please setup MySQL in the config. When done reboot the server.");
            logger.info("Disabling plugin. Please reboot to reload config.");
            logger.info("-----------------------------");
            logger.info("");
            PluginDisabler.disablePlugin(HackerGuardianB.getInstance());
            return;
        }

        HikariConfig config = new HikariConfig();

        String jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false"
                + "&serverTimezone=UTC"
                + "&characterEncoding=utf8"
                + "&useUnicode=true";

        config.setJdbcUrl(jdbcUrl);
        config.setUsername(user);
        config.setPassword(pass);

        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(10_000);
        config.setIdleTimeout(300_000);
        config.setMaxLifetime(1_800_000);

        // This helps detect dead connections
        config.setConnectionTestQuery("SELECT 1");

        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        dataSource = new HikariDataSource(config);

        // Test connection
        try (Connection conn = dataSource.getConnection()) {
            logger.info("Connection to MySQL (HikariCP) successful.");
        }catch (Exception ignored) {}

        // formatCoreDatabase();
    }

    /**
     * Keeps compatibility with existing calls
     */
    public void checkdbconnection() {
        ProxyServer.getInstance().getScheduler().runAsync(HackerGuardianB.getInstance(), () -> {
            try (Connection conn = getConnection()) {
                logger.info("Connection to MySQL database successful.");
            } catch (SQLException e) {
                ErrorHandler.handleGenericException(e, "Could not connect to the database");
            }
        });
    }

    /* ------------------------------------------------------------
     * Connection helper
     * ------------------------------------------------------------ */

    public Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("HikariDataSource not initialized");
        }
        return dataSource.getConnection();
    }

    /* ------------------------------------------------------------
     * Database formatting
     * ------------------------------------------------------------ */

    public void formatCoreDatabase() {
        try (Connection db = getConnection();
             PreparedStatement checkIfExists = db.prepareStatement(
                     "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?")) {

            checkIfExists.setString(1, this.database);
            checkIfExists.setString(2, "CorePlayerStats");

            try (ResultSet resultSet = checkIfExists.executeQuery()) {
                if (resultSet.next()) {
                    int count = resultSet.getInt(1);
                    if (count > 0) {
                        logger.info("V1 HackerGuardian Installation found!");
                        logger.info("Eradicating old database tables and data!");
                        // initializeDatabaseCleanup();
                    } else {
                        logger.info("New installation!");
                        // doNewCoreDatabase();
                    }
                }
            }

            logger.info("Successfully checked tables.");
        } catch (SQLException e) {
            ErrorHandler.handleGenericException(e, "Error finding SQL Tables");
        }
    }

    /* ------------------------------------------------------------
     * Ticket handling
     * ------------------------------------------------------------ */

    public void insertTicket(TicketPayload payload) throws SQLException {
        String sql =
                "INSERT INTO " + this.database + ".hg_player_tickets " +
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

    public void cleanupExpired(Plugin plugin) {
        String cleanup =
                "DELETE FROM " + this.database + ".hg_player_tickets " +
                        "WHERE expires_at < ? OR (used_at IS NOT NULL AND used_at < ?)";

        long now = System.currentTimeMillis();

        try (Connection db = getConnection();
             PreparedStatement ps = db.prepareStatement(cleanup)) {

            ps.setLong(1, now - 60_000);
            ps.setLong(2, now - 60_000);
            ps.executeUpdate();

        } catch (Exception e) {
            logger.log(Level.SEVERE, "error cleaning up: " + e.getMessage());
        }
    }

    /* ------------------------------------------------------------
     * Ban lookups
     * ------------------------------------------------------------ */

    public BanRow getActivePlayerBanWide(String playerUuid, long now) throws SQLException {
        String sql =
                "SELECT id, reason, expires_at FROM hg_punishments " +
                        "WHERE type='BAN' AND target_uuid=? AND active=TRUE " +
                        "AND (expires_at IS NULL OR expires_at > ?) " +
                        "AND scope='WIDE' " +
                        "ORDER BY created_at DESC LIMIT 1";

        try (Connection db = getConnection();
             PreparedStatement ps = db.prepareStatement(sql)) {

            ps.setString(1, playerUuid);
            ps.setLong(2, now);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Long expiresAt = rs.getObject("expires_at", Long.class);
                return new BanRow(
                        rs.getLong("id"),
                        rs.getString("reason"),
                        expiresAt
                );
            }
        }
    }

    public BanRow getActivePlayerBanServer(String playerUuid, long now, String serverName) throws SQLException {
        String sql =
                "SELECT id, reason, expires_at FROM hg_punishments " +
                        "WHERE type='BAN' AND target_uuid=? AND active=TRUE " +
                        "AND (expires_at IS NULL OR expires_at > ?) " +
                        "AND scope='SERVER' AND scope_server=? " +
                        "ORDER BY created_at DESC LIMIT 1";

        try (Connection db = getConnection();
             PreparedStatement ps = db.prepareStatement(sql)) {

            ps.setString(1, playerUuid);
            ps.setLong(2, now);
            ps.setString(3, serverName);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Long expiresAt = rs.getObject("expires_at", Long.class);
                return new BanRow(
                        rs.getLong("id"),
                        rs.getString("reason"),
                        expiresAt
                );
            }
        }
    }

    public BanRow getActiveIpBanWide(String ip, long now) throws SQLException {
        String sql =
                "SELECT id, reason, expires_at FROM hg_ip_bans " +
                        "WHERE ip=? AND active=TRUE " +
                        "AND (expires_at IS NULL OR expires_at > ?) " +
                        "AND scope='WIDE' " +
                        "ORDER BY created_at DESC LIMIT 1";

        try (Connection db = getConnection();
             PreparedStatement ps = db.prepareStatement(sql)) {

            ps.setString(1, ip);
            ps.setLong(2, now);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Long expiresAt = rs.getObject("expires_at", Long.class);
                return new BanRow(
                        rs.getLong("id"),
                        rs.getString("reason"),
                        expiresAt
                );
            }
        }
    }

    public BanRow getActiveIpBanServer(String ip, long now, String serverName) throws SQLException {
        String sql =
                "SELECT id, reason, expires_at FROM hg_ip_bans " +
                        "WHERE ip=? AND active=TRUE " +
                        "AND (expires_at IS NULL OR expires_at > ?) " +
                        "AND scope='SERVER' AND scope_server=? " +
                        "ORDER BY created_at DESC LIMIT 1";

        try (Connection db = getConnection();
             PreparedStatement ps = db.prepareStatement(sql)) {

            ps.setString(1, ip);
            ps.setLong(2, now);
            ps.setString(3, serverName);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Long expiresAt = rs.getObject("expires_at", Long.class);
                return new BanRow(
                        rs.getLong("id"),
                        rs.getString("reason"),
                        expiresAt
                );
            }
        }
    }

    /* ------------------------------------------------------------
     * Utility
     * ------------------------------------------------------------ */

    public String extractIp(net.md_5.bungee.api.connection.ProxiedPlayer p) {
        try {
            if (p.getSocketAddress() instanceof java.net.InetSocketAddress isa) {
                if (isa.getAddress() != null) return isa.getAddress().getHostAddress();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /* ------------------------------------------------------------
     * Shutdown (recommended)
     * ------------------------------------------------------------ */

    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    public DataSource getDataSource() {
        return dataSource;
    }
}