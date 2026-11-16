package me.hackerguardian.main.detection.packet;

import org.junit.jupiter.api.Test;

import static me.hackerguardian.main.detection.packet.PacketGroundReachability.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class PacketResearchRunsTest {
    @Test
    void emptySnapshotDoesNotInventCompletedRunLengths() {
        var study = new PacketResearchRuns();
        var snapshot = study.snapshot();
        assertEquals(0, snapshot.acceptedRuns().completed());
        assertEquals(0, snapshot.timingClusters().retained());
        assertNull(snapshot.acceptedRuns().p50());
        assertNull(snapshot.timingClusters().max());
        assertEquals(snapshot, study.snapshot());
    }

    @Test
    void timingsSeparatedOnlyByWarmupFormOneClusterButNotOneValidRun() {
        var study = new PacketResearchRuns();
        study.observe(CONSERVATIVE_REACHABLE);
        study.observe(OUTSIDE_RESEARCH_ENVELOPE);
        study.observe(CONSERVATIVE_REACHABLE); // run length 3
        study.observe(TICK_GAP);
        study.observe(WARMUP);                 // do not split timing cluster
        study.observe(AMBIGUOUS_TICK);
        study.observe(WARMUP);
        study.observe(CONSERVATIVE_REACHABLE); // closes timing cluster of 2
        study.observe(CONSERVATIVE_REACHABLE); // run length 2
        study.observe(CONTEXT_CHANGED);       // closes run
        study.observe(STALE_SERVER_SAMPLE);
        study.correction();                   // closes isolated timing cluster

        var snap = study.snapshot();
        assertEquals(5, snap.totalModeled());
        assertEquals(3, snap.totalTimingRejects());
        assertEquals(2, snap.warmupsInsideTimingClusters());
        assertEquals(0, snap.activeAcceptedRun());
        assertEquals(0, snap.activeTimingCluster());
        assertEquals(2, snap.acceptedRuns().completed());
        assertEquals(2L, snap.acceptedRuns().p50());
        assertEquals(3L, snap.acceptedRuns().p95());
        assertEquals(2, snap.runs2to4());
        assertEquals(0, snap.singleRuns());
        assertEquals(2, snap.timingClusters().completed());
        assertEquals(1L, snap.timingClusters().p50());
        assertEquals(2L, snap.timingClusters().p95());
        assertEquals(1, snap.isolatedTimingClusters());
        assertEquals(1, snap.multiTimingClusters());
        assertEquals(snap, study.snapshot(), "Admin reads must not seal active sequences");
    }

    @Test
    void liveSequencesAreNotPrematurelyCountedAsCompleted() {
        var study = new PacketResearchRuns();
        study.observe(CONSERVATIVE_REACHABLE);
        study.observe(CONSERVATIVE_REACHABLE);
        assertEquals(2, study.snapshot().activeAcceptedRun());
        assertEquals(0, study.snapshot().acceptedRuns().completed());

        study.observe(TICK_GAP);
        study.observe(WARMUP);
        assertEquals(1, study.snapshot().acceptedRuns().completed());
        assertEquals(2L, study.snapshot().acceptedRuns().max());
        assertEquals(1, study.snapshot().activeTimingCluster());
        assertEquals(0, study.snapshot().timingClusters().completed());

        study.correction();
        assertEquals(1, study.snapshot().timingClusters().completed());
        assertEquals(0, study.snapshot().activeTimingCluster());
        study.observe(WARMUP);
        study.observe(CONSERVATIVE_REACHABLE);
        assertEquals(1, study.snapshot().activeAcceptedRun());
        assertEquals(1, study.snapshot().acceptedRuns().completed());
    }

    @Test
    void unsupportedContextTerminatesGapClusterEvenWithoutModeledPackets() {
        var study = new PacketResearchRuns();
        study.observe(TICK_GAP);
        study.observe(WARMUP);
        study.observe(UNSUPPORTED_CONTEXT);
        study.observe(STALE_SERVER_SAMPLE);
        study.observe(INVALID_GROUND_CLAIM);
        var snap = study.snapshot();
        assertEquals(2, snap.timingClusters().completed());
        assertEquals(2, snap.isolatedTimingClusters());
        assertEquals(0, snap.multiTimingClusters());
        assertEquals(0, snap.acceptedRuns().completed());
        assertEquals(2, snap.totalTimingRejects());
        assertEquals(1, snap.warmupsInsideTimingClusters());
    }

    @Test
    void completedRunAndClusterRingsStayAt128WithoutLosingLifetimeCounts() {
        var study = new PacketResearchRuns();
        for (int length = 1; length <= 200; length++) {
            for (int i = 0; i < length; i++) study.observe(CONSERVATIVE_REACHABLE);
            study.observe(CONTEXT_CHANGED);
        }
        var runs = study.snapshot();
        assertEquals(200, runs.acceptedRuns().completed());
        assertEquals(128, runs.acceptedRuns().retained());
        assertEquals(136L, runs.acceptedRuns().p50()); // last 128 lengths 73..200
        assertEquals(194L, runs.acceptedRuns().p95());
        assertEquals(200L, runs.acceptedRuns().max());
        assertEquals(20100, runs.totalModeled());
        assertEquals(1, runs.singleRuns());
        assertEquals(3, runs.runs2to4());
        assertEquals(15, runs.runs5to19());
        assertEquals(181, runs.runs20Plus());

        for (int length = 1; length <= 200; length++) {
            for (int i = 0; i < length; i++) study.observe(TICK_GAP);
            study.observe(CONTEXT_CHANGED);
        }
        var clusters = study.snapshot();
        assertEquals(200, clusters.timingClusters().completed());
        assertEquals(128, clusters.timingClusters().retained());
        assertEquals(136L, clusters.timingClusters().p50());
        assertEquals(194L, clusters.timingClusters().p95());
        assertEquals(200L, clusters.timingClusters().max());
        assertEquals(1, clusters.isolatedTimingClusters());
        assertEquals(199, clusters.multiTimingClusters());
        assertEquals(20100, clusters.totalTimingRejects());
        assertEquals(clusters, study.snapshot());
    }

    @Test
    void nullStatusCannotPoisonResearchCounters() {
        var study = new PacketResearchRuns();
        assertThrows(IllegalArgumentException.class, () -> study.observe(null));
        assertEquals(0, study.snapshot().totalModeled());
        assertEquals(0, study.snapshot().totalTimingRejects());
    }
}
