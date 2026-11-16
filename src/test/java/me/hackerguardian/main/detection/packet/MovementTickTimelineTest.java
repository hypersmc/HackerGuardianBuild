package me.hackerguardian.main.detection.packet;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class MovementTickTimelineTest {
    private static MovementTickTimeline.TickInput tick(String world, double x) {
        return new MovementTickTimeline.TickInput(
                world,
                new MovementPacketBuffer.Position(x, 64, 10),
                new MovementPacketBuffer.Velocity(0.05, -0.03, 0),
                new MovementTickTimeline.PlayerFlags(
                        true, false, false, false, false, false),
                new MovementTickTimeline.Environment(
                        true, false, true, false, false));
    }

    @Test
    void pairsPacketWithFirstFollowingTickButNeverCallsItCheatEvidence() {
        AtomicLong time = new AtomicLong(1000);
        var timeline = new MovementTickTimeline(time::get);
        assertEquals(MovementTickTimeline.Status.NO_CLIENT_POSITION,
                timeline.snapshot().comparison().status());

        timeline.observeClientPosition(new MovementPacketBuffer.Position(10, 64, 10));
        assertEquals(MovementTickTimeline.Status.NO_SERVER_TICK,
                timeline.snapshot().comparison().status());
        time.addAndGet(25_000_000L);
        timeline.sampleServerTick(1, tick("world-a", 10.1));
        var snapshot = timeline.snapshot();
        assertEquals(MovementTickTimeline.Status.READY_DIAGNOSTIC,
                snapshot.comparison().status());
        assertEquals(1L, snapshot.comparison().followingServerTick());
        assertEquals(25_000_000L, snapshot.comparison().tickLagNanos());
        assertEquals(0.1, snapshot.comparison().positionDifference(), 1e-8);
        assertEquals(0L, snapshot.correctionEpoch());
        assertEquals("world-a", snapshot.latestTick().state().worldKey());
    }

    @Test
    void missingFollowingTickAndSlowTickStayUnknown() {
        AtomicLong time = new AtomicLong(0);
        var timeline = new MovementTickTimeline(time::get);
        timeline.sampleServerTick(1, tick("world-a", 1));
        time.set(100);
        timeline.observeClientPosition(new MovementPacketBuffer.Position(1, 64, 10));
        assertEquals(MovementTickTimeline.Status.AWAITING_NEXT_TICK,
                timeline.snapshot().comparison().status());
        time.set(300_000_000L);
        assertEquals(MovementTickTimeline.Status.TOO_OLD,
                timeline.snapshot().comparison().status());
        timeline.sampleServerTick(2, tick("world-a", 1));
        assertEquals(MovementTickTimeline.Status.TOO_OLD,
                timeline.snapshot().comparison().status());
        assertNull(timeline.snapshot().comparison().positionDifference());
    }

    @Test
    void continuousMovementUsesLatestCompletedPairInsteadOfWaitingEveryTime() {
        AtomicLong time = new AtomicLong(1_000_000_000L);
        var timeline = new MovementTickTimeline(time::get);
        timeline.sampleServerTick(1, tick("world", 0));

        // Client position arrives after server tick 1. No following sample yet.
        time.addAndGet(5_000_000L);
        timeline.observeClientPosition(new MovementPacketBuffer.Position(1, 64, 10));
        assertEquals(MovementTickTimeline.Status.AWAITING_NEXT_TICK,
                timeline.snapshot().comparison().status());

        // Server tick 2 completes the first client/server pair.
        time.addAndGet(40_000_000L);
        timeline.sampleServerTick(2, tick("world", 1.1));
        assertEquals(MovementTickTimeline.Status.READY_DIAGNOSTIC,
                timeline.snapshot().comparison().status());
        assertEquals(1L, timeline.snapshot().comparison().clientOrder());
        assertEquals(2L, timeline.snapshot().comparison().followingServerTick());

        // Player keeps flying: another movement packet arrives just after tick.
        // Old logic reported AWAITING_NEXT_TICK constantly. New logic displays
        // the completed pair, correctly labeled by its older packet order.
        time.addAndGet(5_000_000L);
        timeline.observeClientPosition(new MovementPacketBuffer.Position(2, 64, 10));
        var whileMoving = timeline.snapshot().comparison();
        assertEquals(MovementTickTimeline.Status.READY_DIAGNOSTIC, whileMoving.status());
        assertEquals(1L, whileMoving.clientOrder());
        assertEquals(2L, whileMoving.followingServerTick());

        time.addAndGet(40_000_000L);
        timeline.sampleServerTick(3, tick("world", 2.1));
        var nextPair = timeline.snapshot().comparison();
        assertEquals(MovementTickTimeline.Status.READY_DIAGNOSTIC, nextPair.status());
        assertEquals(2L, nextPair.clientOrder());
        assertEquals(3L, nextPair.followingServerTick());
        assertEquals(0.1, nextPair.positionDifference(), 1e-8);
    }

    @Test
    void completedPairExpiresAndCannotOutliveTeleportOrVelocityBoundary() {
        AtomicLong time = new AtomicLong(1_000_000_000L);
        var timeline = new MovementTickTimeline(time::get);
        timeline.sampleServerTick(1, tick("world", 0));
        time.addAndGet(5_000_000L);
        timeline.observeClientPosition(new MovementPacketBuffer.Position(1, 64, 10));
        time.addAndGet(40_000_000L);
        timeline.sampleServerTick(2, tick("world", 1.1));
        assertEquals(MovementTickTimeline.Status.READY_DIAGNOSTIC,
                timeline.snapshot().comparison().status());

        time.addAndGet(800_000_000L);
        assertEquals(MovementTickTimeline.Status.TOO_OLD,
                timeline.snapshot().comparison().status(),
                "Do not reuse a completed pair as current evidence indefinitely");

        timeline.markCorrection(MovementTickTimeline.Correction.OUTBOUND_SELF_VELOCITY);
        assertEquals(MovementTickTimeline.Status.DIFFERENT_CONTEXT,
                timeline.snapshot().comparison().status(),
                "A velocity boundary invalidates even a previously successful pair");

        time.addAndGet(1_000_000L);
        timeline.observeClientPosition(new MovementPacketBuffer.Position(2, 64, 10));
        assertEquals(MovementTickTimeline.Status.CORRECTION_WINDOW,
                timeline.snapshot().comparison().status());
        assertEquals(MovementTickTimeline.Status.PENDING_TELEPORT,
                timeline.snapshot(true).comparison().status());
        assertNull(timeline.snapshot(true).comparison().positionDifference());
    }

    @Test
    void teleportAndVelocityBoundariesBlockCrossEpochComparisons() {
        AtomicLong time = new AtomicLong(1);
        var timeline = new MovementTickTimeline(time::get);
        timeline.sampleServerTick(1, tick("world", 0));
        time.addAndGet(1);
        timeline.observeClientPosition(new MovementPacketBuffer.Position(0, 64, 10));
        timeline.markCorrection(MovementTickTimeline.Correction.OUTBOUND_TELEPORT);
        timeline.sampleServerTick(2, tick("world", 20));
        assertEquals(MovementTickTimeline.Status.DIFFERENT_CONTEXT,
                timeline.snapshot().comparison().status());

        timeline.observeClientPosition(new MovementPacketBuffer.Position(20, 64, 10));
        timeline.sampleServerTick(3, tick("world", 20));
        assertEquals(MovementTickTimeline.Status.CORRECTION_WINDOW,
                timeline.snapshot().comparison().status());

        timeline.sampleServerTick(4, tick("world", 20));
        timeline.observeClientPosition(new MovementPacketBuffer.Position(21, 64, 10));
        timeline.sampleServerTick(5, tick("world", 21.05));
        assertEquals(MovementTickTimeline.Status.READY_DIAGNOSTIC,
                timeline.snapshot().comparison().status());

        timeline.markCorrection(MovementTickTimeline.Correction.OUTBOUND_SELF_VELOCITY);
        assertEquals(MovementTickTimeline.Status.DIFFERENT_CONTEXT,
                timeline.snapshot().comparison().status());
    }

    @Test
    void pendingTeleportPreventsDiagnosticComparisonEvenWhenTicksContinue() {
        AtomicLong clock = new AtomicLong(1);
        var timeline = new MovementTickTimeline(clock::incrementAndGet);
        timeline.sampleServerTick(1, tick("world", 0));
        timeline.observeClientPosition(new MovementPacketBuffer.Position(0, 64, 10));
        timeline.sampleServerTick(2, tick("world", 0));
        assertEquals(MovementTickTimeline.Status.READY_DIAGNOSTIC,
                timeline.snapshot().comparison().status());
        assertEquals(MovementTickTimeline.Status.PENDING_TELEPORT,
                timeline.snapshot(true).comparison().status());
        assertNull(timeline.snapshot(true).comparison().positionDifference());
        assertEquals(MovementTickTimeline.Status.READY_DIAGNOSTIC,
                timeline.snapshot(false).comparison().status());
    }

    @Test
    void skippedSchedulerTicksDoNotCountAsStableSamplesAfterCorrection() {
        AtomicLong clock = new AtomicLong(1);
        var timeline = new MovementTickTimeline(clock::incrementAndGet);
        timeline.sampleServerTick(1, tick("world", 0));
        timeline.markCorrection(MovementTickTimeline.Correction.BUKKIT_VELOCITY);

        // Server ticks 2..39 have no sample (e.g. unavailable context).
        timeline.observeClientPosition(new MovementPacketBuffer.Position(0, 64, 10));
        timeline.sampleServerTick(40, tick("world", 0));
        assertEquals(MovementTickTimeline.Status.CORRECTION_WINDOW,
                timeline.snapshot().comparison().status());

        timeline.observeClientPosition(new MovementPacketBuffer.Position(0, 64, 10));
        timeline.sampleServerTick(80, tick("world", 0));
        assertEquals(MovementTickTimeline.Status.CORRECTION_WINDOW,
                timeline.snapshot().comparison().status());

        timeline.observeClientPosition(new MovementPacketBuffer.Position(0, 64, 10));
        timeline.sampleServerTick(120, tick("world", 0));
        assertEquals(MovementTickTimeline.Status.READY_DIAGNOSTIC,
                timeline.snapshot().comparison().status());
    }

    @Test
    void recordsBoundaryEventsAndOnlyCountsActualSuppressedSamplePairs() {
        AtomicLong clock = new AtomicLong(1);
        var timeline = new MovementTickTimeline(clock::incrementAndGet);
        timeline.sampleServerTick(1, tick("world", 0));

        timeline.observeClientPosition(new MovementPacketBuffer.Position(0, 64, 10));
        timeline.markCorrection(MovementTickTimeline.Correction.BUKKIT_TELEPORT);
        var initial = timeline.snapshot().guards();
        assertEquals(1, initial.teleportBoundaries());
        assertEquals(1, initial.pendingClaimsInvalidated());
        assertEquals(0, initial.recoverySuppressions(),
                "An invalidated position must not masquerade as a sampled comparison");
        assertEquals(0, initial.completedPairs());

        // Recovery window: actual client packets and following server samples.
        timeline.observeClientPosition(new MovementPacketBuffer.Position(1, 64, 10));
        timeline.sampleServerTick(2, tick("world", 1));
        assertEquals(1, timeline.snapshot().guards().recoverySuppressions());

        // Pending teleport takes priority over the otherwise normal recovery guard.
        timeline.observeClientPosition(new MovementPacketBuffer.Position(2, 64, 10));
        timeline.sampleServerTick(3, tick("world", 2), true);
        var guarded = timeline.snapshot().guards();
        assertEquals(1, guarded.pendingTeleportSuppressions());
        assertEquals(1, guarded.recoverySuppressions());
        assertEquals(MovementTickTimeline.Status.PENDING_TELEPORT, guarded.lastSuppression());
        assertEquals(3, guarded.lastSuppressionTick());
        assertEquals(0, guarded.completedPairs());

        // A client confirmation is an event boundary; no pending comparison
        // means it doesn't by itself increment the suppression count.
        timeline.markCorrection(MovementTickTimeline.Correction.TELEPORT_CONFIRM);
        assertEquals(2, timeline.snapshot().guards().teleportBoundaries());
        timeline.observeClientPosition(new MovementPacketBuffer.Position(3, 64, 10));
        timeline.sampleServerTick(4, tick("world", 3));
        timeline.observeClientPosition(new MovementPacketBuffer.Position(4, 64, 10));
        timeline.sampleServerTick(5, tick("world", 4));
        timeline.observeClientPosition(new MovementPacketBuffer.Position(5, 64, 10));
        timeline.sampleServerTick(6, tick("world", 5));
        var recovered = timeline.snapshot().guards();
        assertEquals(3, recovered.recoverySuppressions());
        assertEquals(1, recovered.pendingTeleportSuppressions());
        assertEquals(1, recovered.completedPairs());

        timeline.markCorrection(MovementTickTimeline.Correction.OUTBOUND_SELF_VELOCITY);
        var velocity = timeline.snapshot().guards();
        assertEquals(1, velocity.velocityBoundaries());
        assertEquals(1, velocity.completedPairsInvalidated());
        assertEquals(3, velocity.recoverySuppressions());
        assertEquals(1, velocity.completedPairs());
        assertEquals(0, velocity.contextSuppressions());
    }

    @Test
    void readingDiagnosticsRepeatedlyNeverCreatesGuardOrCorrectionEvents() {
        AtomicLong clock = new AtomicLong(100);
        var timeline = new MovementTickTimeline(clock::get);
        timeline.sampleServerTick(1, tick("world", 0));
        timeline.markCorrection(MovementTickTimeline.Correction.BUKKIT_VELOCITY);
        timeline.observeClientPosition(new MovementPacketBuffer.Position(1, 64, 10));
        clock.addAndGet(50_000_000L);
        timeline.sampleServerTick(2, tick("world", 1));
        var baseline = timeline.snapshot().guards();

        for (int i = 0; i < 100; i++) {
            clock.addAndGet(1_000_000L);
            assertEquals(baseline, timeline.snapshot().guards());
            assertEquals(baseline, timeline.snapshot(true).guards(),
                    "A pending teleport queried through the UI is not a sampled suppression");
        }

        assertEquals(1, baseline.velocityBoundaries());
        assertEquals(1, baseline.recoverySuppressions());
        assertEquals(0, baseline.pendingTeleportSuppressions());
    }

    @Test
    void worldChangeCountsAsOtherBoundaryAndInvalidatesPendingClaims() {
        AtomicLong clock = new AtomicLong(1);
        var timeline = new MovementTickTimeline(clock::incrementAndGet);
        timeline.sampleServerTick(1, tick("overworld", 0));
        timeline.observeClientPosition(new MovementPacketBuffer.Position(0, 64, 10));
        timeline.sampleServerTick(2, tick("nether", 100));
        var guards = timeline.snapshot().guards();
        assertEquals(1, guards.otherBoundaries());
        assertEquals(1, guards.pendingClaimsInvalidated());
        assertEquals(0, guards.completedPairs());
        assertEquals(0, guards.recoverySuppressions());
    }

    @Test
    void worldSwitchCannotBeComparedEvenWithoutTeleportCallback() {
        AtomicLong time = new AtomicLong(1);
        var timeline = new MovementTickTimeline(time::incrementAndGet);
        timeline.sampleServerTick(1, tick("overworld", 0));
        timeline.observeClientPosition(new MovementPacketBuffer.Position(0, 64, 10));
        timeline.sampleServerTick(2, tick("nether", 0));
        assertEquals(MovementTickTimeline.Correction.WORLD_CHANGE,
                timeline.snapshot().lastCorrection());
        assertEquals(MovementTickTimeline.Status.DIFFERENT_CONTEXT,
                timeline.snapshot().comparison().status());
    }

    @Test
    void historyIsBoundedAndContainsNoMutableBukkitObjects() {
        AtomicLong time = new AtomicLong(0);
        var timeline = new MovementTickTimeline(time::incrementAndGet);
        for (int i = 1; i <= 100; i++) timeline.sampleServerTick(i, tick("world", i));
        var snapshot = timeline.snapshot();
        assertEquals(100L, snapshot.sampledTicks());
        assertEquals(64, snapshot.ticks().size());
        assertEquals(36L, snapshot.droppedTicks());
        assertEquals(37L, snapshot.ticks().get(0).tick());
        assertEquals(100L, snapshot.latestTick().tick());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.ticks().clear());
    }

    @Test
    void refusesDuplicateTickAndInvalidServerSample() {
        var timeline = new MovementTickTimeline();
        timeline.sampleServerTick(1, tick("world", 1));
        assertThrows(IllegalArgumentException.class,
                () -> timeline.sampleServerTick(1, tick("world", 1)));
        assertThrows(IllegalArgumentException.class,
                () -> timeline.sampleServerTick(2, null));
        assertThrows(IllegalArgumentException.class,
                () -> new MovementTickTimeline.TickInput("", new MovementPacketBuffer.Position(1, 2, 3),
                        new MovementPacketBuffer.Velocity(0, 0, 0),
                        new MovementTickTimeline.PlayerFlags(false, false, false, false, false, false),
                        new MovementTickTimeline.Environment(true, true, true, false, false)));
    }

    @Test
    void concurrentPacketAndServerTickSamplesKeepBoundedHistory() throws Exception {
        AtomicLong time = new AtomicLong();
        var timeline = new MovementTickTimeline(time::incrementAndGet);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> {
                for (int i = 1; i <= 200; i++) {
                    timeline.sampleServerTick(i, tick("world", i));
                }
            });
            var two = pool.submit(() -> {
                for (int i = 0; i < 500; i++) {
                    timeline.observeClientPosition(new MovementPacketBuffer.Position(i, 64, 10));
                }
            });
            one.get();
            two.get();
        } finally {
            pool.shutdownNow();
        }
        var snapshot = timeline.snapshot();
        assertEquals(200L, snapshot.sampledTicks());
        assertEquals(500L, snapshot.clientPacketsObserved());
        assertEquals(64, snapshot.ticks().size());
        assertNotNull(snapshot.lastClient());
    }
}
