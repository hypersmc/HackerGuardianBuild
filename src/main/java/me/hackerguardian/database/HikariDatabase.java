package me.hackerguardian.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/** Owns one Hikari pool for a configured HackerGuardian SQL provider. */
public final class HikariDatabase implements AutoCloseable {

    private final DatabaseSettings settings;
    private HikariDataSource dataSource;

    public HikariDatabase(DatabaseSettings settings) {
        this.settings = settings;
    }

    public synchronized void start() throws SQLException {
        if (dataSource != null && !dataSource.isClosed()) return;

        try {
            Class.forName(settings.type().driverClass());
        } catch (ClassNotFoundException e) {
            throw new SQLException("JDBC driver is not available for " + settings.type(), e);
        }

        HikariConfig config = new HikariConfig();
        config.setPoolName("HackerGuardian-" + settings.type().name());
        config.setJdbcUrl(settings.type().jdbcUrl(settings));
        config.setUsername(settings.username());
        config.setPassword(settings.password());
        config.setMaximumPoolSize(settings.maximumPoolSize());
        config.setMinimumIdle(settings.minimumIdle());
        config.setConnectionTimeout(settings.connectionTimeoutMs());
        config.setValidationTimeout(settings.validationTimeoutMs());
        config.setIdleTimeout(settings.idleTimeoutMs());
        config.setMaxLifetime(settings.maxLifetimeMs());
        config.setConnectionTestQuery("SELECT 1");

        if (settings.type() == DatabaseType.MYSQL) {
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        }

        HikariDataSource created = new HikariDataSource(config);
        try (Connection ignored = created.getConnection()) {
            // Fail startup immediately if credentials/network/database are invalid.
        } catch (SQLException e) {
            created.close();
            throw e;
        }
        dataSource = created;
    }

    public DatabaseSettings settings() {
        return settings;
    }

    public DatabaseType type() {
        return settings.type();
    }

    public DataSource dataSource() {
        if (dataSource == null || dataSource.isClosed()) {
            throw new IllegalStateException("Database pool is not running");
        }
        return dataSource;
    }

    public Connection connection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("Database pool is not running");
        }
        return dataSource.getConnection();
    }

    @Override
    public synchronized void close() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }
}
