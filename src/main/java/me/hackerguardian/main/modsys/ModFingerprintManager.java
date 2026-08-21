package me.hackerguardian.main.modsys;

import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ModFingerprintManager {

    private final ConcurrentHashMap<UUID, ModFingerprint> map = new ConcurrentHashMap<>();

    public ModFingerprint getOrCreate(UUID playerId) {
        return map.computeIfAbsent(playerId, k -> new ModFingerprint());
    }

    public ModFingerprint getOrNull(UUID playerId) {
        return map.get(playerId);
    }

    public void remove(UUID playerId) {
        map.remove(playerId);
    }

    public void clear() {
        map.clear();
    }

    public String debugSummary(Player p) {
        ModFingerprint fp = getOrNull(p.getUniqueId());
        if (fp == null) return "no fingerprint";
        return "brand=" + fp.getBrand()
                + " channels=" + fp.getChannelsSnapshot().size()
                + " likelyModded=" + fp.isLikelyModded();
    }
}