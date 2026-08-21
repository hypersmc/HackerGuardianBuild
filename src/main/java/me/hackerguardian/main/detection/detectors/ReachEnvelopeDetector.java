package me.hackerguardian.main.detection.detectors;

import me.hackerguardian.main.detection.DetectionCategory;
import me.hackerguardian.main.detection.DetectionFinding;
import me.hackerguardian.main.detection.Detector;
import me.hackerguardian.main.detection.telemetry.BehaviorSnapshot;
import org.bukkit.GameMode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Conservative server-observed hit-distance envelope.
 *
 * This is evidence only. Location snapshots are not precise enough to call a
 * player a cheater by themselves, so reliability is intentionally capped and
 * reduced further during high latency / low TPS.
 */
public final class ReachEnvelopeDetector implements Detector {

    private final double softDistance;
    private final double hardDistance;

    public ReachEnvelopeDetector(double softDistance, double hardDistance) {
        this.softDistance = Math.max(3.0, softDistance);
        this.hardDistance = Math.max(this.softDistance + 0.1, hardDistance);
    }

    @Override
    public String id() {
        return "combat.reach-envelope";
    }

    @Override
    public List<DetectionFinding> analyze(BehaviorSnapshot snapshot) {
        if (snapshot.getHitCount() <= 0) return Collections.emptyList();
        if (snapshot.getGameMode() != GameMode.SURVIVAL && snapshot.getGameMode() != GameMode.ADVENTURE) {
            return Collections.emptyList();
        }

        double observed = snapshot.getMaxHitDistance();
        if (observed <= softDistance) return Collections.emptyList();

        double progress = (observed - softDistance) / (hardDistance - softDistance);
        progress = clamp(progress);
        double score = 0.25 + (progress * 0.75);

        double reliability = 0.65;
        if (snapshot.getPingMs() >= 150) reliability *= 0.75;
        if (snapshot.getPingMs() >= 250) reliability *= 0.70;
        if (snapshot.getTps() > 0.0 && snapshot.getTps() < 18.0) reliability *= 0.70;

        Map<String, Double> evidence = new LinkedHashMap<>();
        evidence.put("max_hit_distance", observed);
        evidence.put("average_hit_distance", snapshot.getAverageHitDistance());
        evidence.put("soft_distance", softDistance);
        evidence.put("hard_distance", hardDistance);
        evidence.put("ping_ms", (double) snapshot.getPingMs());
        evidence.put("tps", snapshot.getTps());

        DetectionFinding finding = new DetectionFinding(
                id(),
                DetectionCategory.COMBAT,
                score,
                reliability,
                "Server-observed hit distance exceeded the configured evidence envelope.",
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
