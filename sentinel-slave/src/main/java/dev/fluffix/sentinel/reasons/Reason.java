package dev.fluffix.sentinel.reasons;

/**
 * Repräsentiert einen Grund (Reason) für BAN, MUTE oder REPORT.
 * Dauer ist in Sekunden angegeben, 0 bedeutet permanent.
 */
public class Reason {

    private long id;
    private String name;
    private ReasonType type;
    private long durationSeconds; // DB-Spalte: duration

    public Reason() {
    }

    public Reason(long id, String name, ReasonType type, long durationSeconds) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.durationSeconds = durationSeconds;
    }

    public long getId() {
        return id;
    }

    public Reason setId(long id) {
        this.id = id;
        return this;
    }

    public String getName() {
        return name;
    }

    public Reason setName(String name) {
        this.name = name;
        return this;
    }

    public ReasonType getType() {
        return type;
    }

    public Reason setType(ReasonType type) {
        this.type = type;
        return this;
    }

    public long getDurationSeconds() {
        return durationSeconds;
    }

    public Reason setDurationSeconds(long durationSeconds) {
        this.durationSeconds = durationSeconds;
        return this;
    }

    @Override
    public String toString() {
        return "Reason{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", type=" + type +
                ", durationSeconds=" + durationSeconds +
                '}';
    }
}
