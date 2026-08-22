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
            if (!indexExists(connection, table, index)) throw e;
        }
    }

    /** Add a column only when it is genuinely absent. The definition is trusted static schema text. */
    public static void ensureColumn(Connection connection, String table, String column, String definition) throws SQLException {
        if (columnExists(connection, table, column)) return;

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        } catch (SQLException e) {
            // Multiple proxy/backend nodes can race schema startup against the shared DB.
            if (!columnExists(connection, table, column)) throw e;
        }
    }

    public static void dropIndexIfExists(Connection connection, String table, String index) throws SQLException {
        if (!indexExists(connection, table, index)) return;

        DatabaseType type = detectType(connection);
        String sql = type == DatabaseType.POSTGRESQL
                ? "DROP INDEX IF EXISTS " + index
                : "DROP INDEX " + index + " ON " + table;

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    public static boolean indexExists(Connection connection, String table, String index) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        String catalog = safeCatalog(connection);
        String schema = safeSchema(connection);

        String[] tableNames = {table, table.toUpperCase(), table.toLowerCase()};
        for (String tableName : tableNames) {
            if (indexExists(meta, catalog, schema, tableName, index)) return true;
            if (schema != null && indexExists(meta, catalog, null, tableName, index)) return true;
            if (catalog != null && indexExists(meta, null, schema, tableName, index)) return true;
            if (indexExists(meta, null, null, tableName, index)) return true;
        }
        return false;
    }

    public static boolean columnExists(Connection connection, String table, String column) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        String catalog = safeCatalog(connection);
        String schema = safeSchema(connection);
        String[] tableNames = {table, table.toUpperCase(), table.toLowerCase()};
        String[] columnNames = {column, column.toUpperCase(), column.toLowerCase()};

        for (String tableName : tableNames) {
            for (String columnName : columnNames) {
                if (columnExists(meta, catalog, schema, tableName, columnName)) return true;
                if (schema != null && columnExists(meta, catalog, null, tableName, columnName)) return true;
                if (catalog != null && columnExists(meta, null, schema, tableName, columnName)) return true;
                if (columnExists(meta, null, null, tableName, columnName)) return true;
            }
        }
        return false;
    }

    private static boolean indexExists(DatabaseMetaData meta,
                                       String catalog,
                                       String schema,
                                       String table,
                                       String index) throws SQLException {
        try (ResultSet rs = meta.getIndexInfo(catalog, schema, table, false, false)) {
            while (rs.next()) {
                String existing = rs.getString("INDEX_NAME");
                if (existing != null && existing.equalsIgnoreCase(index)) return true;
            }
        }
        return false;
    }

    private static boolean columnExists(DatabaseMetaData meta,
                                         String catalog,
                                         String schema,
                                         String table,
                                         String column) throws SQLException {
        try (ResultSet rs = meta.getColumns(catalog, schema, table, column)) {
            while (rs.next()) {
                String existing = rs.getString("COLUMN_NAME");
                if (existing != null && existing.equalsIgnoreCase(column)) return true;
            }
        }
        return false;
    }

    private static String safeCatalog(Connection connection) {
        try { return connection.getCatalog(); }
        catch (SQLException ignored) { return null; }
    }

    private static String safeSchema(Connection connection) {
        try { return connection.getSchema(); }
        catch (SQLException | AbstractMethodError ignored) { return null; }
    }
}
