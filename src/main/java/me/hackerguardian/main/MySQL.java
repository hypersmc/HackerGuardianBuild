package me.hackerguardian.main;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import me.hackerguardian.Util.TicketPayload;
import me.hackerguardian.main.utils.ErrorHandler;
import me.hackerguardian.main.utils.textHandling;
import org.bukkit.Bukkit;

import javax.sql.DataSource;
import java.sql.*;

public final class MySQL {

    private final HackerGuardian plugin;

    private HikariDataSource ds;

    private final String host;
    private final int port;
    private final String database;
    private final String user;
    private final String pass;

    public MySQL(HackerGuardian plugin) {
        this.plugin = plugin;
        this.host = plugin.getConfig().getString("SQLHost");
        this.port = Integer.parseInt(plugin.getConfig().getString("SQLPort", "3306"));
        this.database = plugin.getConfig().getString("SQLDatabaseName");
        this.user = plugin.getConfig().getString("SQLUsername");
        this.pass = plugin.getConfig().getString("SQLPassword");
    }

    public void init() {
        if ("changeme".equalsIgnoreCase(user) && "changeme".equalsIgnoreCase(pass)) {
            textHandling text = new textHandling();
            text.SendconsoleTextWp("");
            text.SendconsoleTextWp("---------- Core MySQL ----------");
            text.SendconsoleTextWp("Please setup MySQL in the config. When done reboot the server.");
            text.SendconsoleTextWp("Disabling plugin. Please reboot to reload config.");
            text.SendconsoleTextWp("-------------------------------");
            text.SendconsoleTextWp("");
            Bukkit.getPluginManager().disablePlugin(plugin);
            return;
        }

        HikariConfig cfg = new HikariConfig();

        String jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false"
                + "&serverTimezone=UTC"
                + "&characterEncoding=utf8"
                + "&useUnicode=true";

        cfg.setJdbcUrl(jdbcUrl);
        cfg.setUsername(user);
        cfg.setPassword(pass);

        cfg.setMaximumPoolSize(plugin.getConfig().getInt("SQLPoolSize", 10));
        cfg.setMinimumIdle(plugin.getConfig().getInt("SQLMinIdle", 2));
        cfg.setConnectionTimeout(10_000);
        cfg.setValidationTimeout(5_000);
        cfg.setIdleTimeout(60_000);
        cfg.setMaxLifetime(10 * 60_000);
        cfg.setConnectionTestQuery("SELECT 1");

        ds = new HikariDataSource(cfg);

        // MySQL owns only its core tables. Feature-specific repositories are
        // initialized by HackerGuardian after those repositories exist.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::createTablesSafe);

        plugin.getLogger().info("MySQL pool initialized.");
    }

    public void shutdown() {
        if (ds != null) {
            ds.close();
            ds = null;
            plugin.getLogger().info("MySQL pool closed.");
        }
    }

    public DataSource getDataSource() {
        return ds;
    }

    private void createTablesSafe() {
        try (Connection c = getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS " + database + ".hg_player_tickets(" +
                            "`ticket_id` CHAR(36) PRIMARY KEY," +
                            "`player_uuid` CHAR(36) NOT NULL," +
                            "`player_name` VARCHAR(16) NOT NULL," +
                            "`issued_at` BIGINT NOT NULL," +
                            "`expires_at` BIGINT NOT NULL," +
                            "`used_at` BIGINT NULL," +
                            "`target_server` VARCHAR(64) NOT NULL," +
                            "INDEX idx_player_uuid (player_uuid)," +
                            "INDEX idx_expires_at (expires_at)," +
                            "INDEX idx_used_at (used_at)" +
                            ");"
            )) {
                ps.executeUpdate();
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS " + database + ".aiTable(" +
                            "`filename` VARCHAR(255) NOT NULL UNIQUE PRIMARY KEY," +
                            "`training_data_bin` LONGBLOB NOT NULL" +
                            ");"
            )) {
                ps.executeUpdate();
            }

            plugin.getLogger().info("MySQL tables ensured.");
        } catch (SQLException e) {
            ErrorHandler.handleSQLException(e, "Error creating SQL tables");
        }
    }

    private Connection getConnection() throws SQLException {
        if (ds == null) throw new SQLException("MySQL not initialized");
        return ds.getConnection();
    }

    /** Thread-safe and atomic. */
    public boolean consumeTicket(TicketPayload payload, long nowMs) {
        String sql =
                "UPDATE " + database + ".hg_player_tickets " +
                        "SET used_at = ? " +
                        "WHERE ticket_id = ? " +
                        "  AND player_uuid = ? " +
                        "  AND target_server = ? " +
                        "  AND used_at IS NULL " +
                        "  AND expires_at > ?";

        try (Connection c = getConnection();
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

    /** Optional: keep table small. Run periodically. */
    public void cleanupTickets(long olderThanMs) {
        long cutoff = System.currentTimeMillis() - olderThanMs;
        String sql =
                "DELETE FROM " + database + ".hg_player_tickets " +
                        "WHERE expires_at < ? OR (used_at IS NOT NULL AND used_at < ?)";

        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, cutoff);
            ps.setLong(2, cutoff);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("cleanupTickets SQL error: " + e.getMessage());
        }
    }
}
