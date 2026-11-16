package me.hackerguardian.main.detection.packet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GroundIdleTransitionsTest {
    private static final long STEP = 50_000_000L;

    private static MovementTickTimeline.TickInput sample(double x) {
        return sample(x, "STONE", false, false);
    }

    private static MovementTickTimeline.TickInput sample(double x, String surface,
                                                          boolean creative, boolean sprint) {
        return new MovementTickTimeline.TickInput("world",
                new MovementPacketBuffer.Position(x, 64, 10),
                new MovementPacketBuffer.Velocity(0, 0, 0),
                new MovementTickTimeline.PlayerFlags(!creative, creative, false, false,
                        false, creative),
                new MovementTickTimeline.Environment(true, false, true, false, false),
                new MovementTickTimeline.ProbeContext(true, true, true, true,
                        "", sprint, surface));
    }

    private static void tick(GroundIdleTransitions id, int tick, long epoch,
                             MovementTickTimeline.TickInput frame, boolean settled,
                             boolean recentPacket) {
        id.sample(tick, tick * STEP, epoch, frame, settled, recentPacket);
    }

    @Test
    void serverStopCanBeObservedWithoutARecentClientPosition() {
        var id = new GroundIdleTransitions();
        tick(id, 1, 0, sample(0), true, false);
        tick(id, 2, 0, sample(0), true, false);
        tick(id, 3, 0, sample(0), true, false); // confirmed server idle
        assertEquals(GroundIdleTransitions.State.IDLE, id.snapshot().state());

        tick(id, 4, 0, sample(.20), true, true);
        tick(id, 5, 0, sample(.40), true, true); // confirmed server resume
        assertEquals(1, id.snapshot().serverStarts());
        assertEquals(1, id.snapshot().startsWithRecentClient());
        assertEquals(GroundIdleTransitions.State.MOVING, id.snapshot().state());

        tick(id, 6, 0, sample(.40), true, false);
        tick(id, 7, 0, sample(.40), true, false); // no packet, but server idle
        var snap = id.snapshot();
        assertEquals(1, snap.serverStops());
        assertEquals(1, snap.stopsWithoutRecentClient());
        assertEquals(GroundIdleTransitions.State.IDLE, snap.state());
        assertEquals(6, snap.eligibleIntervals());
        assertEquals(0.0, snap.lastBlocksPer50ms(), 1e-10);
        assertEquals(snap, id.snapshot(), "Viewing stats must not change counters");
    }

    @Test
    void teleportAndKnockbackRecoveryCanNeverBridgeStartStopObservations() {
        var id = new GroundIdleTransitions();
        tick(id, 1, 0, sample(0), true, true);
        tick(id, 2, 0, sample(0), true, true);
        tick(id, 3, 0, sample(0), true, true);
        tick(id, 4, 1, sample(12), false, false); // teleport correction epoch
        tick(id, 5, 1, sample(12), false, false);
        tick(id, 6, 1, sample(12), true, false);
        tick(id, 7, 1, sample(12), true, false);
        tick(id, 8, 1, sample(12), true, false);
        assertEquals(0, id.snapshot().serverStarts());
        assertEquals(0, id.snapshot().serverStops());
        assertEquals(GroundIdleTransitions.State.IDLE, id.snapshot().state());
        assertTrue(id.snapshot().interruptedChains() >= 1);

        tick(id, 9, 1, sample(12), false, false); // knockback boundary
        tick(id, 10, 2, sample(20), false, false);
        assertEquals(GroundIdleTransitions.State.UNKNOWN, id.snapshot().state());
        assertEquals(0, id.snapshot().serverStarts());
    }

    @Test
    void creativeFlightAndMaterialChangesAreHardBoundaries() {
        var id = new GroundIdleTransitions();
        tick(id, 1, 0, sample(0), true, false);
        tick(id, 2, 0, sample(0), true, false);
        tick(id, 3, 0, sample(0), true, false);
        tick(id, 4, 0, sample(1, "STONE", true, false), true, false);
        assertEquals(GroundIdleTransitions.State.UNKNOWN, id.snapshot().state());
        assertEquals(GroundIdleTransitions.Status.UNKNOWN_CONTEXT, id.snapshot().lastStatus());

        tick(id, 5, 0, sample(2), true, true);
        tick(id, 6, 0, sample(2.2), true, true);
        tick(id, 7, 0, sample(2.4), true, true); // classify MOVING, no false start
        assertEquals(0, id.snapshot().serverStarts());
        assertEquals(GroundIdleTransitions.State.MOVING, id.snapshot().state());

        tick(id, 8, 0, sample(2.6, "GRASS_BLOCK", false, false), true, true);
        assertEquals(GroundIdleTransitions.State.UNKNOWN, id.snapshot().state());
        assertEquals(GroundIdleTransitions.Status.DISCONTINUITY, id.snapshot().lastStatus());
        assertEquals(0, id.snapshot().serverStops());
    }

    @Test
    void sprintFlagChangeIsNotMisclassifiedAsStartOrStop() {
        var id = new GroundIdleTransitions();
        tick(id, 1, 0, sample(0), true, false);
        tick(id, 2, 0, sample(0), true, false);
        tick(id, 3, 0, sample(0), true, false);
        tick(id, 4, 0, sample(.3, "STONE", false, true), true, true);
        tick(id, 5, 0, sample(.6, "STONE", false, true), true, true);
        tick(id, 6, 0, sample(.9, "STONE", false, true), true, true);
        assertEquals(0, id.snapshot().serverStarts());
        assertEquals(0, id.snapshot().serverStops());
    }

    @Test
    void slowTickAndSkippedSchedulerTickBreakContinuity() {
        var id = new GroundIdleTransitions();
        tick(id, 1, 0, sample(0), true, true);
        tick(id, 2, 0, sample(.2), true, true);
        id.sample(3, 3 * STEP + 300_000_000L, 0, sample(.4), true, true);
        assertEquals(GroundIdleTransitions.Status.UNSTABLE_TICK, id.snapshot().lastStatus());
        tick(id, 6, 0, sample(10), true, true);
        assertEquals(GroundIdleTransitions.Status.WARMUP, id.snapshot().lastStatus());
        tick(id, 7, 0, sample(10.2), true, true);
        tick(id, 9, 0, sample(10.4), true, true);
        assertEquals(GroundIdleTransitions.Status.DISCONTINUITY, id.snapshot().lastStatus());
        assertEquals(0, id.snapshot().serverStarts());
    }
}
