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
 * MySQL Manager mit HikariCP + JsonFileBuilder für Config.
 */
public final class MySqlManager implements AutoCloseable {

    private final HikariDataSource dataSource;

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

        cfg.setMaximumPoolSize(Math.max(2, maxPoolSize));
        cfg.setMinimumIdle(Math.min(2, cfg.getMaximumPoolSize()));
        cfg.setIdleTimeout(120_000);
        cfg.setConnectionTimeout(10_000);
        cfg.setMaxLifetime(30 * 60_000);
        cfg.setPoolName("MySqlPool");

        cfg.addDataSourceProperty("cachePrepStmts", "true");
        cfg.addDataSourceProperty("prepStmtCacheSize", "250");
        cfg.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        cfg.addDataSourceProperty("useServerPrepStmts", "true");
        cfg.addDataSourceProperty("useLocalSessionState", "true");
        cfg.addDataSourceProperty("rewriteBatchedStatements", "true");

        this.dataSource = new HikariDataSource(cfg);
    }

    /**
     * Factory: Erstellt Manager aus JSON-Konfig.
     * Falls Datei fehlt, wird eine Default-Datei erzeugt.
     */
    public static MySqlManager fromConfig(File file) throws IOException {
        JsonFileBuilder builder = new JsonFileBuilder();

        if (!file.exists()) {
            // Default-Konfig schreiben
            builder.add("host", "localhost")
                    .add("port", 3306)
                    .add("database", "testdb")
                    .add("username", "root")
                    .add("password", "")
                    .add("poolSize", 10);
            builder.build(file.getAbsolutePath());
        }

        // Jetzt laden
        builder.loadFromFile(file);

        String host = builder.getString("host");
        int port = builder.getInt("port");
        String db = builder.getString("database");
        String user = builder.getString("username");
        String pass = builder.getString("password");
        int pool = builder.contains("poolSize") ? builder.getInt("poolSize") : 10;

        if (host == null || db == null || user == null) {
            throw new IllegalArgumentException("Config unvollständig: host/database/username fehlt");
        }

        return new MySqlManager(host, port == 0 ? 3306 : port, db, user, pass, pool);
    }

    // ===== Query & Update Methoden =====

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public List<Map<String, Object>> query(String sql, Object... params) throws SQLException {
        try (Connection con = getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return toList(rs);
            }
        }
    }

    public int update(String sql, Object... params) throws SQLException {
        try (Connection con = getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            bindParams(ps, params);
            return ps.executeUpdate();
        }
    }

    public <T> T inTransaction(SQLFunction<Connection, T> work) throws SQLException {
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

    @Override
    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    // ===== Helpers =====

    private static void bindParams(PreparedStatement ps, Object... params) throws SQLException {
        if (params == null) return;
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            int idx = i + 1;
            if (p instanceof LocalDate ld) {
                ps.setDate(idx, Date.valueOf(ld));
            } else if (p instanceof LocalDateTime ldt) {
                ps.setTimestamp(idx, Timestamp.valueOf(ldt));
            } else if (p instanceof Instant instant) {
                ps.setTimestamp(idx, Timestamp.from(instant));
            } else {
                ps.setObject(idx, p);
            }
        }
    }

    private static List<Map<String, Object>> toList(ResultSet rs) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        ResultSetMetaData meta = rs.getMetaData();
        int cols = meta.getColumnCount();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int c = 1; c <= cols; c++) {
                row.put(meta.getColumnLabel(c), rs.getObject(c));
            }
            rows.add(row);
        }
        return rows;
    }

    @FunctionalInterface
    public interface SQLFunction<T, R> {
        R apply(T t) throws SQLException;
    }
}
