package me.hackerguardian.main.detection;

import me.hackerguardian.main.detection.telemetry.BehaviorSnapshot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Observe-only aggregation engine for Detection v2.
 *
 * Detectors produce explainable findings. This class aggregates and stores a
 * small in-memory history, but intentionally has no punishment/replay/report
 * side effects. A separate policy layer can be added later after the evidence
 * model is stable and tested.
 */
public final class DetectionEngine {

    private final Logger logger;
    private final int historySize;
    private final List<Detector> detectors = new CopyOnWriteArrayList<>();
    private final Map<UUID, DetectionAssessment> latest = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<DetectionAssessment>> history = new ConcurrentHashMap<>();

    public DetectionEngine(Logger logger, int historySize) {
        this.logger = logger;
        this.historySize = Math.max(1, Math.min(historySize, 200));
    }

    public void registerDetector(Detector detector) {
        if (detector == null) return;
        for (Detector existing : detectors) {
            if (existing.id().equalsIgnoreCase(detector.id())) {
                throw new IllegalArgumentException("Duplicate detector id: " + detector.id());
            }
        }
        detectors.add(detector);
    }

    public int getDetectorCount() {
        return detectors.size();
    }

    public List<String> getDetectorIds() {
        List<String> ids = new ArrayList<>();
        for (Detector detector : detectors) ids.add(detector.id());
        return Collections.unmodifiableList(ids);
    }

    public DetectionAssessment assess(BehaviorSnapshot snapshot) {
        if (snapshot == null) return null;

        List<DetectionFinding> findings = new ArrayList<>();
        for (Detector detector : detectors) {
            try {
                List<DetectionFinding> detectorFindings = detector.analyze(snapshot);
                if (detectorFindings == null || detectorFindings.isEmpty()) continue;
                for (DetectionFinding finding : detectorFindings) {
                    if (finding != null) findings.add(finding);
                }
            } catch (Exception e) {
                if (logger != null) {
                    logger.warning("Detection v2 detector '" + detector.id() + "' failed: " + e.getMessage());
                }
            }
        }

        double weightedMax = 0.0;
        double weightedSum = 0.0;
        double reliabilitySum = 0.0;
        double strongestReliability = 0.0;

        for (DetectionFinding finding : findings) {
            double weighted = finding.getWeightedScore();
            weightedMax = Math.max(weightedMax, weighted);
            weightedSum += finding.getScore() * finding.getReliability();
            reliabilitySum += finding.getReliability();
            strongestReliability = Math.max(strongestReliability, finding.getReliability());
        }

        // Deliberately conservative aggregation: one strong finding matters,
        // while many weak/noisy findings cannot simply add their way to 100%.
        double weightedMean = reliabilitySum <= 0.0 ? 0.0 : weightedSum / reliabilitySum;
        double risk = clamp((weightedMax * 0.70) + (weightedMean * 0.30));

        DetectionAssessment assessment = new DetectionAssessment(
                snapshot.getPlayerId(),
                snapshot.getPlayerName(),
                snapshot.getCapturedAtMs(),
                snapshot.getWindowMs(),
                risk,
                strongestReliability,
                findings
        );

        latest.put(snapshot.getPlayerId(), assessment);
        Deque<DetectionAssessment> deque = history.computeIfAbsent(snapshot.getPlayerId(), ignored -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(assessment);
            while (deque.size() > historySize) deque.removeFirst();
        }

        return assessment;
    }

    public DetectionAssessment getLatest(UUID playerId) {
        return playerId == null ? null : latest.get(playerId);
    }

    public List<DetectionAssessment> getHistory(UUID playerId) {
        if (playerId == null) return Collections.emptyList();
        Deque<DetectionAssessment> deque = history.get(playerId);
        if (deque == null) return Collections.emptyList();
        synchronized (deque) {
            return Collections.unmodifiableList(new ArrayList<>(deque));
        }
    }

    public void clearPlayer(UUID playerId) {
        if (playerId == null) return;
        latest.remove(playerId);
        history.remove(playerId);
    }

    public void clear() {
        latest.clear();
        history.clear();
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) return 0.0;
        if (value < 0.0) return 0.0;
        if (value > 1.0) return 1.0;
        return value;
    }
}
