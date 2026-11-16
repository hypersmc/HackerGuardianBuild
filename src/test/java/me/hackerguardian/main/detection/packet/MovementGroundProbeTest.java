package me.hackerguardian.main.detection.packet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MovementGroundProbeTest {
    private static final long STEP = 50_000_000L;

    private static MovementTickTimeline.TickInput frame(double x) {
        return frame(x, false, true, true, true, true);
    }

    private static MovementTickTimeline.TickInput frame(double x, boolean flying,
                                                          boolean grounded, boolean ordinary,
                                                          boolean noEffects, boolean defaultSpeed) {
        return new MovementTickTimeline.TickInput(
                "world",
                new MovementPacketBuffer.Position(x, 64, 10),
                new MovementPacketBuffer.Velocity(0.25, 0, 0),
                new MovementTickTimeline.PlayerFlags(
                        grounded, flying, false, false, false, false),
                new MovementTickTimeline.Environment(true, false, true, false, false),
                new MovementTickTimeline.ProbeContext(true, ordinary, noEffects, defaultSpeed));
    }

    @Test
    void ordinaryGroundMovementProducesMeasurementsNotCheatFindings() {
        var probe = new MovementGroundProbe();
        probe.sample(1, STEP, 0, frame(0), true, true);
        assertEquals(MovementGroundProbe.Result.WARMUP, probe.snapshot().lastResult());
        probe.sample(2, 2 * STEP, 0, frame(0.28), true, true);
        var result = probe.snapshot();
        assertEquals(MovementGroundProbe.Result.WITHIN_RESEARCH_ENVELOPE, result.lastResult());
        assertEquals(1, result.evaluated());
        assertEquals(0, result.researchOutliers());
        assertEquals(0.28, result.horizontalDistance(), 1e-12);
        assertEquals(1.2, result.envelope(), 1e-12);
        assertEquals(1, result.noDecision()); // first sample is warmup
        assertEquals(result, probe.snapshot()); // reading must be side-effect free
    }

    @Test
    void repeatedExtremeMovementIsResearchTelemetryOnly() {
        var probe = new MovementGroundProbe();
        probe.sample(1, STEP, 0, frame(0), true, true);
        probe.sample(2, 2 * STEP, 0, frame(3), true, true);
        probe.sample(3, 3 * STEP, 0, frame(6), true, true);
        var result = probe.snapshot();
        assertEquals(MovementGroundProbe.Result.RESEARCH_OUTLIER, result.lastResult());
        assertEquals(2, result.researchOutliers());
        assertEquals(2, result.consecutiveOutlierSamples());
        assertEquals(3, result.lastResearchOutlierTick());

        probe.sample(4, 4 * STEP, 0, frame(6.2), true, true);
        assertEquals(0, probe.snapshot().consecutiveOutlierSamples());
        assertEquals(2, probe.snapshot().researchOutliers());
    }

    @Test
    void creativeFlightAndUnsupportedMovementNeverBecomeEligible() {
        var probe = new MovementGroundProbe();
        probe.sample(1, STEP, 0, frame(0), true, true);
        probe.sample(2, 2 * STEP, 0, frame(9, true, false, true, true, true), true, true);
        assertEquals(MovementGroundProbe.Result.SPECIAL_MOVEMENT, probe.snapshot().lastResult());
        probe.sample(3, 3 * STEP, 0, frame(10, false, true, false, true, true), true, true);
        assertEquals(MovementGroundProbe.Result.SPECIAL_MOVEMENT, probe.snapshot().lastResult());
        probe.sample(4, 4 * STEP, 0, frame(11, false, true, false, true, true), true, true);
        assertEquals(MovementGroundProbe.Result.UNSAFE_TERRAIN, probe.snapshot().lastResult());
        probe.sample(5, 5 * STEP, 0, frame(12, false, true, true, false, true), true, true);
        assertEquals(MovementGroundProbe.Result.UNSAFE_TERRAIN, probe.snapshot().lastResult());
        probe.sample(6, 6 * STEP, 0, frame(13, false, true, true, false, true), true, true);
        assertEquals(MovementGroundProbe.Result.UNKNOWN_PHYSICS, probe.snapshot().lastResult());
        assertEquals(0, probe.snapshot().evaluated());
        assertEquals(0, probe.snapshot().researchOutliers());
    }

    @Test
    void oldCodeSnapshotsUnknownPhysicsStayNoDecision() {
        var probe = new MovementGroundProbe();
        var old = new MovementTickTimeline.TickInput(
                "world", new MovementPacketBuffer.Position(0, 64, 0),
                new MovementPacketBuffer.Velocity(0, 0, 0),
                new MovementTickTimeline.PlayerFlags(true, false, false, false, false, false),
                new MovementTickTimeline.Environment(true, false, true, false, false));
        probe.sample(1, STEP, 0, old, true, true);
        probe.sample(2, 2 * STEP, 0, old, true, true);
        assertEquals(MovementGroundProbe.Result.UNSAFE_TERRAIN, probe.snapshot().lastResult());
        assertEquals(0, probe.snapshot().evaluated());
    }

    @Test
    void correctionEpochPendingTeleportOrMissingClientAlwaysDisqualifies() {
        var probe = new MovementGroundProbe();
        probe.sample(1, STEP, 0, frame(0), true, true);
        probe.sample(2, 2 * STEP, 0, frame(3), false, true);
        assertEquals(MovementGroundProbe.Result.CORRECTION, probe.snapshot().lastResult());

        probe.sample(3, 3 * STEP, 1, frame(10), true, true);
        assertEquals(MovementGroundProbe.Result.CORRECTION, probe.snapshot().lastResult());

        probe.sample(4, 4 * STEP, 1, frame(13), true, false);
        assertEquals(MovementGroundProbe.Result.NO_CLIENT_DATA, probe.snapshot().lastResult());

        probe.sample(5, 5 * STEP, 1, frame(16), false, true);
        assertEquals(MovementGroundProbe.Result.CORRECTION, probe.snapshot().lastResult());
        assertEquals(0, probe.snapshot().evaluated());
        assertEquals(0, probe.snapshot().researchOutliers());
    }

    @Test
    void slowTicksAndMissingServerSamplesAreNeverEvaluated() {
        var probe = new MovementGroundProbe();
        probe.sample(1, STEP, 0, frame(0), true, true);
        probe.sample(3, 2 * STEP, 0, frame(2), true, true);
        assertEquals(MovementGroundProbe.Result.TICK_GAP, probe.snapshot().lastResult());
        probe.sample(4, 2 * STEP + 200_000_000L, 0, frame(10), true, true);
        assertEquals(MovementGroundProbe.Result.UNSTABLE_TICK, probe.snapshot().lastResult());
        assertEquals(0, probe.snapshot().evaluated());
    }

    @Test
    void verticalMotionAndExternallyBoostedVelocityAreExcluded() {
        var probe = new MovementGroundProbe();
        probe.sample(1, STEP, 0, frame(0), true, true);
        var raised = new MovementTickTimeline.TickInput(
                "world", new MovementPacketBuffer.Position(3, 65, 10),
                new MovementPacketBuffer.Velocity(0.1, -0.1, 0),
                frame(0).flags(), frame(0).environment(), frame(0).probeContext());
        probe.sample(2, 2 * STEP, 0, raised, true, true);
        assertEquals(MovementGroundProbe.Result.SPECIAL_MOVEMENT, probe.snapshot().lastResult());
        assertEquals(0, probe.snapshot().evaluated());
    }

    @Test
    void unsafeTerrainRetainsSpecificReasonEvenIfLaterClientDataIsMissing() {
        var probe = new MovementGroundProbe();
        var safe = frame(0);
        var path = new MovementTickTimeline.TickInput("world",
                new MovementPacketBuffer.Position(0.3, 64.9375, 10),
                new MovementPacketBuffer.Velocity(0.1, 0, 0),
                frame(0).flags(),
                new MovementTickTimeline.Environment(false, false, true, false, false),
                new MovementTickTimeline.ProbeContext(true, false, true, true,
                        "feet=DIRT_PATH"));
        probe.sample(1, STEP, 0, safe, true, true);
        probe.sample(2, 2 * STEP, 0, path, true, true);
        assertEquals(MovementGroundProbe.Result.UNSAFE_TERRAIN, probe.snapshot().lastResult());
        assertEquals("feet=DIRT_PATH", probe.snapshot().lastSurfaceReason());
        assertEquals(1, probe.snapshot().unsafeTerrainSamples());

        probe.sample(3, 3 * STEP, 0, path, true, false);
        assertEquals(MovementGroundProbe.Result.NO_CLIENT_DATA, probe.snapshot().lastResult());
        assertEquals(1, probe.snapshot().noClientDataSamples());
        assertEquals("feet=DIRT_PATH", probe.snapshot().lastSurfaceReason(),
                "We need the last known ground exclusion, even if an idle client has no fresh packet");
        assertEquals(0, probe.snapshot().evaluated());
    }

    @Test
    void eligibleSamplesRecoverOnOrdinaryGroundAfterUnsafeTerrain() {
        var probe = new MovementGroundProbe();
        var path = new MovementTickTimeline.TickInput("world",
                new MovementPacketBuffer.Position(0, 64.9375, 10),
                new MovementPacketBuffer.Velocity(0.1, 0, 0),
                frame(0).flags(),
                frame(0).environment(),
                new MovementTickTimeline.ProbeContext(true, false, true, true,
                        "feetY-offset=0.938"));
        probe.sample(1, STEP, 0, path, true, true);
        probe.sample(2, 2 * STEP, 0, frame(0.2), true, true);
        assertEquals(MovementGroundProbe.Result.UNSAFE_TERRAIN, probe.snapshot().lastResult());
        probe.sample(3, 3 * STEP, 0, frame(0.5), true, true);
        assertEquals(MovementGroundProbe.Result.WITHIN_RESEARCH_ENVELOPE,
                probe.snapshot().lastResult());
        assertEquals(1, probe.snapshot().evaluated());
        assertEquals(1, probe.snapshot().unsafeTerrainSamples());
    }

    @Test
    void staleSnapshotNeverChangesCounters() {
        var probe = new MovementGroundProbe();
        probe.sample(1, STEP, 0, frame(0), true, true);
        var before = probe.snapshot();
        for (int i = 0; i < 100; i++) assertEquals(before, probe.snapshot());
    }
}
