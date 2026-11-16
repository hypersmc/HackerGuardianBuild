package me.hackerguardian.main.detection.packet;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Per-player receive/send-observation timeline for teleport correlation.
 *
 * A ProtocolLib sending callback only shows the packet being processed for
 * sending; it does NOT prove delivery. A client teleport confirmation means
 * that a matching ID was observed inbound, not that position or velocity
 * physics have been validated. Outbound entity velocity has NO client ACK.
 *
 * This class has no Bukkit or ProtocolLib dependency so transition behavior
 * can be tested with deterministic clocks and controlled event order.
 */
public final class MovementPacketTransport {
    private static final int MAX_PENDING = 32;
    private static final int MAX_EVENTS = 64;
    private static final long PENDING_TTL_NANOS = 30_000_000_000L;

    public enum EventKind {
        MOVEMENT_INBOUND,
        BUKKIT_TELEPORT_INTENT,
        BUKKIT_VELOCITY_INTENT,
        TELEPORT_OUTBOUND_OBSERVED,
        TELEPORT_CONFIRM_INBOUND,
        SELF_VELOCITY_OUTBOUND_OBSERVED
    }

    public enum Result {
        OBSERVED, NEW_PENDING, RESEND, MATCHED, UNMATCHED, UNREADABLE_ID
    }

    public record Event(long order, long observedAtNanos, EventKind kind,
                        Result result, Integer teleportId,
                        MovementPacketBuffer.Velocity velocity,
                        long movementSequence, int pendingTeleportCount) {}

    public record Snapshot(long movementPackets, long movementWhilePending,
                           long sentTeleports, long receivedConfirms,
                           long matchedConfirms, long unmatchedConfirms,
                           long resends, long expiredOrEvicted,
                           long unreadableIds, int pendingTeleports,
                           long outboundSelfVelocities, long unknownVelocityPayloads,
                           Integer lastSentId, Integer lastMatchedId,
                           long lastMatchDelayNanos, long lastMatchMovementCount,
                           long eventsDropped, List<Event> recentEvents,
                           long velocityCallbacks, long velocityOtherEntity,
                           long velocityEntityIdUnreadable,
                           Integer lastVelocityPacketEntityId, int expectedSelfEntityId) {
        public boolean hasUnverifiedTeleport() {
            return pendingTeleports > 0;
        }
    }

    private record Pending(long lastOutboundNanos, long movementSequenceAtFirstSend) {}

    private final LongSupplier clock;
    private final Deque<Event> recent = new ArrayDeque<>();
    private final Map<Integer, Pending> pending = new LinkedHashMap<>();
    private long order, lastNanos = Long.MIN_VALUE;
    private long movementPackets, movementWhilePending;
    private long sentTeleports, receivedConfirms, matchedConfirms, unmatchedConfirms;
    private long resends, expiredOrEvicted, unreadableIds;
    private long outboundSelfVelocities, unknownVelocityPayloads;
    private long velocityCallbacks, velocityOtherEntity, velocityEntityIdUnreadable;
    private Integer lastVelocityPacketEntityId;
    private int expectedSelfEntityId = -1;
    private long eventsDropped, lastMatchDelayNanos = -1, lastMatchMovementCount = -1;
    private Integer lastSentId, lastMatchedId;

    public MovementPacketTransport() {
        this(System::nanoTime);
    }

    MovementPacketTransport(LongSupplier clock) {
        if (clock == null) throw new IllegalArgumentException("clock required");
        this.clock = clock;
    }

    /** Record the next observed inbound movement packet in the same per-player session lock. */
    public synchronized void movement() {
        long now = tick();
        movementPackets++;
        if (!pending.isEmpty()) movementWhilePending++;
        // Movement is already kept in the bounded MovementPacketBuffer.
        // Do not duplicate every packet in the transport event ring.
        order++;
    }

    public synchronized void serverTeleportIntent() {
        append(tick(), EventKind.BUKKIT_TELEPORT_INTENT, Result.OBSERVED, null, null);
    }

    public synchronized void serverVelocityIntent(MovementPacketBuffer.Velocity velocity) {
        append(tick(), EventKind.BUKKIT_VELOCITY_INTENT, Result.OBSERVED, null, velocity);
    }

    /** Observed outbound PLAYER_POSITION packet with its explicit teleport ID. */
    public synchronized void outboundTeleport(Integer id) {
        long now = tick();
        sentTeleports++;
        Result result;
        if (id == null) {
            unreadableIds++;
            result = Result.UNREADABLE_ID;
        } else if (pending.containsKey(id)) {
            resends++;
            // Retain first-send movement sequence, refresh expiration for retry.
            Pending earlier = pending.get(id);
            pending.put(id, new Pending(now, earlier.movementSequenceAtFirstSend));
            result = Result.RESEND;
        } else {
            pending.put(id, new Pending(now, movementPackets));
            lastSentId = id;
            result = Result.NEW_PENDING;
            if (pending.size() > MAX_PENDING) {
                Integer oldest = pending.keySet().iterator().next();
                pending.remove(oldest);
                expiredOrEvicted++;
            }
        }
        append(now, EventKind.TELEPORT_OUTBOUND_OBSERVED, result, id, null);
    }

    /** Client confirmation is associated only with the matching outstanding ID. */
    public synchronized void inboundConfirm(Integer id) {
        long now = tick();
        receivedConfirms++;
        Result result;
        if (id == null) {
            unreadableIds++;
            result = Result.UNREADABLE_ID;
        } else {
            Pending original = pending.remove(id);
            if (original == null) {
                unmatchedConfirms++;
                result = Result.UNMATCHED;
            } else {
                matchedConfirms++;
                lastMatchedId = id;
                // Delay is observation-to-observation, not network RTT.
                lastMatchDelayNanos = Math.max(0, now - original.lastOutboundNanos);
                lastMatchMovementCount = movementPackets - original.movementSequenceAtFirstSend;
                result = Result.MATCHED;
            }
        }
        append(now, EventKind.TELEPORT_CONFIRM_INBOUND, result, id, null);
    }

    /**
     * Records every outbound ENTITY_VELOCITY callback BEFORE filtering on the
     * target ID. Without these totals a zero self counter cannot distinguish
     * listener failure, unknown target ID and another entity being updated.
     * Must not be interpreted as proof of a packet reaching the client.
     */
    public synchronized void observedOutboundVelocity(Integer entityId, int selfEntityId,
                                                       MovementPacketBuffer.Velocity velocity) {
        velocityCallbacks++;
        expectedSelfEntityId = selfEntityId;
        lastVelocityPacketEntityId = entityId;
        if (entityId == null) {
            velocityEntityIdUnreadable++;
        } else if (entityId.intValue() != selfEntityId) {
            velocityOtherEntity++;
        } else {
            outboundSelfVelocity(velocity);
        }
    }

    /** Only invoke for a packet addressed to the player's own entity ID. */
    public synchronized void outboundSelfVelocity(MovementPacketBuffer.Velocity velocity) {
        long now = tick();
        outboundSelfVelocities++;
        if (velocity == null) unknownVelocityPayloads++;
        append(now, EventKind.SELF_VELOCITY_OUTBOUND_OBSERVED,
                Result.OBSERVED, null, velocity);
    }

    public synchronized Snapshot snapshot() {
        tick();
        return new Snapshot(movementPackets, movementWhilePending,
                sentTeleports, receivedConfirms, matchedConfirms, unmatchedConfirms,
                resends, expiredOrEvicted, unreadableIds, pending.size(),
                outboundSelfVelocities, unknownVelocityPayloads, lastSentId,
                lastMatchedId, lastMatchDelayNanos, lastMatchMovementCount,
                eventsDropped, List.copyOf(new ArrayList<>(recent)),
                velocityCallbacks, velocityOtherEntity, velocityEntityIdUnreadable,
                lastVelocityPacketEntityId, expectedSelfEntityId);
    }

    private long tick() {
        lastNanos = Math.max(lastNanos, clock.getAsLong());
        expirePending(lastNanos);
        return lastNanos;
    }

    private void expirePending(long now) {
        Iterator<Pending> iter = pending.values().iterator();
        while (iter.hasNext()) {
            long elapsed = now - iter.next().lastOutboundNanos;
            if (elapsed >= PENDING_TTL_NANOS && elapsed >= 0L) {
                iter.remove();
                expiredOrEvicted++;
            }
        }
    }

    private void append(long now, EventKind kind, Result result, Integer id,
                        MovementPacketBuffer.Velocity velocity) {
        if (recent.size() >= MAX_EVENTS) {
            recent.removeFirst();
            eventsDropped++;
        }
        recent.addLast(new Event(++order, now, kind, result, id, velocity,
                movementPackets, pending.size()));
    }
}
