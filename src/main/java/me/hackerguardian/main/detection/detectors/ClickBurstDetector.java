package me.hackerguardian.main.detection.detectors;

import me.hackerguardian.main.detection.DetectionCategory;
import me.hackerguardian.main.detection.DetectionFinding;
import me.hackerguardian.main.detection.Detector;
import me.hackerguardian.main.detection.telemetry.BehaviorSnapshot;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Flags sustained, unusually high arm-swing rates as weak combat evidence.
 *
 * High CPS is not proof of cheating. This detector therefore has deliberately
 * low reliability and exists primarily to prove the v2 evidence/aggregation
 * path with an explainable signal.
 */
public final class ClickBurstDetector implements Detector {

    private final double softCps;
    private final double hardCps;
    private final int minimumSwings;

    public ClickBurstDetector(double softCps, double hardCps, int minimumSwings) {
        this.softCps = Math.max(1.0, softCps);
        this.hardCps = Math.max(this.softCps + 1.0, hardCps);
        this.minimumSwings = Math.max(1, minimumSwings);
    }

    @Override
    public String id() {
        return "combat.click-burst";
    }

    @Override
    public List<DetectionFinding> analyze(BehaviorSnapshot snapshot) {
        if (snapshot.getSwingCount() < minimumSwings || snapshot.getSwingCps() <= softCps) {
            return Collections.emptyList();
        }

        double progress = clamp((snapshot.getSwingCps() - softCps) / (hardCps - softCps));
        double score = 0.20 + (progress * 0.80);
        double reliability = 0.35;

        if (snapshot.getTps() > 0.0 && snapshot.getTps() < 18.0) reliability *= 0.70;

        Map<String, Double> evidence = new LinkedHashMap<>();
        evidence.put("swing_cps", snapshot.getSwingCps());
        evidence.put("swing_count", (double) snapshot.getSwingCount());
        evidence.put("hit_count", (double) snapshot.getHitCount());
        evidence.put("hit_rate", snapshot.getHitRate());
        evidence.put("soft_cps", softCps);
        evidence.put("hard_cps", hardCps);

        DetectionFinding finding = new DetectionFinding(
                id(),
                DetectionCategory.COMBAT,
                score,
                reliability,
                "Sustained arm-swing rate exceeded the configured observation envelope.",
                evidence
        );
        return Collections.singletonList(finding);
    }

    private static double clamp(double value) {
        if (value < 0.0) return 0.0;
        if (value > 1.0) return 1.0;
        return value;
    }
}
