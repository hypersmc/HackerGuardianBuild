package me.hackerguardian.main.detection.packet;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementPacketBufferTest {

    @Test
    void ordersAndBoundsEachPlayerHistory() {
        AtomicLong now = new AtomicLong(100);
        MovementPacketBuffer buffer = new MovementPacketBuffer(3, now::incrementAndGet);
        UUID player = UUID.randomUUID();

        buffer.record(player, MovementPacketBuffer.Kind.GROUND);
        buffer.record(player, MovementPacketBuffer.Kind.POSITION);
        buffer.record(player, MovementPacketBuffer.Kind.LOOK);
        buffer.record(player, MovementPacketBuffer.Kind.POSITION_LOOK);

        MovementPacketBuffer.Snapshot snapshot = buffer.snapshot(player);
        assertEquals(4, snapshot.totalReceived());
        assertEquals(1, snapshot.dropped());
        assertEquals(3, snapshot.retained());
        assertEquals(List.of(2L, 3L, 4L),
                snapshot.observations().stream().map(MovementPacketBuffer.Observation::sequence).toList());
        assertEquals(List.of(
                MovementPacketBuffer.Kind.POSITION,
                MovementPacketBuffer.Kind.LOOK,
                MovementPacketBuffer.Kind.POSITION_LOOK
        ), snapshot.observations().stream().map(MovementPacketBuffer.Observation::kind).toList());
        assertTrue(snapshot.observations().get(0).arrivalNanos()
                < snapshot.observations().get(2).arrivalNanos());
    }

    @Test
    void isolatesPlayersAndClearsOnDisconnect() {
        MovementPacketBuffer buffer = new MovementPacketBuffer(2);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        buffer.record(first, MovementPacketBuffer.Kind.GROUND);
        buffer.record(second, MovementPacketBuffer.Kind.POSITION);
        assertEquals(1, buffer.snapshot(first).totalReceived());
        assertEquals(1, buffer.snapshot(second).totalReceived());

        buffer.clearPlayer(first);
        assertEquals(0, buffer.snapshot(first).totalReceived());
        assertEquals(1, buffer.snapshot(second).totalReceived());
    }

    @Test
    void clockRegressionCannotMoveRecordedTimeBackwards() {
        AtomicLong clock = new AtomicLong(1000);
        MovementPacketBuffer buffer = new MovementPacketBuffer(2, clock::get);
        UUID player = UUID.randomUUID();
        buffer.record(player, MovementPacketBuffer.Kind.GROUND);
        clock.set(100);
        buffer.record(player, MovementPacketBuffer.Kind.GROUND);

        List<MovementPacketBuffer.Observation> events = buffer.snapshot(player).observations();
        assertEquals(events.get(0).arrivalNanos(), events.get(1).arrivalNanos());
        assertEquals(2L, events.get(1).sequence());
    }

    @Test
    void recentFiveSecondBreakdownUsesMonotonicReceiveTimes() {
        AtomicLong now = new AtomicLong(0);
        MovementPacketBuffer buffer = new MovementPacketBuffer(8, now::get);
        UUID player = UUID.randomUUID();

        now.set(1_000_000_000L);
        buffer.record(player, MovementPacketBuffer.Kind.GROUND);
        now.set(2_000_000_000L);
        buffer.record(player, MovementPacketBuffer.Kind.POSITION);
        now.set(6_000_000_000L);
        buffer.record(player, MovementPacketBuffer.Kind.LOOK);
        now.set(7_000_000_000L);
        buffer.record(player, MovementPacketBuffer.Kind.POSITION_LOOK);

        MovementPacketBuffer.WindowStats stats =
                buffer.snapshot(player).recentWindow(7_000_000_000L, 5_000_000_000L);
        assertEquals(3, stats.count());
        assertEquals(0, stats.ground());
        assertEquals(1, stats.position());
        assertEquals(1, stats.look());
        assertEquals(1, stats.positionLook());
        assertEquals(0.6, stats.ratePerSecond(), 1e-12);
        assertEquals(false, stats.truncated());
    }

    @Test
    void truncatedWindowIsVisibleButOlderDropsDoNotFalselyWarn() {
        AtomicLong now = new AtomicLong(0);
        MovementPacketBuffer buffer = new MovementPacketBuffer(2, now::get);
        UUID player = UUID.randomUUID();
        now.set(1_000_000_000L);
        buffer.record(player, MovementPacketBuffer.Kind.GROUND);
        now.set(2_000_000_000L);
        buffer.record(player, MovementPacketBuffer.Kind.POSITION);
        now.set(3_000_000_000L);
        buffer.record(player, MovementPacketBuffer.Kind.LOOK);

        MovementPacketBuffer.Snapshot snapshot = buffer.snapshot(player);
        assertEquals(1, snapshot.dropped());
        MovementPacketBuffer.WindowStats partial =
                snapshot.recentWindow(3_000_000_000L, 5_000_000_000L);
        assertEquals(2, partial.count());
        assertTrue(partial.truncated());

        MovementPacketBuffer.WindowStats complete =
                snapshot.recentWindow(10_000_000_000L, 5_000_000_000L);
        assertEquals(0, complete.count());
        assertEquals(false, complete.truncated());
    }

    @Test
    void lastKnownRawFieldsPreservePacketProvenanceAndDecodeFailures() {
        AtomicLong now = new AtomicLong(1);
        MovementPacketBuffer buffer = new MovementPacketBuffer(8, now::incrementAndGet);
        UUID id = UUID.randomUUID();
        buffer.record(id, MovementPacketBuffer.Kind.POSITION,
                new MovementPacketBuffer.PacketState(
                        new MovementPacketBuffer.Position(1, 70, -3),
                        null, false, null, true));
        buffer.record(id, MovementPacketBuffer.Kind.LOOK,
                new MovementPacketBuffer.PacketState(null,
                        new MovementPacketBuffer.Rotation(190, 45),
                        true, false, true));
        buffer.record(id, MovementPacketBuffer.Kind.POSITION_LOOK,
                MovementPacketBuffer.PacketState.missing());

        var snapshot = buffer.snapshot(id);
        assertEquals(3, snapshot.totalReceived());
        assertEquals(1, snapshot.decodeFailures());
        var known = snapshot.lastKnownClientFields();
        assertEquals(new MovementPacketBuffer.Position(1, 70, -3), known.position());
        assertEquals(1L, known.positionSequence());
        assertEquals(new MovementPacketBuffer.Rotation(190, 45), known.rotation());
        assertEquals(2L, known.rotationSequence());
        assertEquals(true, known.onGround());
        assertEquals(false, known.horizontalCollision());
        assertEquals(2L, known.groundSequence());
    }

    @Test
    void teleportAndVelocityContextAreSeparateFromInboundPacketCount() {
        AtomicLong now = new AtomicLong(10);
        MovementPacketBuffer buffer = new MovementPacketBuffer(8, now::incrementAndGet);
        UUID id = UUID.randomUUID();
        buffer.record(id, MovementPacketBuffer.Kind.GROUND);
        buffer.recordServerTeleport(id, "world-uuid",
                new MovementPacketBuffer.Position(4, 80, 5),
                new MovementPacketBuffer.Rotation(0, 0), "PLUGIN");
        buffer.recordServerVelocity(id, "world-uuid",
                new MovementPacketBuffer.Velocity(0.5, 0.25, -0.5));

        var snap = buffer.snapshot(id);
        assertEquals(1L, snap.totalReceived());
        assertEquals(2L, snap.totalContexts());
        assertEquals(0L, snap.droppedContexts());
        assertEquals(2, snap.contexts().size());
        assertEquals(MovementPacketBuffer.ContextKind.SERVER_TELEPORT, snap.contexts().get(0).kind());
        assertEquals("PLUGIN", snap.contexts().get(0).cause());
        assertEquals(new MovementPacketBuffer.Velocity(0.5, 0.25, -0.5),
                snap.contexts().get(1).velocity());
        assertTrue(snap.contexts().get(0).arrivalNanos() < snap.contexts().get(1).arrivalNanos());
    }

    @Test
    void serverContextIsBoundedAndClearedWithPlayer() {
        MovementPacketBuffer buffer = new MovementPacketBuffer(1);
        UUID id = UUID.randomUUID();
        for (int i = 0; i < 20; i++) {
            buffer.recordServerVelocity(id, "world",
                    new MovementPacketBuffer.Velocity(i, 0, 0));
        }
        var snapshot = buffer.snapshot(id);
        assertEquals(20, snapshot.totalContexts());
        assertEquals(8, snapshot.contexts().size());
        assertEquals(12, snapshot.droppedContexts());
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> snapshot.contexts().clear());
        buffer.clearPlayer(id);
        assertEquals(0, buffer.snapshot(id).totalContexts());
    }

    @Test
    void rejectsNonFiniteContextDataAndDoesNotConflateTypes() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new MovementPacketBuffer.Position(Double.NaN, 0, 0));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new MovementPacketBuffer.Rotation(0, Float.NEGATIVE_INFINITY));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new MovementPacketBuffer.Velocity(0, 0, Double.POSITIVE_INFINITY));
    }

    @Test
    void concurrentProducersPreserveStrictSequence() throws Exception {
        MovementPacketBuffer buffer = new MovementPacketBuffer(32);
        UUID player = UUID.randomUUID();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            List<java.util.concurrent.Future<?>> jobs = new java.util.ArrayList<>();
            for (int thread = 0; thread < 4; thread++) {
                jobs.add(pool.submit(() -> {
                    for (int i = 0; i < 300; i++) {
                        buffer.record(player, MovementPacketBuffer.Kind.POSITION);
                    }
                }));
            }
            for (java.util.concurrent.Future<?> job : jobs) job.get();
        } finally {
            pool.shutdownNow();
        }
        MovementPacketBuffer.Snapshot snapshot = buffer.snapshot(player);
        assertEquals(1200, snapshot.totalReceived());
        assertEquals(1168, snapshot.dropped());
        assertEquals(32, snapshot.retained());
        long expected = 1169;
        for (MovementPacketBuffer.Observation event : snapshot.observations()) {
            assertEquals(expected++, event.sequence());
        }
    }
}
