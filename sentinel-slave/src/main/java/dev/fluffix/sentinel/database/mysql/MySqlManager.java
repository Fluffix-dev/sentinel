package dev.fluffix.sentinel.database.mysql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.fluffix.sentinel.configuration.JsonFileBuilder;

import java.io.File;
import java.io.IOException;
import java.sql.*;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * MySQL Manager auf Basis von HikariCP.
 * - Connection-Pool
 * - Helper für Queries (SELECT) & Updates (INSERT/UPDATE/DELETE)
 * - Batch-Updates
 * - Transaktionen
 * - Laden/Erzeugen der Konfiguration via JsonFileBuilder
 */
public final class MySqlManager implements AutoCloseable {

    private final HikariDataSource dataSource;

    /* -------------------------------------------------------------
     * Konstruktoren / Factory
     * ------------------------------------------------------------- */

    public MySqlManager(
            String host,
            int port,
            String database,
            String username,
            String password,
            int maxPoolSize
    ) {
        HikariConfig cfg = new HikariConfig();

        String jdbcUrl = String.format(
                Locale.ROOT,
                "jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=UTC&allowMultiQueries=true",
                host, port, database
        );

        cfg.setJdbcUrl(jdbcUrl);
        cfg.setUsername(username);
        cfg.setPassword(password);

        // Pool-Parameter (konservative Defaults)
        cfg.setMaximumPoolSize(Math.max(2, maxPoolSize));
        cfg.setMinimumIdle(Math.min(2, cfg.getMaximumPoolSize()));
        cfg.setIdleTimeout(120_000);          // 2 min
        cfg.setConnectionTimeout(10_000);     // 10 s
        cfg.setMaxLifetime(30 * 60_000);      // 30 min
        cfg.setPoolName("Sentinel-MySQL");

        // MySQL-spezifische Tuning-Properties
        cfg.addDataSourceProperty("cachePrepStmts", "true");
        cfg.addDataSourceProperty("prepStmtCacheSize", "250");
        cfg.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        cfg.addDataSourceProperty("useServerPrepStmts", "true");
        cfg.addDataSourceProperty("useLocalSessionState", "true");
        cfg.addDataSourceProperty("rewriteBatchedStatements", "true");
        cfg.addDataSourceProperty("cacheResultSetMetadata", "true");

        this.dataSource = new HikariDataSource(cfg);
    }

    /**
     * Factory: Erstellt den Manager aus einer JSON-Konfig.
     * Fehlt die Datei, wird sie mit Defaults erzeugt.
     */
    public static MySqlManager fromConfig(File file) throws IOException {
        JsonFileBuilder builder = new JsonFileBuilder();

        if (!file.exists()) {
            builder.add("host", "localhost")
                    .add("port", 3306)
                    .add("database", "testdb")
                    .add("username", "root")
                    .add("password", "")
                    .add("poolSize", 10);
            builder.build(file.getAbsolutePath());
        }

        builder.loadFromFile(file);

        String host = builder.getString("host");
        int port = builder.getInt("port");
        String db   = builder.getString("database");
        String user = builder.getString("username");
        String pass = builder.getString("password");
        int pool    = builder.contains("poolSize") ? builder.getInt("poolSize") : 10;

        if (host == null || db == null || user == null) {
            throw new IllegalArgumentException("MySQL-Config unvollständig: host/database/username fehlt.");
        }

        return new MySqlManager(host, port == 0 ? 3306 : port, db, user, pass, pool);
    }

    /* -------------------------------------------------------------
     * Core API
     * ------------------------------------------------------------- */

    /** Liefert eine Verbindung aus dem Pool. */
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /** SELECT (eigenständige Verbindung). */
    public List<Map<String, Object>> query(String sql, Object... params) throws SQLException {
        try (Connection con = getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return toList(rs);
            }
        }
    }

    /** INSERT/UPDATE/DELETE (eigenständige Verbindung). */
    public int update(String sql, Object... params) throws SQLException {
        try (Connection con = getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            bindParams(ps, params);
            return ps.executeUpdate();
        }
    }

    /** Batch-Update mit derselben SQL-Vorlage (eigenständige Verbindung). */
    public int[] batchUpdate(String sql, List<Object[]> batchParams) throws SQLException {
        if (batchParams == null || batchParams.isEmpty()) return new int[0];
        try (Connection con = getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            for (Object[] p : batchParams) {
                bindParams(ps, p);
                ps.addBatch();
            }
            return ps.executeBatch();
        }
    }

    /**
     * Führt Arbeiten innerhalb einer Transaktion aus.
     * Nutzung:
     *   manager.inTransaction(con -> {
     *       manager.update(con, "INSERT ...", p1, p2);
     *       List<Map<String,Object>> rows = manager.query(con, "SELECT ...");
     *       return null;
     *   });
     */
    public <T> T inTransaction(SQLFunction<Connection, T> work) throws SQLException {
        Objects.requireNonNull(work, "work");
        try (Connection con = getConnection()) {
            boolean old = con.getAutoCommit();
            con.setAutoCommit(false);
            try {
                T result = work.apply(con);
                con.commit();
                return result;
            } catch (SQLException | RuntimeException ex) {
                try { con.rollback(); } catch (SQLException ignore) {}
                throw ex;
            } finally {
                try { con.setAutoCommit(old); } catch (SQLException ignore) {}
            }
        }
    }

    /* -------------------------------------------------------------
     * Connection-gebundene Varianten (für Transaktionen)
     * ------------------------------------------------------------- */

    /** SELECT mit bereitgestellter Connection (z. B. inTransaction). */
    public List<Map<String, Object>> query(Connection con, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return toList(rs);
            }
        }
    }

    /** UPDATE/INSERT/DELETE mit bereitgestellter Connection (z. B. inTransaction). */
    public int update(Connection con, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            bindParams(ps, params);
            return ps.executeUpdate();
        }
    }

    /* -------------------------------------------------------------
     * Shutdown
     * ------------------------------------------------------------- */

    /** Idempotentes Schließen des Pools. */
    @Override
    public void close() {
        if (dataSource != null) {
            dataSource.close(); // idempotent bei Hikari
        }
    }

    /* -------------------------------------------------------------
     * Helpers
     * ------------------------------------------------------------- */

    private static void bindParams(PreparedStatement ps, Object... params) throws SQLException {
        if (params == null) return;
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            int idx = i + 1;

            switch (p) {
                case null -> ps.setObject(idx, null);
                case LocalDate ld -> ps.setDate(idx, Date.valueOf(ld));
                case LocalDateTime ldt -> ps.setTimestamp(idx, Timestamp.valueOf(ldt));
                case Instant instant -> ps.setTimestamp(idx, Timestamp.from(instant));
                case java.util.Date d -> ps.setTimestamp(idx, new Timestamp(d.getTime()));
                case Boolean b -> ps.setBoolean(idx, b);
                case Integer n -> ps.setInt(idx, n);
                case Long l -> ps.setLong(idx, l);
                case Double d -> ps.setDouble(idx, d);
                case Float f -> ps.setFloat(idx, f);
                case byte[] bytes -> ps.setBytes(idx, bytes);
                default -> ps.setObject(idx, p);
            }
        }
    }

    private static List<Map<String, Object>> toList(ResultSet rs) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        ResultSetMetaData meta = rs.getMetaData();
        int cols = meta.getColumnCount();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>(cols);
            for (int c = 1; c <= cols; c++) {
                String key = meta.getColumnLabel(c);
                Object val = rs.getObject(c);
                row.put(key, val);
            }
            rows.add(row);
        }
        return rows;
    }

    /* -------------------------------------------------------------
     * Functional Interface
     * ------------------------------------------------------------- */
    @FunctionalInterface
    public interface SQLFunction<T, R> {
        R apply(T t) throws SQLException;
    }
}
