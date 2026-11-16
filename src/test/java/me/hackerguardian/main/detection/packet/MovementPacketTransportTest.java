package me.hackerguardian.main.detection.packet;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementPacketTransportTest {

    @Test
    void matchesTeleportIdsAndCountsMovementObservedBeforeConfirmation() {
        AtomicLong now = new AtomicLong(1_000_000L);
        var transport = new MovementPacketTransport(now::get);
        transport.movement();
        transport.outboundTeleport(42);
        assertTrue(transport.snapshot().hasUnverifiedTeleport());
        transport.movement();
        transport.movement();
        now.addAndGet(75_000_000L);
        transport.inboundConfirm(42);

        var snapshot = transport.snapshot();
        assertFalse(snapshot.hasUnverifiedTeleport());
        assertEquals(1, snapshot.sentTeleports());
        assertEquals(1, snapshot.receivedConfirms());
        assertEquals(1, snapshot.matchedConfirms());
        assertEquals(0, snapshot.unmatchedConfirms());
        assertEquals(42, snapshot.lastMatchedId());
        assertEquals(2, snapshot.lastMatchMovementCount());
        assertEquals(75_000_000L, snapshot.lastMatchDelayNanos());
        assertEquals(2, snapshot.movementWhilePending());
        assertEquals(List.of(MovementPacketTransport.Result.NEW_PENDING,
                MovementPacketTransport.Result.MATCHED),
                snapshot.recentEvents().stream()
                        .filter(event -> event.teleportId() != null)
                        .map(MovementPacketTransport.Event::result).toList());
        assertTrue(snapshot.recentEvents().get(0).order() <
                snapshot.recentEvents().get(1).order());
    }

    @Test
    void mismatchedAndUnreadableConfirmationsCannotResolvePendingTeleport() {
        var transport = new MovementPacketTransport();
        transport.outboundTeleport(100);
        transport.inboundConfirm(99);
        transport.inboundConfirm(null);
        assertEquals(1, transport.snapshot().pendingTeleports());
        assertEquals(0, transport.snapshot().matchedConfirms());
        assertEquals(1, transport.snapshot().unmatchedConfirms());
        assertEquals(1, transport.snapshot().unreadableIds());
        transport.inboundConfirm(100);
        assertEquals(0, transport.snapshot().pendingTeleports());
        assertEquals(1, transport.snapshot().matchedConfirms());
        transport.inboundConfirm(100);
        assertEquals(2, transport.snapshot().unmatchedConfirms());
    }

    @Test
    void resendReusesIdAndDoesNotForgetEarlierMovement() {
        AtomicLong now = new AtomicLong(2);
        var transport = new MovementPacketTransport(now::get);
        transport.outboundTeleport(12);
        transport.movement();
        now.addAndGet(10_000_000L);
        transport.outboundTeleport(12);
        transport.movement();
        transport.inboundConfirm(12);
        assertEquals(2, transport.snapshot().sentTeleports());
        assertEquals(1, transport.snapshot().resends());
        assertEquals(1, transport.snapshot().matchedConfirms());
        assertEquals(2, transport.snapshot().lastMatchMovementCount());
    }

    @Test
    void pendingIdsExpireAndOutstandingCountStaysBounded() {
        AtomicLong now = new AtomicLong(100);
        var transport = new MovementPacketTransport(now::get);
        for (int id = 1; id <= 33; id++) transport.outboundTeleport(id);
        assertEquals(32, transport.snapshot().pendingTeleports());
        assertEquals(1, transport.snapshot().expiredOrEvicted());

        transport.inboundConfirm(1);
        assertEquals(1, transport.snapshot().unmatchedConfirms());
        now.addAndGet(31_000_000_000L);
        assertEquals(0, transport.snapshot().pendingTeleports());
        assertEquals(33, transport.snapshot().expiredOrEvicted());
        transport.inboundConfirm(33);
        assertEquals(2, transport.snapshot().unmatchedConfirms());
    }

    @Test
    void serverIntentAndOutgoingVelocityHaveNoAutomaticConfirmation() {
        var transport = new MovementPacketTransport();
        var velocity = new MovementPacketBuffer.Velocity(0.5, 0.1, -0.3);
        transport.serverTeleportIntent();
        transport.serverVelocityIntent(velocity);
        transport.outboundSelfVelocity(velocity);
        transport.outboundSelfVelocity(null);

        var snapshot = transport.snapshot();
        assertEquals(2, snapshot.outboundSelfVelocities());
        assertEquals(1, snapshot.unknownVelocityPayloads());
        assertEquals(0, snapshot.matchedConfirms());
        assertEquals(0, snapshot.pendingTeleports());
        assertEquals(List.of(
                MovementPacketTransport.EventKind.BUKKIT_TELEPORT_INTENT,
                MovementPacketTransport.EventKind.BUKKIT_VELOCITY_INTENT,
                MovementPacketTransport.EventKind.SELF_VELOCITY_OUTBOUND_OBSERVED,
                MovementPacketTransport.EventKind.SELF_VELOCITY_OUTBOUND_OBSERVED),
                snapshot.recentEvents().stream()
                        .map(MovementPacketTransport.Event::kind).toList());
    }

    @Test
    void bukkitVelocityIntentAloneDoesNotImplyOutgoingPacketWasObserved() {
        var transport = new MovementPacketTransport();
        transport.serverVelocityIntent(new MovementPacketBuffer.Velocity(0.4, 0.1, 0));
        var snapshot = transport.snapshot();
        assertEquals(0, snapshot.velocityCallbacks());
        assertEquals(0, snapshot.outboundSelfVelocities());
        assertEquals(0, snapshot.velocityEntityIdUnreadable());
    }

    @Test
    void distinguishesRawCallbacksFromSelfOtherAndUnreadableEntityIds() {
        var transport = new MovementPacketTransport();
        transport.observedOutboundVelocity(31, 31, new MovementPacketBuffer.Velocity(0.5, 0.2, 0));
        transport.observedOutboundVelocity(15, 31, null);
        transport.observedOutboundVelocity(null, 31, null);
        transport.observedOutboundVelocity(31, 31, null);

        var snapshot = transport.snapshot();
        assertEquals(4, snapshot.velocityCallbacks());
        assertEquals(2, snapshot.outboundSelfVelocities());
        assertEquals(1, snapshot.velocityOtherEntity());
        assertEquals(1, snapshot.velocityEntityIdUnreadable());
        assertEquals(1, snapshot.unknownVelocityPayloads());
        assertEquals(31, snapshot.lastVelocityPacketEntityId());
        assertEquals(31, snapshot.expectedSelfEntityId());
        assertEquals(0, snapshot.receivedConfirms());
    }

    @Test
    void transportHistoryIsBoundedAndSnapshotCannotBeMutated() {
        var transport = new MovementPacketTransport();
        for (int i = 0; i < 90; i++) transport.outboundTeleport(i);
        var snapshot = transport.snapshot();
        assertEquals(64, snapshot.recentEvents().size());
        assertEquals(26, snapshot.eventsDropped());
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> snapshot.recentEvents().clear());
        assertEquals(32, snapshot.pendingTeleports());
    }

    @Test
    void concurrentCallbacksKeepTotalCountersAndEventOrder() throws Exception {
        var transport = new MovementPacketTransport();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 4; worker++) {
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < 250; i++) transport.movement();
                }));
            }
            for (var future : futures) future.get();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1000L, transport.snapshot().movementPackets());
        assertEquals(0, transport.snapshot().movementWhilePending());
    }
}
