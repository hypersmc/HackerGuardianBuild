package me.hackerguardian.main.detection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Aggregated, observe-only result for one player snapshot.
 *
 * This object is evidence, not a punishment decision. Enforcement belongs in a
 * separate policy layer so a detector/model cannot ban players by itself.
 */
public final class DetectionAssessment {

    private final UUID playerId;
    private final String playerName;
    private final long capturedAtMs;
    private final long windowMs;
    private final double riskScore;
    private final double reliability;
    private final List<DetectionFinding> findings;

    public DetectionAssessment(UUID playerId,
                               String playerName,
                               long capturedAtMs,
                               long windowMs,
                               double riskScore,
                               double reliability,
                               List<DetectionFinding> findings) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.capturedAtMs = capturedAtMs;
        this.windowMs = Math.max(0L, windowMs);
        this.riskScore = clamp(riskScore);
        this.reliability = clamp(reliability);
        this.findings = Collections.unmodifiableList(
                findings == null ? new ArrayList<>() : new ArrayList<>(findings)
        );
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public long getCapturedAtMs() {
        return capturedAtMs;
    }

    public long getWindowMs() {
        return windowMs;
    }

    public double getRiskScore() {
        return riskScore;
    }

    public double getReliability() {
        return reliability;
    }

    public List<DetectionFinding> getFindings() {
        return findings;
    }

    public boolean hasFindings() {
        return !findings.isEmpty();
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) return 0.0;
        if (value < 0.0) return 0.0;
        if (value > 1.0) return 1.0;
        return value;
    }
}
