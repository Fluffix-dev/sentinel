package dev.fluffix.sentinel.database.mysql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.io.IOException;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

import dev.fluffix.sentinel.configuration.JsonFileBuilder;

public class MySqlManager implements AutoCloseable {

    private final HikariDataSource dataSource;

    private MySqlManager(HikariDataSource ds) {
        this.dataSource = ds;
    }

    public static MySqlManager fromConfig(File file) throws IOException {
        JsonFileBuilder json = new JsonFileBuilder();
        if (!file.exists()) {
            json.add("host", "localhost")
                    .add("port", 3306)
                    .add("database", "sentinel")
                    .add("username", "root")
                    .add("password", "root")
                    .add("poolSize", 10)
                    .build(file.getAbsolutePath());
        } else {
            json.loadFromFile(file);
        }

        String host = json.getString("host");
        int port = json.getInt("port");
        String db = json.getString("database");
        String user = json.getString("username");
        String pass = json.getString("password");
        int poolSize = json.getInt("poolSize");

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + db + "?useSSL=false&autoReconnect=true&serverTimezone=UTC");
        cfg.setUsername(user);
        cfg.setPassword(pass);
        cfg.setMaximumPoolSize(poolSize);
        cfg.setMinimumIdle(1);
        cfg.setPoolName("SentinelPool");
        cfg.setConnectionTimeout(10000);

        return new MySqlManager(new HikariDataSource(cfg));
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /* -------------------- Update -------------------- */

    public int update(String sql, Object... params) throws SQLException {
        try (Connection con = getConnection()) {
            return update(con, sql, params);
        }
    }

    public int update(Connection con, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            bindParams(ps, params);
            return ps.executeUpdate();
        }
    }

    /* -------------------- Query --------------------- */

    public List<Map<String, Object>> query(String sql, Object... params) throws SQLException {
        try (Connection con = getConnection()) {
            return query(con, sql, params);
        }
    }

    public List<Map<String, Object>> query(Connection con, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> out = new ArrayList<>();
                ResultSetMetaData meta = rs.getMetaData();
                int cols = meta.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    for (int i = 1; i <= cols; i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    out.add(row);
                }
                return out;
            }
        }
    }

    /* -------------------- Transactions ---------------- */

    public <T> T inTransaction(Function<Connection, T> fn) throws SQLException {
        try (Connection con = getConnection()) {
            try {
                con.setAutoCommit(false);
                T result = fn.apply(con);
                con.commit();
                return result;
            } catch (Exception ex) {
                con.rollback();
                throw ex instanceof SQLException ? (SQLException) ex : new SQLException(ex);
            } finally {
                con.setAutoCommit(true);
            }
        }
    }

    /* -------------------- Helpers -------------------- */

    private static void bindParams(PreparedStatement ps, Object... params) throws SQLException {
        if (params == null || params.length == 0) return;

        int expected;
        try {
            expected = ps.getParameterMetaData().getParameterCount();
        } catch (SQLException ignored) {
            expected = -1; // manche Treiber liefern nichts
        }

        if (expected == 0) {
            throw new SQLException("Attempted to bind parameters but the SQL has no placeholders ('?').");
        }

        if (expected > 0 && expected != params.length) {
            throw new SQLException("Parameter count mismatch: expected " + expected + " but got " + params.length);
        }

        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            int idx = i + 1;

            if (p == null) {
                ps.setObject(idx, null);
            } else if (p instanceof java.time.LocalDate ld) {
                ps.setDate(idx, java.sql.Date.valueOf(ld));
            } else if (p instanceof java.time.LocalDateTime ldt) {
                ps.setTimestamp(idx, java.sql.Timestamp.valueOf(ldt));
            } else if (p instanceof Instant instant) {
                ps.setTimestamp(idx, Timestamp.from(instant));
            } else if (p instanceof java.util.Date d) {
                ps.setTimestamp(idx, new Timestamp(d.getTime()));
            } else if (p instanceof Boolean b) {
                ps.setBoolean(idx, b);
            } else if (p instanceof Integer n) {
                ps.setInt(idx, n);
            } else if (p instanceof Long l) {
                ps.setLong(idx, l);
            } else if (p instanceof Double d) {
                ps.setDouble(idx, d);
            } else if (p instanceof Float f) {
                ps.setFloat(idx, f);
            } else if (p instanceof byte[] bytes) {
                ps.setBytes(idx, bytes);
            } else {
                ps.setObject(idx, p);
            }
        }
    }

    @Override
    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
