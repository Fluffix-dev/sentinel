package dev.fluffix.sentinel.ban;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class Ban {
    private Long id;                     // DB-Primärschlüssel
    private UUID uniqueId;               // Spieler UUID
    private String name;                 // Spielername
    private String operator;             // wer gebannt hat (Name/UUID/Freitext)
    private BanType type;                // TEMP | PERMANENT | IP
    private List<String> reasons;        // Gründe (Liste)
    private long remainingSeconds;       // Restdauer in Sekunden (0 = permanent)
    private String notice;               // Hinweis/Notiz

    // abgeleitete/DB-Felder (nur lesen)
    private Instant createdAt;
    private Instant expiresAt;           // null, wenn permanent
    private boolean active;              // true, wenn jetzt aktiv

    public Ban() {
        this.reasons = new ArrayList<>();
    }

    public Ban(UUID uniqueId, String name, String operator, BanType type,
               List<String> reasons, long remainingSeconds, String notice) {
        this.uniqueId = Objects.requireNonNull(uniqueId, "uniqueId");
        this.name = Objects.requireNonNull(name, "name");
        this.operator = operator;
        this.type = Objects.requireNonNull(type, "type");
        this.reasons = (reasons == null) ? new ArrayList<>() : new ArrayList<>(reasons);
        this.remainingSeconds = Math.max(0, remainingSeconds);
        this.notice = notice;
    }

    // ---- Getter/Setter ----
    public Long getId() { return id; }
    public Ban setId(Long id) { this.id = id; return this; }

    public UUID getUniqueId() { return uniqueId; }
    public Ban setUniqueId(UUID uniqueId) { this.uniqueId = uniqueId; return this; }

    public String getName() { return name; }
    public Ban setName(String name) { this.name = name; return this; }

    public String getOperator() { return operator; }
    public Ban setOperator(String operator) { this.operator = operator; return this; }

    public BanType getType() { return type; }
    public Ban setType(BanType type) { this.type = type; return this; }

    public List<String> getReasons() { return reasons; }
    public Ban setReasons(List<String> reasons) {
        this.reasons = (reasons == null) ? new ArrayList<>() : new ArrayList<>(reasons);
        return this;
    }

    public long getRemainingSeconds() { return remainingSeconds; }
    public Ban setRemainingSeconds(long remainingSeconds) {
        this.remainingSeconds = Math.max(0, remainingSeconds);
        return this;
    }

    public String getNotice() { return notice; }
    public Ban setNotice(String notice) { this.notice = notice; return this; }

    public Instant getCreatedAt() { return createdAt; }
    public Ban setCreatedAt(Instant createdAt) { this.createdAt = createdAt; return this; }

    public Instant getExpiresAt() { return expiresAt; }
    public Ban setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; return this; }

    public boolean isActive() { return active; }
    public Ban setActive(boolean active) { this.active = active; return this; }
}
