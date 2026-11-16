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
    void recordsSeparateWalkingAndSprintingDistributionsWithoutTransitionContamination() {
        var probe = new MovementGroundProbe();
        probe.sample(1, STEP, 0, sprintFrame(0, false), true, true);
        probe.sample(2, 2 * STEP, 0, sprintFrame(0.20, false), true, true);
        probe.sample(3, 3 * STEP, 0, sprintFrame(0.45, false), true, true);
        // Transition tick must not land in either distribution.
        probe.sample(4, 4 * STEP, 0, sprintFrame(0.80, true), true, true);
        probe.sample(5, 5 * STEP, 0, sprintFrame(1.15, true), true, true);
        probe.sample(6, 6 * STEP, 0, sprintFrame(1.55, true), true, true);
        var snapshot = probe.snapshot();
        assertEquals(4, snapshot.evaluated());
        assertEquals(1, snapshot.sprintTransitions());
        assertEquals(2, snapshot.noDecision());
        assertEquals(0, snapshot.researchOutliers());
        assertEquals(2, snapshot.distributions().walking().totalSamples());
        assertEquals(2, snapshot.distributions().sprinting().totalSamples());
        assertEquals(.20, snapshot.distributions().walking().p50(), 1e-10);
        assertEquals(.25, snapshot.distributions().walking().p95(), 1e-10);
        assertEquals(.35, snapshot.distributions().sprinting().p50(), 1e-10);
        assertEquals(.40, snapshot.distributions().sprinting().max(), 1e-10);
    }

    @Test
    void correctionAndMissingClientNeverEnterDescriptiveDistribution() {
        var probe = new MovementGroundProbe();
        probe.sample(1, STEP, 0, sprintFrame(0, false), true, true);
        probe.sample(2, 2 * STEP, 0, sprintFrame(.2, false), true, true);
        probe.sample(3, 3 * STEP, 0, sprintFrame(5, false), false, true);
        probe.sample(4, 4 * STEP, 1, sprintFrame(10, false), true, true);
        probe.sample(5, 5 * STEP, 1, sprintFrame(20, false), true, false);
        var snapshot = probe.snapshot();
        assertEquals(1, snapshot.distributions().walking().totalSamples());
        assertEquals(0, snapshot.distributions().sprinting().totalSamples());
        assertEquals(1, snapshot.evaluated());
        assertEquals(4, snapshot.noDecision());
    }

    @Test
    void normalizesEligibleSamplesToFiftyMillisecondsOfServerObservation() {
        var probe = new MovementGroundProbe();
        probe.sample(1, STEP, 0, sprintFrame(0, false), true, true);
        probe.sample(2, STEP + 100_000_000L, 0, sprintFrame(.4, false), true, true);
        var result = probe.snapshot();
        assertEquals(1, result.distributions().walking().retained());
        assertEquals(.2, result.distributions().walking().p50(), 1e-10);
        assertEquals(.4, result.horizontalDistance(), 1e-10);
    }

    private static MovementTickTimeline.TickInput sprintFrame(double x, boolean sprinting) {
        var f = frame(x);
        return new MovementTickTimeline.TickInput(f.worldKey(), f.position(),
                f.velocity(), f.flags(), f.environment(),
                new MovementTickTimeline.ProbeContext(true, true, true, true, "", sprinting));
    }

    @Test
    void realProbePassesOnlyContiguousSameSurfaceMotionToDynamics() {
        var probe = new MovementGroundProbe();
        probe.sample(1, STEP, 0, materialFrame(0, "STONE"), true, true);
        probe.sample(2, 2 * STEP, 0, materialFrame(.2, "STONE"), true, true);
        probe.sample(3, 3 * STEP, 0, materialFrame(.4, "STONE"), true, true);
        probe.sample(4, 4 * STEP, 0, materialFrame(.8, "STONE"), true, true);
        var first = probe.snapshot().dynamics();
        assertEquals(3, first.eligibleVectors());
        assertEquals(2, first.comparedTransitions());
        assertEquals(1, first.accelerations());
        assertEquals(1, first.steady());

        probe.sample(5, 5 * STEP, 0, materialFrame(1, "STONE"), true, false);
        assertEquals(1, probe.snapshot().dynamics().discontinuities());

        probe.sample(6, 6 * STEP, 0, materialFrame(1.2, "STONE"), true, true);
        assertEquals(2, probe.snapshot().dynamics().comparedTransitions(),
                "Missing client must sever the previous-vector chain");

        // Two individually safe ordinary blocks, but not a uniform surface.
        probe.sample(7, 7 * STEP, 0, materialFrame(1.4, "GRASS_BLOCK"), true, true);
        assertEquals(2, probe.snapshot().dynamics().comparedTransitions());
        probe.sample(8, 8 * STEP, 0, materialFrame(1.6, "GRASS_BLOCK"), true, true);
        probe.sample(9, 9 * STEP, 0, materialFrame(1.8, "GRASS_BLOCK"), true, true);
        assertEquals(3, probe.snapshot().dynamics().comparedTransitions());
        assertEquals(0, probe.snapshot().researchOutliers());
    }

    private static MovementTickTimeline.TickInput materialFrame(double x, String material) {
        var frame = frame(x);
        return new MovementTickTimeline.TickInput(
                frame.worldKey(), frame.position(), frame.velocity(), frame.flags(),
                frame.environment(),
                new MovementTickTimeline.ProbeContext(true, true, true, true,
                        "", false, material));
    }

    @Test
    void staleSnapshotNeverChangesCounters() {
        var probe = new MovementGroundProbe();
        probe.sample(1, STEP, 0, frame(0), true, true);
        var before = probe.snapshot();
        for (int i = 0; i < 100; i++) assertEquals(before, probe.snapshot());
    }
}
