package me.hackerguardian.database;

import java.util.Locale;

/** Supported SQL engines for HackerGuardian. */
public enum DatabaseType {
    MYSQL(3306, "com.mysql.cj.jdbc.Driver"),
    POSTGRESQL(5432, "org.postgresql.Driver");

    private final int defaultPort;
    private final String driverClass;

    DatabaseType(int defaultPort, String driverClass) {
        this.defaultPort = defaultPort;
        this.driverClass = driverClass;
    }

    public int defaultPort() {
        return defaultPort;
    }

    public String driverClass() {
        return driverClass;
    }

    public String jdbcUrl(DatabaseSettings settings) {
        if (this == POSTGRESQL) {
            return "jdbc:postgresql://" + settings.host() + ":" + settings.port() + "/" + settings.database()
                    + (settings.ssl() ? "?sslmode=require" : "?sslmode=disable");
        }

        return "jdbc:mysql://" + settings.host() + ":" + settings.port() + "/" + settings.database()
                + "?useSSL=" + settings.ssl()
                + "&serverTimezone=UTC"
                + "&characterEncoding=utf8"
                + "&useUnicode=true";
    }

    public String generatedIdColumn() {
        return this == POSTGRESQL
                ? "BIGSERIAL PRIMARY KEY"
                : "BIGINT AUTO_INCREMENT PRIMARY KEY";
    }

    public String binaryLargeObjectType() {
        return this == POSTGRESQL ? "BYTEA" : "LONGBLOB";
    }

    public static DatabaseType parse(String raw) {
        String normalized = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (normalized.equals("POSTGRES") || normalized.equals("PG") || normalized.equals("PGSQL")) {
            normalized = "POSTGRESQL";
        }
        if (normalized.equals("MARIADB")) {
            // MariaDB is accepted as a convenience alias and uses the MySQL JDBC path.
            normalized = "MYSQL";
        }
        for (DatabaseType type : values()) {
            if (type.name().equals(normalized)) return type;
        }
        throw new IllegalArgumentException(
                "Unsupported database type '" + raw + "'. Supported types: MYSQL, POSTGRESQL"
        );
    }
}
