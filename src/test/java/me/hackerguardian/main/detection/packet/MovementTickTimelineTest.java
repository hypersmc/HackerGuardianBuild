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
