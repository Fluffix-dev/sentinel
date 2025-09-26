package dev.fluffix.sentinel.reasons;

import dev.fluffix.sentinel.database.mysql.MySqlManager;

import java.sql.SQLException;
import java.util.*;

public class ReasonManager {

    private final MySqlManager db;

    public ReasonManager(MySqlManager db) throws SQLException {
        this.db = Objects.requireNonNull(db, "db");
        ensureSchema();
    }

    private void ensureSchema() throws SQLException {
        db.update("""
            CREATE TABLE IF NOT EXISTS sentinel_reasons (
              name              VARCHAR(128)  NOT NULL,
              type              VARCHAR(16)   NOT NULL,
              duration_seconds  BIGINT        NOT NULL DEFAULT 0,
              created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              PRIMARY KEY (name, type)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
        """);
    }

    public boolean exists(String name) throws SQLException {
        List<Map<String, Object>> rows = db.query(
                "SELECT 1 FROM sentinel_reasons WHERE name = ? LIMIT 1", name);
        return !rows.isEmpty();
    }

    public boolean exists(String name, ReasonType type) throws SQLException {
        List<Map<String, Object>> rows = db.query(
                "SELECT 1 FROM sentinel_reasons WHERE name = ? AND type = ? LIMIT 1",
                name, type.name());
        return !rows.isEmpty();
    }

    public Reason save(Reason reason) throws SQLException {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(reason.getName(), "reason.name");
        Objects.requireNonNull(reason.getType(), "reason.type");

        if (exists(reason.getName(), reason.getType())) {
            db.update("UPDATE sentinel_reasons SET duration_seconds = ? WHERE name = ? AND type = ?",
                    reason.getDurationSeconds(),
                    reason.getName(),
                    reason.getType().name());
        } else {
            db.update("INSERT INTO sentinel_reasons(name, type, duration_seconds) VALUES(?, ?, ?)",
                    reason.getName(),
                    reason.getType().name(),
                    reason.getDurationSeconds());
        }
        return load(reason.getName(), reason.getType());
    }

    public Reason save(String name, ReasonType type, long durationSeconds) throws SQLException {
        return save(new Reason(name, type, durationSeconds));
    }

    public Reason load(String name, ReasonType type) throws SQLException {
        List<Map<String, Object>> rows = db.query(
                "SELECT name, type, duration_seconds FROM sentinel_reasons WHERE name = ? AND type = ?",
                name, type.name());
        if (rows.isEmpty()) return null;

        Map<String, Object> r = rows.get(0);
        Reason out = new Reason();
        out.setName(Objects.toString(r.get("name")));
        out.setType(ReasonType.valueOf(Objects.toString(r.get("type"))));
        out.setDurationSeconds(((Number) r.get("duration_seconds")).longValue());
        return out;
    }

    public List<Reason> load(String name) throws SQLException {
        List<Map<String, Object>> rows = db.query(
                "SELECT name, type, duration_seconds FROM sentinel_reasons WHERE name = ?", name);

        List<Reason> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Reason reason = new Reason();
            reason.setName(Objects.toString(r.get("name")));
            reason.setType(ReasonType.valueOf(Objects.toString(r.get("type"))));
            reason.setDurationSeconds(((Number) r.get("duration_seconds")).longValue());
            out.add(reason);
        }
        return out;
    }

    public List<Reason> loadAll(ReasonType typeFilter) throws SQLException {
        List<Map<String, Object>> rows;
        if (typeFilter == null) {
            rows = db.query("SELECT name, type, duration_seconds FROM sentinel_reasons ORDER BY name ASC");
        } else {
            rows = db.query("SELECT name, type, duration_seconds FROM sentinel_reasons WHERE type = ? ORDER BY name ASC",
                    typeFilter.name());
        }

        List<Reason> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            Reason reason = new Reason();
            reason.setName(Objects.toString(r.get("name")));
            reason.setType(ReasonType.valueOf(Objects.toString(r.get("type"))));
            reason.setDurationSeconds(((Number) r.get("duration_seconds")).longValue());
            out.add(reason);
        }
        return out;
    }

    public boolean delete(String name, ReasonType type) throws SQLException {
        int affected = db.update("DELETE FROM sentinel_reasons WHERE name = ? AND type = ?", name, type.name());
        return affected > 0;
    }
}
