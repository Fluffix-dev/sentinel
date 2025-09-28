package dev.fluffix.sentinel.reasons;

import dev.fluffix.sentinel.database.mysql.MySqlManager;

import java.sql.SQLException;
import java.util.*;

/**
 * Verwaltet Reasons (name, type, duration[seconds]) in Tabelle 'sentinel_reasons'.
 * Schema (laut Create-SQL):
 *   id BIGINT AUTO_INCREMENT PK
 *   name VARCHAR(128)
 *   type VARCHAR(16)   -- BAN | MUTE | REPORT
 *   duration BIGINT    -- Sekunden, 0 = permanent
 *   created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
 *
 * Uniques: (name,type)
 */
public class ReasonManager {

    private final MySqlManager db;

    public ReasonManager(MySqlManager db) throws SQLException {
        this.db = Objects.requireNonNull(db, "db");
        ensureSchema();
    }

    private void ensureSchema() throws SQLException {
        db.update("""
            CREATE TABLE IF NOT EXISTS sentinel_reasons (
                id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
                name       VARCHAR(128) NOT NULL,
                type       VARCHAR(16)  NOT NULL,
                duration   BIGINT       NOT NULL DEFAULT 0,
                created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                UNIQUE KEY uniq_reason_type (name, type)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
        """);
    }

    /* ----------------- CRUD ----------------- */

    public boolean exists(String name, ReasonType type) throws SQLException {
        List<Map<String, Object>> rows = db.query(
                "SELECT 1 FROM sentinel_reasons WHERE name=? AND type=? LIMIT 1",
                name, type.name()
        );
        return !rows.isEmpty();
    }

    public void save(String name, ReasonType type, long durationSeconds) throws SQLException {
        // upsert-artig: erst versuchen zu insert'en, bei DUPLICATE KEY -> update duration
        db.update("""
            INSERT INTO sentinel_reasons(name, type, duration)
            VALUES(?, ?, ?)
            ON DUPLICATE KEY UPDATE duration = VALUES(duration)
        """, name, type.name(), durationSeconds);
    }

    public void delete(String name, ReasonType type) throws SQLException {
        db.update("DELETE FROM sentinel_reasons WHERE name=? AND type=?", name, type.name());
    }

    public Reason load(String name, ReasonType type) throws SQLException {
        List<Map<String, Object>> rows = db.query("""
            SELECT id, name, type, duration, created_at
              FROM sentinel_reasons
             WHERE name=? AND type=?
             LIMIT 1
        """, name, type.name());

        if (rows.isEmpty()) return null;
        return map(rows.get(0));
    }

    /**
     * Lädt alle Reasons (optional gefiltert nach Type).
     * ACHTUNG: nutzt Spaltennamen 'duration' (nicht 'duration_seconds').
     */
    public List<Reason> loadAll(ReasonType filter) throws SQLException {
        final List<Map<String, Object>> rows;
        if (filter == null) {
            rows = db.query("""
                SELECT id, name, type, duration, created_at
                  FROM sentinel_reasons
                 ORDER BY name ASC
            """);
        } else {
            rows = db.query("""
                SELECT id, name, type, duration, created_at
                  FROM sentinel_reasons
                 WHERE type=?
                 ORDER BY name ASC
            """, filter.name());
        }

        List<Reason> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) out.add(map(r));
        return out;
    }

    private Reason map(Map<String, Object> r) {
        ReasonType type = ReasonType.valueOf(Objects.toString(r.get("type")));
        long duration = ((Number) r.get("duration")).longValue();

        Reason reason = new Reason();
        reason.setId(((Number) r.get("id")).longValue());
        reason.setName(Objects.toString(r.get("name")));
        reason.setType(type);
        reason.setDurationSeconds(duration);
        // created_at kannst du bei Bedarf im Reason-Modell ergänzen/setzen
        return reason;
    }
}
