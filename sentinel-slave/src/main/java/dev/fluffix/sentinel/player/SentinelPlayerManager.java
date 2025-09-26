package dev.fluffix.sentinel.player;

import dev.fluffix.sentinel.database.mysql.MySqlManager;

import java.sql.SQLException;
import java.util.*;

/**
 * Persistenter Manager für SentinelPlayer.
 * Speichert/liest Daten über MySqlManager.
 *
 * Tabellen:
 * - sentinel_players(uuid PK, name, points, created_at, updated_at)
 * - sentinel_player_ips(uuid, ip, first_seen, last_seen)  PK(uuid, ip)
 */
public class SentinelPlayerManager {

    private final MySqlManager db;

    public SentinelPlayerManager(MySqlManager db) throws SQLException {
        this.db = Objects.requireNonNull(db, "db");
        ensureSchema();
    }

    /* -------------------------- Schema -------------------------- */

    private void ensureSchema() throws SQLException {
        // Haupttabelle
        db.update("""
            CREATE TABLE IF NOT EXISTS sentinel_players (
              uuid       CHAR(36)      NOT NULL PRIMARY KEY,
              name       VARCHAR(64)   NOT NULL,
              points     INT           NOT NULL DEFAULT 0,
              created_at TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              INDEX idx_name (name)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
        """);

        // IPs (eine Zeile pro (uuid, ip)), FK auf Spieler
        db.update("""
            CREATE TABLE IF NOT EXISTS sentinel_player_ips (
              uuid       CHAR(36)     NOT NULL,
              ip         VARCHAR(45)  NOT NULL,
              first_seen TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
              last_seen  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              PRIMARY KEY (uuid, ip),
              INDEX idx_ip (ip),
              CONSTRAINT fk_player_uuid FOREIGN KEY (uuid)
                REFERENCES sentinel_players(uuid)
                ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
        """);
    }

    /* -------------------------- Exists -------------------------- */

    public boolean existsPlayer(UUID uuid) throws SQLException {
        List<Map<String, Object>> rows = db.query(
                "SELECT 1 FROM sentinel_players WHERE uuid = ? LIMIT 1",
                uuid.toString()
        );
        return !rows.isEmpty();
    }

    public boolean existsIp(UUID uuid, String ip) throws SQLException {
        List<Map<String, Object>> rows = db.query(
                "SELECT 1 FROM sentinel_player_ips WHERE uuid = ? AND ip = ? LIMIT 1",
                uuid.toString(), ip
        );
        return !rows.isEmpty();
    }

    /* -------------------------- CRUD ---------------------------- */

    /**
     * Registriert einen Spieler (legt an oder aktualisiert Name),
     * fügt optional eine IP hinzu (mit Exists-Check) und gibt den
     * vollständigen Datensatz zurück.
     */
    public SentinelPlayer registerOrUpdate(UUID uuid, String name, String ipOpt) throws SQLException {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(name, "name");

        if (existsPlayer(uuid)) {
            db.update("UPDATE sentinel_players SET name = ? WHERE uuid = ?", name, uuid.toString());
        } else {
            db.update("INSERT INTO sentinel_players(uuid, name, points) VALUES(?, ?, ?)",
                    uuid.toString(), name, 0);
        }

        if (ipOpt != null && !ipOpt.isBlank() && !existsIp(uuid, ipOpt)) {
            db.update("INSERT INTO sentinel_player_ips(uuid, ip) VALUES(?, ?)",
                    uuid.toString(), ipOpt.trim());
        }

        return loadByUuid(uuid);
    }

    /**
     * Lädt einen Spieler inkl. IPs.
     */
    public SentinelPlayer loadByUuid(UUID uuid) throws SQLException {
        List<Map<String, Object>> rows = db.query(
                "SELECT uuid, name, points FROM sentinel_players WHERE uuid = ?",
                uuid.toString()
        );
        if (rows.isEmpty()) return null;

        Map<String, Object> r = rows.get(0);
        SentinelPlayer p = new SentinelPlayer(
                UUID.fromString(Objects.toString(r.get("uuid"))),
                Objects.toString(r.get("name"), "Unknown")
        );
        p.setPoints(((Number) r.get("points")).intValue());

        // IPs laden
        List<Map<String, Object>> ipRows = db.query(
                "SELECT ip FROM sentinel_player_ips WHERE uuid = ?",
                uuid.toString()
        );
        for (Map<String, Object> ipr : ipRows) {
            String ip = Objects.toString(ipr.get("ip"), null);
            if (ip != null) {
                p.addIpAddress(ip);
            }
        }
        return p;
    }

    /**
     * Lädt einen Spieler anhand des Namens (falls mehrere, erster Treffer).
     */
    public SentinelPlayer loadByName(String name) throws SQLException {
        List<Map<String, Object>> rows = db.query(
                "SELECT uuid FROM sentinel_players WHERE name = ? LIMIT 1",
                name
        );
        if (rows.isEmpty()) return null;
        String uuid = Objects.toString(rows.get(0).get("uuid"), null);
        return uuid == null ? null : loadByUuid(UUID.fromString(uuid));
    }

    /**
     * Speichert/aktualisiert einen Spieler (upsert) und fügt fehlende IPs hinzu.
     */
    public void save(SentinelPlayer p) throws SQLException {
        Objects.requireNonNull(p, "player");
        UUID uuid = p.getUniqueId();

        if (existsPlayer(uuid)) {
            db.update("UPDATE sentinel_players SET name = ?, points = ? WHERE uuid = ?",
                    p.getName(), p.getPoints(), uuid.toString());
        } else {
            db.update("INSERT INTO sentinel_players(uuid, name, points) VALUES(?, ?, ?)",
                    uuid.toString(), p.getName(), p.getPoints());
        }

        for (String ip : p.getIpAddresses()) {
            if (ip != null && !ip.isBlank() && !existsIp(uuid, ip)) {
                db.update("INSERT INTO sentinel_player_ips(uuid, ip) VALUES(?, ?)",
                        uuid.toString(), ip.trim());
            }
        }
    }

    public void addIp(UUID uuid, String ip) throws SQLException {
        if (ip == null || ip.isBlank()) return;
        if (!existsPlayer(uuid)) {
            throw new IllegalStateException("Spieler existiert nicht: " + uuid);
        }
        if (!existsIp(uuid, ip)) {
            db.update("INSERT INTO sentinel_player_ips(uuid, ip) VALUES(?, ?)",
                    uuid.toString(), ip.trim());
        } else {
            db.update("UPDATE sentinel_player_ips SET last_seen = CURRENT_TIMESTAMP WHERE uuid = ? AND ip = ?",
                    uuid.toString(), ip.trim());
        }
    }

    public void setPoints(UUID uuid, int points) throws SQLException {
        if (!existsPlayer(uuid)) {
            throw new IllegalStateException("Spieler existiert nicht: " + uuid);
        }
        db.update("UPDATE sentinel_players SET points = ? WHERE uuid = ?",
                Math.max(0, points), uuid.toString());
    }

    public void addPoints(UUID uuid, int delta) throws SQLException {
        if (delta <= 0) return;
        if (!existsPlayer(uuid)) {
            throw new IllegalStateException("Spieler existiert nicht: " + uuid);
        }
        db.update("UPDATE sentinel_players SET points = points + ? WHERE uuid = ?",
                delta, uuid.toString());
    }

    public void removePoints(UUID uuid, int delta) throws SQLException {
        if (delta <= 0) return;
        if (!existsPlayer(uuid)) {
            throw new IllegalStateException("Spieler existiert nicht: " + uuid);
        }
        db.update("""
            UPDATE sentinel_players
               SET points = GREATEST(0, points - ?)
             WHERE uuid = ?
        """, delta, uuid.toString());
    }

    public boolean delete(UUID uuid) throws SQLException {
        int affected = db.update("DELETE FROM sentinel_players WHERE uuid = ?", uuid.toString());
        return affected > 0;
    }

    /**
     * Lädt alle Spieler (N+1 IP-Abfragen – für große Datenmengen ggf. optimieren).
     */
    public List<SentinelPlayer> loadAll() throws SQLException {
        List<Map<String, Object>> rows = db.query(
                "SELECT uuid, name, points FROM sentinel_players ORDER BY created_at ASC"
        );
        List<SentinelPlayer> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            UUID uuid = UUID.fromString(Objects.toString(r.get("uuid")));
            SentinelPlayer p = new SentinelPlayer(
                    uuid,
                    Objects.toString(r.get("name"), "Unknown")
            );
            p.setPoints(((Number) r.get("points")).intValue());

            List<Map<String, Object>> ipRows = db.query(
                    "SELECT ip FROM sentinel_player_ips WHERE uuid = ?",
                    uuid.toString()
            );
            for (Map<String, Object> ipr : ipRows) {
                String ip = Objects.toString(ipr.get("ip"), null);
                if (ip != null) {
                    p.addIpAddress(ip);
                }
            }
            out.add(p);
        }
        return out;
    }
}
