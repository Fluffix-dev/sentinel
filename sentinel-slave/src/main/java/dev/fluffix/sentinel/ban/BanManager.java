package dev.fluffix.sentinel.ban;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fluffix.sentinel.database.mysql.MySqlManager;
import dev.fluffix.sentinel.player.SentinelPlayer;
import dev.fluffix.sentinel.player.SentinelPlayerManager;
import dev.fluffix.sentinel.reasons.Reason;
import dev.fluffix.sentinel.reasons.ReasonManager;
import dev.fluffix.sentinel.reasons.ReasonType;

import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

/**
 * Persistenter Ban-Manager.
 * Nur Reasons erlaubt, die im ReasonManager mit Typ BAN existieren.
 * Unterstützt Auto-Dauer aus Reasons (max Dauer; 0 => permanent).
 */
public class BanManager {

    private final MySqlManager db;
    private final SentinelPlayerManager players; // optional, für Offline-Bans
    private final ReasonManager reasons;         // Pflicht: Validierung + Auto-Dauer

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public BanManager(MySqlManager db, SentinelPlayerManager players, ReasonManager reasons) throws SQLException {
        this.db = Objects.requireNonNull(db, "db");
        this.players = players;
        this.reasons = Objects.requireNonNull(reasons, "reasons");
        ensureSchema();
    }

    /* ---------------- Schema ---------------- */

    private void ensureSchema() throws SQLException {
        db.update("""
            CREATE TABLE IF NOT EXISTS sentinel_bans (
              id                BIGINT        NOT NULL AUTO_INCREMENT,
              uuid              CHAR(36)      NOT NULL,
              name              VARCHAR(64)   NOT NULL,
              operator          VARCHAR(64)   NULL,
              type              VARCHAR(16)   NOT NULL,
              reasons           JSON          NOT NULL,
              remaining_seconds BIGINT        NOT NULL DEFAULT 0,
              notice            TEXT          NULL,
              created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
              expires_at        TIMESTAMP     NULL,
              active            TINYINT(1)    NOT NULL DEFAULT 1,
              PRIMARY KEY (id),
              INDEX idx_uuid_active (uuid, active),
              INDEX idx_expires_at (expires_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
        """);
    }

    /* ---------------- Helpers ---------------- */

    private static String reasonsToJson(List<String> reasons) {
        try { return MAPPER.writeValueAsString(reasons == null ? List.of() : reasons); }
        catch (Exception e) { throw new IllegalArgumentException("Failed to serialize reasons", e); }
    }

    private static List<String> jsonToReasons(Object json) {
        if (json == null) return new ArrayList<>();
        try {
            if (json instanceof String s) return MAPPER.readValue(s, new TypeReference<List<String>>() {});
            return MAPPER.convertValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) { return new ArrayList<>(); }
    }

    private static Instant toInstant(Object ts) {
        if (ts == null) return null;
        if (ts instanceof java.sql.Timestamp t) return t.toInstant();
        if (ts instanceof java.util.Date d) return d.toInstant();
        if (ts instanceof Long l) return Instant.ofEpochMilli(l);
        return null;
    }

    private static boolean toBool(Object o) {
        if (o instanceof Boolean b) return b;
        if (o instanceof Number n) return n.intValue() != 0;
        if (o instanceof String s) return "1".equals(s) || "true".equalsIgnoreCase(s);
        return false;
    }

    private Ban mapRow(Map<String, Object> r) {
        Ban b = new Ban();
        b.setId(((Number) r.get("id")).longValue());
        b.setUniqueId(UUID.fromString(Objects.toString(r.get("uuid"))));
        b.setName(Objects.toString(r.get("name"), "Unknown"));
        b.setOperator(Objects.toString(r.get("operator"), null));
        b.setType(BanType.valueOf(Objects.toString(r.get("type"))));
        b.setReasons(jsonToReasons(r.get("reasons")));
        b.setRemainingSeconds(((Number) r.get("remaining_seconds")).longValue());
        b.setNotice(Objects.toString(r.get("notice"), null));
        b.setCreatedAt(toInstant(r.get("created_at")));
        b.setExpiresAt(toInstant(r.get("expires_at")));
        b.setActive(toBool(r.get("active")));
        return b;
    }

    private Instant calcExpiresAt(BanType type, long remainingSeconds) {
        if (type == BanType.PERMANENT) return null;
        if (remainingSeconds <= 0) return Instant.now();
        return Instant.now().plusSeconds(remainingSeconds);
    }

    /** prüft, dass alle übergebenen Gründe im ReasonManager (Typ BAN) existieren. */
    private void validateBanReasons(List<String> provided) throws SQLException {
        if (provided == null || provided.isEmpty())
            throw new IllegalArgumentException("Es muss mindestens ein gültiger BAN-Grund angegeben werden.");

        List<Reason> valid = reasons.loadAll(ReasonType.BAN);
        Set<String> validNames = new HashSet<>();
        for (Reason r : valid) validNames.add(r.getName().toLowerCase(Locale.ROOT));

        List<String> unknown = new ArrayList<>();
        for (String p : provided) {
            if (p == null || p.isBlank() || !validNames.contains(p.toLowerCase(Locale.ROOT))) {
                unknown.add(p == null ? "<null>" : p);
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Ungültige BAN-Gründe: " + String.join(", ", unknown));
        }
    }

    /** berechnet Auto-Dauer aus BAN-Reasons: max(duration); 0 irgendwo ⇒ permanent. */
    private long computeDurationFromReasonsSeconds(List<String> provided) throws SQLException {
        validateBanReasons(provided); // Sicherheit
        long max = 0;
        for (String name : provided) {
            // hole den konkreten Reason (Name+Typ)
            Reason r = reasons.load(name, ReasonType.BAN);
            if (r == null) continue; // sollte nicht passieren nach validate
            long d = r.getDurationSeconds();
            if (d == 0) return 0; // permanent
            if (d > max) max = d;
        }
        return max;
    }

    /* ---------------- Exists/Status ---------------- */

    public boolean existsActive(UUID uuid) throws SQLException {
        List<Map<String, Object>> rows = db.query(
                "SELECT 1 FROM sentinel_bans WHERE uuid = ? AND active = 1 LIMIT 1",
                uuid.toString()
        );
        return !rows.isEmpty();
    }

    /* ---------------- Create (manuell) ---------------- */

    public Ban create(Ban ban) throws SQLException {
        Objects.requireNonNull(ban, "ban");
        Objects.requireNonNull(ban.getUniqueId(), "ban.uniqueId");
        Objects.requireNonNull(ban.getName(), "ban.name");
        Objects.requireNonNull(ban.getType(), "ban.type");

        validateBanReasons(ban.getReasons());

        // Wenn Typ TEMP aber Reasons ergeben 0 → permanent erzwingen
        long remaining = ban.getRemainingSeconds();
        long auto = computeDurationFromReasonsSeconds(ban.getReasons());
        if (auto == 0) {
            ban.setType(BanType.PERMANENT);
            remaining = 0;
        } else if (ban.getType() != BanType.PERMANENT) {
            // Falls manuelle Dauer kürzer als Auto: wir nehmen die längere (Policy)
            remaining = Math.max(Math.max(0, remaining), auto);
        } else {
            remaining = 0;
        }

        Instant expiresAt = calcExpiresAt(ban.getType(), remaining);
        String reasonsJson = reasonsToJson(ban.getReasons());

        final long remainingFinal = remaining;
        db.inTransaction(con -> {
            db.update(con, """
                INSERT INTO sentinel_bans
                  (uuid, name, operator, type, reasons, remaining_seconds, notice, created_at, expires_at, active)
                VALUES
                  (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, 1)
            """,
                    ban.getUniqueId().toString(),
                    ban.getName(),
                    ban.getOperator(),
                    ban.getType().name(),
                    reasonsJson,
                    remainingFinal,
                    ban.getNotice(),
                    expiresAt == null ? null : java.sql.Timestamp.from(expiresAt)
            );

            List<Map<String, Object>> idRow = db.query("SELECT LAST_INSERT_ID() AS id", con);
            Long id = ((Number) idRow.get(0).get("id")).longValue();
            ban.setId(id)
                    .setCreatedAt(Instant.now())
                    .setExpiresAt(expiresAt)
                    .setRemainingSeconds(remainingFinal)
                    .setActive(true);
            return null;
        });

        return ban;
    }

    public Ban create(UUID uuid, String name, String operator, BanType type,
                      List<String> reasonsList, long remainingSeconds, String notice) throws SQLException {
        return create(new Ban(uuid, name, operator, type, reasonsList, remainingSeconds, notice));
    }

    /* ---------------- Create (AUTO) ---------------- */

    /** Auto-Dauer: berechnet aus Reasons (max; 0 ⇒ PERMANENT). */
    public Ban createAuto(UUID uuid, String name, String operator,
                          List<String> reasonsList, String notice) throws SQLException {
        long auto = computeDurationFromReasonsSeconds(reasonsList);
        BanType type = (auto == 0) ? BanType.PERMANENT : BanType.TEMP;
        return create(new Ban(uuid, name, operator, type, reasonsList, auto, notice));
    }

    /* ---------------- OFFLINE-BAN (manuell) ---------------- */

    public Ban banOffline(String nameOrUuid,
                          String operator,
                          BanType type,
                          List<String> reasonsList,
                          long remainingSeconds,
                          String notice) throws SQLException {
        if (players == null) throw new IllegalStateException("Offline-Ban nicht möglich: SentinelPlayerManager wurde nicht gesetzt.");
        validateBanReasons(reasonsList);

        SentinelPlayer sp = resolvePlayer(nameOrUuid);
        if (existsActive(sp.getUniqueId())) throw new IllegalStateException("Spieler ist bereits aktiv gebannt: " + sp.getName());

        return create(sp.getUniqueId(), sp.getName(), operator, type, reasonsList, remainingSeconds, notice);
    }

    public Ban banOfflineByUuid(UUID uuid,
                                String operator,
                                BanType type,
                                List<String> reasonsList,
                                long remainingSeconds,
                                String notice) throws SQLException {
        if (players == null) throw new IllegalStateException("Offline-Ban nicht möglich: SentinelPlayerManager wurde nicht gesetzt.");
        validateBanReasons(reasonsList);

        SentinelPlayer sp = players.loadByUuid(uuid);
        if (sp == null) throw new IllegalStateException("Spieler nicht in der Datenbank gefunden: " + uuid);
        if (existsActive(uuid)) throw new IllegalStateException("Spieler ist bereits aktiv gebannt: " + sp.getName());

        return create(uuid, sp.getName(), operator, type, reasonsList, remainingSeconds, notice);
    }

    public Ban banOfflineByName(String name,
                                String operator,
                                BanType type,
                                List<String> reasonsList,
                                long remainingSeconds,
                                String notice) throws SQLException {
        if (players == null) throw new IllegalStateException("Offline-Ban nicht möglich: SentinelPlayerManager wurde nicht gesetzt.");
        validateBanReasons(reasonsList);

        SentinelPlayer sp = players.loadByName(name);
        if (sp == null) throw new IllegalStateException("Spieler nicht in der Datenbank gefunden: " + name);
        if (existsActive(sp.getUniqueId())) throw new IllegalStateException("Spieler ist bereits aktiv gebannt: " + sp.getName());

        return create(sp.getUniqueId(), sp.getName(), operator, type, reasonsList, remainingSeconds, notice);
    }

    /* ---------------- OFFLINE-BAN (AUTO) ---------------- */

    /** Offline-Ban mit Auto-Dauer aus Reasons. */
    public Ban banOfflineAuto(String nameOrUuid,
                              String operator,
                              List<String> reasonsList,
                              String notice) throws SQLException {
        if (players == null) throw new IllegalStateException("Offline-Ban nicht möglich: SentinelPlayerManager wurde nicht gesetzt.");

        SentinelPlayer sp = resolvePlayer(nameOrUuid);
        if (existsActive(sp.getUniqueId())) throw new IllegalStateException("Spieler ist bereits aktiv gebannt: " + sp.getName());

        return createAuto(sp.getUniqueId(), sp.getName(), operator, reasonsList, notice);
    }

    public Ban banOfflineAutoByUuid(UUID uuid,
                                    String operator,
                                    List<String> reasonsList,
                                    String notice) throws SQLException {
        if (players == null) throw new IllegalStateException("Offline-Ban nicht möglich: SentinelPlayerManager wurde nicht gesetzt.");

        SentinelPlayer sp = players.loadByUuid(uuid);
        if (sp == null) throw new IllegalStateException("Spieler nicht in der Datenbank gefunden: " + uuid);
        if (existsActive(uuid)) throw new IllegalStateException("Spieler ist bereits aktiv gebannt: " + sp.getName());

        return createAuto(uuid, sp.getName(), operator, reasonsList, notice);
    }

    public Ban banOfflineAutoByName(String name,
                                    String operator,
                                    List<String> reasonsList,
                                    String notice) throws SQLException {
        if (players == null) throw new IllegalStateException("Offline-Ban nicht möglich: SentinelPlayerManager wurde nicht gesetzt.");

        SentinelPlayer sp = players.loadByName(name);
        if (sp == null) throw new IllegalStateException("Spieler nicht in der Datenbank gefunden: " + name);
        if (existsActive(sp.getUniqueId())) throw new IllegalStateException("Spieler ist bereits aktiv gebannt: " + sp.getName());

        return createAuto(sp.getUniqueId(), sp.getName(), operator, reasonsList, notice);
    }

    private SentinelPlayer resolvePlayer(String nameOrUuid) throws SQLException {
        UUID u = tryParseUuid(nameOrUuid);
        SentinelPlayer sp = (u != null) ? players.loadByUuid(u) : players.loadByName(nameOrUuid);
        if (sp == null) throw new IllegalStateException("Spieler nicht in der Datenbank gefunden: " + nameOrUuid);
        return sp;
    }

    private static UUID tryParseUuid(String s) {
        try { return UUID.fromString(s); } catch (Exception ignored) { return null; }
    }

    /* ---------------- Read / Update / Unban / Expire ---------------- */

    public Ban getActive(UUID uuid) throws SQLException {
        List<Map<String, Object>> rows = db.query("""
            SELECT id, uuid, name, operator, type, reasons, remaining_seconds, notice, created_at, expires_at, active
            FROM sentinel_bans
            WHERE uuid = ? AND active = 1
            ORDER BY id DESC
            LIMIT 1
        """, uuid.toString());
        return rows.isEmpty() ? null : mapRow(rows.get(0));
    }

    public List<Ban> listAll(boolean onlyActive) throws SQLException {
        List<Map<String, Object>> rows = onlyActive
                ? db.query("""
                    SELECT id, uuid, name, operator, type, reasons, remaining_seconds, notice, created_at, expires_at, active
                    FROM sentinel_bans WHERE active = 1 ORDER BY created_at DESC
                  """)
                : db.query("""
                    SELECT id, uuid, name, operator, type, reasons, remaining_seconds, notice, created_at, expires_at, active
                    FROM sentinel_bans ORDER BY created_at DESC
                  """);
        List<Ban> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) out.add(mapRow(r));
        return out;
    }

    public List<Ban> listFor(UUID uuid) throws SQLException {
        List<Map<String, Object>> rows = db.query("""
            SELECT id, uuid, name, operator, type, reasons, remaining_seconds, notice, created_at, expires_at, active
            FROM sentinel_bans
            WHERE uuid = ?
            ORDER BY created_at DESC
        """, uuid.toString());
        List<Ban> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) out.add(mapRow(r));
        return out;
    }

    public void setRemaining(long banId, long newRemainingSeconds) throws SQLException {
        long clamped = Math.max(0, newRemainingSeconds);
        Instant newExpires = (clamped == 0) ? Instant.now() : Instant.now().plusSeconds(clamped);
        db.update("""
            UPDATE sentinel_bans
               SET remaining_seconds = ?, expires_at = ?, active = (CASE WHEN ? > 0 THEN 1 ELSE 0 END)
             WHERE id = ?
        """,
                clamped,
                java.sql.Timestamp.from(newExpires),
                clamped,
                banId);
    }

    public void setNotice(long banId, String notice) throws SQLException {
        db.update("UPDATE sentinel_bans SET notice = ? WHERE id = ?", notice, banId);
    }

    public boolean unban(long banId) throws SQLException {
        int affected = db.update("UPDATE sentinel_bans SET active = 0 WHERE id = ? AND active = 1", banId);
        return affected > 0;
    }

    public int unbanAll(UUID uuid) throws SQLException {
        return db.update("UPDATE sentinel_bans SET active = 0 WHERE uuid = ? AND active = 1", uuid.toString());
    }

    public int expireDueBans() throws SQLException {
        return db.update("""
            UPDATE sentinel_bans
               SET active = 0
             WHERE active = 1
               AND expires_at IS NOT NULL
               AND expires_at <= CURRENT_TIMESTAMP
        """);
    }
}
