package dev.fluffix.sentinel.reasons;

import java.util.Objects;

public class Reason {
    private String name;          // eindeutiger Name/Key
    private ReasonType type;      // BAN, MUTE, REPORT
    private long durationSeconds; // 0 = permanent

    public Reason(String name, ReasonType type, long durationSeconds) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        this.durationSeconds = Math.max(0, durationSeconds);
    }

    public Reason() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public ReasonType getType() { return type; }
    public void setType(ReasonType type) { this.type = type; }
    public long getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(long durationSeconds) { this.durationSeconds = Math.max(0, durationSeconds); }
}
