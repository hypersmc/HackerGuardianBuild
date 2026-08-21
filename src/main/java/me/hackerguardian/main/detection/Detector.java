package me.hackerguardian.main.detection;

import me.hackerguardian.main.detection.telemetry.BehaviorSnapshot;

import java.util.List;

/**
 * Pluggable analysis unit for Detection v2.
 *
 * A detector may be heuristic, statistical, or backed by a future ML model.
 * It can only emit findings; it has no access to punishment APIs.
 */
public interface Detector {

    String id();

    List<DetectionFinding> analyze(BehaviorSnapshot snapshot);
}
