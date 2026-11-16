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
