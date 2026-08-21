package me.hackerguardian.database;

/** Immutable validated database configuration shared by Paper/proxy adapters. */
public record DatabaseSettings(
        DatabaseType type,
        String host,
        int port,
        String database,
        String username,
        String password,
        boolean ssl,
        int maximumPoolSize,
        int minimumIdle,
        long connectionTimeoutMs,
        long validationTimeoutMs,
        long idleTimeoutMs,
        long maxLifetimeMs
) {
    public DatabaseSettings {
        if (type == null) throw new IllegalArgumentException("Database type is required");
        host = require(host, "host");
        database = require(database, "database name");
        username = require(username, "username");
        if (password == null) password = "";
        if (port <= 0 || port > 65535) throw new IllegalArgumentException("Database port must be 1-65535");
        if (maximumPoolSize < 1) throw new IllegalArgumentException("maximumPoolSize must be >= 1");
        if (minimumIdle < 0 || minimumIdle > maximumPoolSize) {
            throw new IllegalArgumentException("minimumIdle must be between 0 and maximumPoolSize");
        }
    }

    public boolean hasPlaceholderCredentials() {
        return "changeme".equalsIgnoreCase(username.trim())
                || "changeme".equalsIgnoreCase(password.trim());
    }

    private static String require(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Database " + name + " is required");
        }
        return value.trim();
    }
}
