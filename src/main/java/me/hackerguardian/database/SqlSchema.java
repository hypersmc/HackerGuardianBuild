package me.hackerguardian.database;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Small helpers for schema operations that differ between SQL engines. */
public final class SqlSchema {

    private SqlSchema() {}

    public static DatabaseType detectType(Connection connection) throws SQLException {
        String name = connection.getMetaData().getDatabaseProductName();
        if (name != null && name.toLowerCase().contains("postgres")) return DatabaseType.POSTGRESQL;
        if (name != null && (name.toLowerCase().contains("mysql") || name.toLowerCase().contains("mariadb"))) {
            return DatabaseType.MYSQL;
        }
        throw new SQLException("Unsupported database product: " + name);
    }

    public static void ensureIndex(Connection connection, String table, String index, String columns) throws SQLException {
        if (indexExists(connection, table, index)) return;
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE INDEX " + index + " ON " + table + " (" + columns + ")");
        } catch (SQLException e) {
            // Another node may have created it between the metadata check and DDL.
            if (!indexExists(connection, table, index)) throw e;
        }
    }

    private static boolean indexExists(Connection connection, String table, String index) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        if (indexExists(meta, table, index)) return true;
        if (indexExists(meta, table.toUpperCase(), index)) return true;
        return indexExists(meta, table.toLowerCase(), index);
    }

    private static boolean indexExists(DatabaseMetaData meta, String table, String index) throws SQLException {
        try (ResultSet rs = meta.getIndexInfo(null, null, table, false, false)) {
            while (rs.next()) {
                String existing = rs.getString("INDEX_NAME");
                if (existing != null && existing.equalsIgnoreCase(index)) return true;
            }
        }
        return false;
    }
}
