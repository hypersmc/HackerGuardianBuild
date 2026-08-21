package me.hackerguardian.velocity;

import me.hackerguardian.Util.TicketPayload;
import me.hackerguardian.database.CoreDatabaseSchema;
import me.hackerguardian.database.DatabaseSettings;
import me.hackerguardian.database.DatabaseType;
import me.hackerguardian.database.HikariDatabase;

import javax.sql.DataSource;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Velocity-side owner of HackerGuardian's SQL pool. */
public final class VDatabase {

    private final Logger logger;
    private final File configFile;
    private final Map<String, Object> config;
    private HikariDatabase database;

    public VDatabase(Logger logger, File configFile, Map<String, Object> config) {
        this.logger = logger;
        this.configFile = configFile;
        this.config = config;
    }

    public boolean init() {
        final DatabaseSettings settings;
        try {
            settings = readSettings();
        } catch (Exception e) {
            logger.severe("Invalid Velocity database configuration: " + e.getMessage());
            return false;
        }

        if (settings.hasPlaceholderCredentials()) {
            logger.warning("============================================================");
            logger.warning("HackerGuardian Velocity database setup is required before first start.");
            logger.warning("Edit: " + configFile.getPath());
            logger.warning("Supported database types: MYSQL, POSTGRESQL");
            logger.warning("Default ports: MySQL 3306, PostgreSQL 5432");
            logger.warning("Set Database.username/password, then restart Velocity.");
            logger.warning("============================================================");
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
            logger.log(Level.SEVERE, "Unable to initialize Velocity database", e);
            shutdown();
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private DatabaseSettings readSettings() {
        Object sectionObject = config.get("Database");
        if (!(sectionObject instanceof Map)) {
            throw new IllegalArgumentException("Missing Database section");
        }
        Map<String, Object> section = (Map<String, Object>) sectionObject;

        DatabaseType type = DatabaseType.parse(stringValue(section.get("type"), "MYSQL"));

        Map<String, Object> pool;
        Object poolObject = section.get("pool");
        if (poolObject instanceof Map) pool = (Map<String, Object>) poolObject;
        else pool = Map.of();

        return new DatabaseSettings(
                type,
                stringValue(section.get("host"), "127.0.0.1"),
                intValue(section.get("port"), type.defaultPort()),
                stringValue(section.get("name"), "hackerguardian"),
                stringValue(section.get("username"), "changeme"),
                stringValue(section.get("password"), "changeme"),
                booleanValue(section.get("ssl"), false),
                intValue(pool.get("maximum_size"), 10),
                intValue(pool.get("minimum_idle"), 2),
                longValue(pool.get("connection_timeout_ms"), 10_000L),
                longValue(pool.get("validation_timeout_ms"), 5_000L),
                longValue(pool.get("idle_timeout_ms"), 60_000L),
                longValue(pool.get("max_lifetime_ms"), 600_000L)
        );
    }

    public void insertTicket(TicketPayload payload) throws SQLException {
        String sql = "INSERT INTO hg_player_tickets " +
                "(ticket_id, player_uuid, player_name, issued_at, expires_at, used_at, target_server) " +
                "VALUES (?,?,?,?,?,NULL,?)";

        try (Connection c = connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, payload.ticketId);
            ps.setString(2, payload.playerUuid);
            ps.setString(3, payload.playerName);
            ps.setLong(4, payload.issuedAt);
            ps.setLong(5, payload.expiresAt);
            ps.setString(6, payload.targetServer);
            ps.executeUpdate();
        }
    }

    public Connection connection() throws SQLException {
        if (database == null) throw new SQLException("Database pool is not initialized");
        return database.connection();
    }

    public DataSource dataSource() {
        return database == null ? null : database.dataSource();
    }

    public DatabaseType type() {
        return database == null ? null : database.type();
    }

    public void shutdown() {
        if (database != null) {
            database.close();
            database = null;
            logger.info("Database pool closed.");
        }
    }

    private static String stringValue(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value == null) return fallback;
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value == null) return fallback;
        try { return Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value == null) return fallback;
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
