package me.hackerguardian.main.aicore;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


public class HGSuspicionManager {

    private final Map<UUID, Double> movementSuspicion = new ConcurrentHashMap<>();
    private final Map<UUID, Double> combatSuspicion = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastUpdate = new ConcurrentHashMap<>();

    public void updateMovement(UUID uuid, double suspicion) {
        movementSuspicion.put(uuid, clamp01(suspicion));
        lastUpdate.put(uuid, System.currentTimeMillis());
    }

    public void updateCombat(UUID uuid, double suspicion) {
        combatSuspicion.put(uuid, clamp01(suspicion));
        lastUpdate.put(uuid, System.currentTimeMillis());
    }

    public double getMovementSuspicion(UUID uuid) {
        return movementSuspicion.getOrDefault(uuid, 0.0);
    }

    public double getCombatSuspicion(UUID uuid) {
        return combatSuspicion.getOrDefault(uuid, 0.0);
    }

    public double getOverallSuspicion(UUID uuid) {
        double m = getMovementSuspicion(uuid);
        double c = getCombatSuspicion(uuid);

        // simple average;
        if (m == 0.0 && c == 0.0) return 0.0;
        return (m + c) / 2.0;
    }

    public long getLastUpdate(UUID uuid) {
        return lastUpdate.getOrDefault(uuid, 0L);
    }

    private double clamp01(double v) {
        if (Double.isNaN(v)) return 0.0;
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }
}