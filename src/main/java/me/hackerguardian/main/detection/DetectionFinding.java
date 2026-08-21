package me.hackerguardian.main.detection;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One explainable piece of evidence emitted by a detector.
 *
 * score describes how unusual the observation is (0..1).
 * reliability describes how much trust should be placed in this detector for
 * this particular observation (0..1). They are kept separate on purpose: a
 * very unusual but noisy signal should not automatically become a strong
 * enforcement decision.
 */
public final class DetectionFinding {

    private final String detectorId;
    private final DetectionCategory category;
    private final double score;
    private final double reliability;
    private final String summary;
    private final Map<String, Double> evidence;

    public DetectionFinding(String detectorId,
                            DetectionCategory category,
                            double score,
                            double reliability,
                            String summary,
                            Map<String, Double> evidence) {
        if (detectorId == null || detectorId.isBlank()) {
            throw new IllegalArgumentException("detectorId is required");
        }
        this.detectorId = detectorId;
        this.category = category == null ? DetectionCategory.OTHER : category;
        this.score = clamp(score);
        this.reliability = clamp(reliability);
        this.summary = summary == null ? "" : summary;

        Map<String, Double> copy = new LinkedHashMap<>();
        if (evidence != null) {
            for (Map.Entry<String, Double> entry : evidence.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) continue;
                double value = entry.getValue();
                if (Double.isNaN(value) || Double.isInfinite(value)) continue;
                copy.put(entry.getKey(), value);
            }
        }
        this.evidence = Collections.unmodifiableMap(copy);
    }

    public String getDetectorId() {
        return detectorId;
    }

    public DetectionCategory getCategory() {
        return category;
    }

    public double getScore() {
        return score;
    }

    public double getReliability() {
        return reliability;
    }

    public double getWeightedScore() {
        return score * reliability;
    }

    public String getSummary() {
        return summary;
    }

    public Map<String, Double> getEvidence() {
        return evidence;
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) return 0.0;
        if (value < 0.0) return 0.0;
        if (value > 1.0) return 1.0;
        return value;
    }
}
