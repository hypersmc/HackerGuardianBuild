package me.hackerguardian.main.modsys;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class ModFingerprint {

    private volatile String brand; // e.g. "vanilla", "forge", "fabric", etc.
    private final Set<String> channels = Collections.synchronizedSet(new HashSet<>());
    private volatile long lastUpdatedMs;

    public String getBrand() {
        return brand;
    }

    public Set<String> getChannelsSnapshot() {
        synchronized (channels) {
            return new HashSet<>(channels);
        }
    }

    public long getLastUpdatedMs() {
        return lastUpdatedMs;
    }

    public void setBrand(String brand, long nowMs) {
        if (brand == null || brand.isBlank()) return;
        this.brand = brand;
        this.lastUpdatedMs = nowMs;
    }

    public void addChannels(Set<String> newChannels, long nowMs) {
        if (newChannels == null || newChannels.isEmpty()) return;
        channels.addAll(newChannels);
        this.lastUpdatedMs = nowMs;
    }

    public boolean isLikelyModded() {
        String b = brand == null ? "" : brand.toLowerCase();
        if (!b.isBlank() && !b.equals("vanilla")) return true;

        // Heuristics: channels that strongly indicate modded environments
        Set<String> ch = getChannelsSnapshot();
        for (String c : ch) {
            String lc = c.toLowerCase();
            if (lc.startsWith("fml:") || lc.startsWith("forge:")
                    || lc.contains("fabric") || lc.contains("quilt")) {
                return true;
            }
        }
        return false;
    }
}