package dev.fluffix.sentinel.player;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class SentinelPlayer {
    private final UUID uniqueId;
    private String name;
    private final Set<String> ipAddresses;
    private int points;

    public SentinelPlayer(UUID uniqueId, String name) {
        this.uniqueId = uniqueId;
        this.name = name;
        this.ipAddresses = new HashSet<>();
        this.points = 0;
    }

    public UUID getUniqueId() {
        return uniqueId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Set<String> getIpAddresses() {
        return Collections.unmodifiableSet(ipAddresses);
    }

    public void addIpAddress(String ip) {
        if (ip != null && !ip.isBlank()) {
            ipAddresses.add(ip);
        }
    }

    public int getPoints() {
        return points;
    }

    public void setPoints(int points) {
        this.points = Math.max(points, 0);
    }

    public void addPoints(int amount) {
        if (amount > 0) {
            this.points += amount;
        }
    }

    public void removePoints(int amount) {
        if (amount > 0) {
            this.points = Math.max(0, this.points - amount);
        }
    }
}
