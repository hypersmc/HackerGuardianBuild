package me.hackerguardian.main.detection.deterministic;

import me.hackerguardian.main.detection.DetectionFinding;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small in-memory bridge between event-driven deterministic checks and the
 * snapshot-driven DetectionEngine.
 *
 * Repeated events from the same check are collapsed to the strongest recent
 * finding and annotated with recent_events so one noisy check cannot inflate an
 * assessment merely by producing many identical findings in one window.
 */
public final class DeterministicEvidenceBuffer {

    private final long retentionMs;
    private final int maxEventsPerPlayer;
    private final Map<UUID, Deque<TimedFinding>> findings = new ConcurrentHashMap<>();

    public DeterministicEvidenceBuffer(long retentionMs, int maxEventsPerPlayer) {
        this.retentionMs = Math.max(1000L, Math.min(retentionMs, 120_000L));
        this.maxEventsPerPlayer = Math.max(8, Math.min(maxEventsPerPlayer, 1024));
    }

    public void record(UUID playerId, DetectionFinding finding) {
        record(playerId, finding, System.currentTimeMillis());
    }

    void record(UUID playerId, DetectionFinding finding, long timestampMs) {
        if (playerId == null || finding == null) return;
        Deque<TimedFinding> deque = findings.computeIfAbsent(playerId, ignored -> new ArrayDeque<>());
        synchronized (deque) {
            prune(deque, timestampMs - retentionMs);
            deque.addLast(new TimedFinding(timestampMs, finding));
            while (deque.size() > maxEventsPerPlayer) deque.removeFirst();
        }
    }

    public List<DetectionFinding> recent(UUID playerId, long nowMs, long windowMs) {
        if (playerId == null) return List.of();
        Deque<TimedFinding> deque = findings.get(playerId);
        if (deque == null) return List.of();

        long effectiveWindow = Math.max(1L, Math.min(windowMs, retentionMs));
        long cutoff = nowMs - effectiveWindow;
        Map<String, Aggregate> byDetector = new LinkedHashMap<>();

        synchronized (deque) {
            prune(deque, nowMs - retentionMs);
            for (TimedFinding timed : deque) {
                if (timed.timestampMs < cutoff) continue;
                Aggregate aggregate = byDetector.computeIfAbsent(
                        timed.finding.getDetectorId(), ignored -> new Aggregate());
                aggregate.count++;
                if (aggregate.strongest == null
                        || timed.finding.getWeightedScore() > aggregate.strongest.getWeightedScore()) {
                    aggregate.strongest = timed.finding;
                }
            }
        }

        if (byDetector.isEmpty()) return List.of();
        List<DetectionFinding> result = new ArrayList<>(byDetector.size());
        for (Aggregate aggregate : byDetector.values()) {
            DetectionFinding strongest = aggregate.strongest;
            Map<String, Double> evidence = new LinkedHashMap<>(strongest.getEvidence());
            evidence.put("recent_events", (double) aggregate.count);
            result.add(new DetectionFinding(
                    strongest.getDetectorId(),
                    strongest.getCategory(),
                    strongest.getScore(),
                    strongest.getReliability(),
                    strongest.getStrength(),
                    strongest.getSummary(),
                    evidence
            ));
        }
        return result;
    }

    public void clearPlayer(UUID playerId) {
        if (playerId != null) findings.remove(playerId);
    }

    public void clear() {
        findings.clear();
    }

    private static void prune(Deque<TimedFinding> deque, long cutoff) {
        while (!deque.isEmpty() && deque.peekFirst().timestampMs < cutoff) deque.removeFirst();
    }

    private static final class TimedFinding {
        final long timestampMs;
        final DetectionFinding finding;

        TimedFinding(long timestampMs, DetectionFinding finding) {
            this.timestampMs = timestampMs;
            this.finding = finding;
        }
    }

    private static final class Aggregate {
        DetectionFinding strongest;
        int count;
    }
}
