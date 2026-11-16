package me.hackerguardian.main.detection.packet;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Read-only bridge between packet-thread client claims and Bukkit-thread
 * server snapshots. Neither stream establishes exact client simulation ticks.
 *
 * A sampled server position is NOT a physics prediction. Comparison distances
 * are diagnostic only; no finding, setback or punishment can originate here.
 * All inputs are immutable primitives/records, never Bukkit/NMS references.
 */
public final class MovementTickTimeline {
    private static final long MAX_SAMPLE_LAG_NANOS = 250_000_000L;
    // A previously completed diagnostic is useful during continuous packet
    // traffic, but must not masquerade as a current position indefinitely.
    private static final long MAX_COMPLETED_AGE_NANOS = 750_000_000L;
    private static final int MAX_TICKS = 64;
    private static final long MIN_STABLE_SAMPLES_AFTER_CORRECTION = 2L;

    public enum Status {
        NO_CLIENT_POSITION, NO_SERVER_TICK, AWAITING_NEXT_TICK,
        TOO_OLD, CORRECTION_WINDOW, DIFFERENT_CONTEXT, PENDING_TELEPORT,
        READY_DIAGNOSTIC
    }

    public enum Correction {
        BUKKIT_TELEPORT, BUKKIT_VELOCITY, OUTBOUND_TELEPORT,
        TELEPORT_CONFIRM, OUTBOUND_SELF_VELOCITY, RESPAWN, WORLD_CHANGE
    }

    /**
     * These world/block fields are environmental hints, NOT authoritative
     * collision geometry. Some blocks have partial or dynamic hitboxes.
     */
    public record Environment(boolean feetPassable, boolean belowPassable,
                              boolean headPassable, boolean feetLiquid,
                              boolean belowLiquid) {}

    public record PlayerFlags(boolean serverOnGround, boolean flying,
                              boolean gliding, boolean swimming,
                              boolean inVehicle, boolean creativeOrSpectator) {}

    /**
     * Explicitly unknown by default. Ground-envelope research must never
     * assume a Bukkit snapshot implies ordinary, unmodified vanilla physics.
     */
    public record ProbeContext(boolean verified, boolean ordinaryFlatSurface,
                               boolean noActiveEffects, boolean defaultWalkSpeed) {
        public static ProbeContext unknown() {
            return new ProbeContext(false, false, false, false);
        }
    }

    public record TickInput(String worldKey, MovementPacketBuffer.Position position,
                            MovementPacketBuffer.Velocity velocity,
                            PlayerFlags flags, Environment environment,
                            ProbeContext probeContext) {
        public TickInput(String worldKey, MovementPacketBuffer.Position position,
                         MovementPacketBuffer.Velocity velocity,
                         PlayerFlags flags, Environment environment) {
            this(worldKey, position, velocity, flags, environment, ProbeContext.unknown());
        }

        public TickInput {
            if (worldKey == null || worldKey.isBlank() || position == null
                    || velocity == null || flags == null || environment == null
                    || probeContext == null) {
                throw new IllegalArgumentException("Incomplete server tick input");
            }
        }
    }

    public record ServerTick(long tick, long sampleOrder, long sampledNanos,
                             long correctionEpoch, TickInput state) {}

    public record ClientPosition(long packetOrder, long observedNanos,
                                 long serverTickHint, long correctionEpoch,
                                 MovementPacketBuffer.Position position) {}

    public record Diagnostic(Status status, Long clientOrder,
                             Long followingServerTick, Long tickLagNanos,
                             Double positionDifference, long correctionEpoch) {}

    /**
     * Session-scoped event and actual comparison-guard counters. Only source
     * events and server tick evaluations change these totals; snapshots do not.
     *
     * Corrections count callback events (one teleport can produce several),
     * not unique physical teleports or cheating incidents.
     */
    public record GuardStats(long teleportBoundaries, long velocityBoundaries,
                             long otherBoundaries, long pendingClaimsInvalidated,
                             long completedPairsInvalidated, long recoverySuppressions,
                             long pendingTeleportSuppressions, long contextSuppressions,
                             long completedPairs, Status lastSuppression,
                             long lastSuppressionTick) {}

    public record Snapshot(long sampledTicks, long droppedTicks,
                           List<ServerTick> ticks, long clientPacketsObserved,
                           ClientPosition lastClient, long correctionEpoch,
                           Correction lastCorrection, long correctionTick,
                           Diagnostic comparison, GuardStats guards) {
        public ServerTick latestTick() {
            return ticks.isEmpty() ? null : ticks.get(ticks.size() - 1);
        }
    }

    private final LongSupplier clock;
    private final Deque<ServerTick> ticks = new ArrayDeque<>();
    private long sampledTicks, sampledCount, droppedTicks, clientPacketsObserved;
    private long lastNanos = Long.MIN_VALUE;
    private long correctionEpoch, correctionTick = Long.MIN_VALUE;
    private long correctionSampleCount = Long.MIN_VALUE;
    private Correction lastCorrection;
    private ClientPosition lastClient;
    private ClientPosition pendingClient;
    private Diagnostic lastCompletedComparison;
    private long lastCompletedAtNanos = Long.MIN_VALUE;
    private long teleportBoundaries, velocityBoundaries, otherBoundaries;
    private long pendingClaimsInvalidated, completedPairsInvalidated;
    private long recoverySuppressions, pendingTeleportSuppressions, contextSuppressions;
    private long completedPairs;
    private Status lastSuppression;
    private long lastSuppressionTick = -1;

    public MovementTickTimeline() {
        this(System::nanoTime);
    }

    MovementTickTimeline(LongSupplier clock) {
        if (clock == null) throw new IllegalArgumentException("clock required");
        this.clock = clock;
    }

    /** Call on the Bukkit main thread, once per scheduled server tick. */
    public synchronized void sampleServerTick(long serverTick, TickInput input) {
        sampleServerTick(serverTick, input, false);
    }

    /**
     * Pending teleport truth comes from the per-player network transport.
     * Only actual incoming position claims paired with a server sample can
     * increment suppression counters.
     */
    public synchronized void sampleServerTick(long serverTick, TickInput input,
                                              boolean hasPendingTeleport) {
        if (input == null || serverTick < 0 || serverTick <= sampledTicks) {
            throw new IllegalArgumentException("Tick numbers must increase and input must be valid");
        }
        long at = monotonicTime();
        ServerTick previous = ticks.peekLast();
        if (previous != null && !previous.state().worldKey().equals(input.worldKey())) {
            // Even if a plugin changes a world without a Bukkit teleport
            // callback, never compare positions across world boundaries.
            applyCorrection(Correction.WORLD_CHANGE);
        }
        sampledTicks = serverTick;
        if (ticks.size() == MAX_TICKS) {
            ticks.removeFirst();
            droppedTicks++;
        }
        ServerTick captured = new ServerTick(serverTick, ++sampledCount, at, correctionEpoch, input);
        ticks.addLast(captured);
        // Commit at most one pending client claim per server sample. It is the
        // newest claim observed before this sample, not necessarily the packet
        // that Minecraft used to calculate the server's position.
        if (pendingClient != null) {
            Diagnostic paired = hasPendingTeleport
                    ? result(Status.PENDING_TELEPORT, pendingClient, null, null)
                    : compare(List.of(captured), pendingClient,
                            correctionEpoch, correctionSampleCount, sampledCount, at);
            if (paired.status() == Status.READY_DIAGNOSTIC) {
                lastCompletedComparison = paired;
                lastCompletedAtNanos = at;
                completedPairs++;
            } else {
                countSuppression(paired.status(), serverTick);
            }
            pendingClient = null;
        }
    }

    /**
     * Call from ProtocolLib packet receive callback after decoding a position
     * claim. The serverTickHint is only the most recently SAMPLED scheduler tick.
     * It must never be called a client tick.
     */
    public synchronized void observeClientPosition(MovementPacketBuffer.Position position) {
        if (position == null) return;
        long at = monotonicTime();
        lastClient = new ClientPosition(++clientPacketsObserved, at,
                sampledTicks, correctionEpoch, position);
        pendingClient = lastClient;
    }

    /**
     * Invalidates comparisons that could cross a teleport or external velocity.
     * It does not establish when a client actually received the change.
     */
    public synchronized void markCorrection(Correction reason) {
        if (reason == null) return;
        monotonicTime();
        applyCorrection(reason);
    }

    private void applyCorrection(Correction reason) {
        correctionEpoch++;
        correctionTick = sampledTicks;
        correctionSampleCount = sampledCount;
        lastCorrection = reason;
        switch (reason) {
            case BUKKIT_TELEPORT, OUTBOUND_TELEPORT, TELEPORT_CONFIRM -> teleportBoundaries++;
            case BUKKIT_VELOCITY, OUTBOUND_SELF_VELOCITY -> velocityBoundaries++;
            case RESPAWN, WORLD_CHANGE -> otherBoundaries++;
        }
        // Dropped pending observations are counted as invalidated claims,
        // not as a completed server-tick comparison.
        if (pendingClient != null) pendingClaimsInvalidated++;
        if (lastCompletedComparison != null) completedPairsInvalidated++;
        pendingClient = null;
        lastCompletedComparison = null;
    }

    private void countSuppression(Status status, long serverTick) {
        switch (status) {
            case CORRECTION_WINDOW -> recoverySuppressions++;
            case PENDING_TELEPORT -> pendingTeleportSuppressions++;
            case DIFFERENT_CONTEXT -> contextSuppressions++;
            default -> { return; }
        }
        lastSuppression = status;
        lastSuppressionTick = serverTick;
    }

    private GuardStats guardStats() {
        return new GuardStats(teleportBoundaries, velocityBoundaries,
                otherBoundaries, pendingClaimsInvalidated, completedPairsInvalidated,
                recoverySuppressions, pendingTeleportSuppressions,
                contextSuppressions, completedPairs, lastSuppression,
                lastSuppressionTick);
    }

    /** A recent, current-epoch inbound client position is needed for the
     *  research probe, though the measured displacement is server-side.
     *  Does not mutate timeline state.
     */
    public synchronized boolean hasRecentClientPosition() {
        long now = Math.max(lastNanos, clock.getAsLong());
        return lastClient != null && lastClient.correctionEpoch() == correctionEpoch
                && now >= lastClient.observedNanos()
                && now - lastClient.observedNanos() <= MAX_SAMPLE_LAG_NANOS;
    }

    /** Last recorded epoch, not a clock or physics value. */
    public synchronized long currentCorrectionEpoch() {
        return correctionEpoch;
    }

    /** Require three actual sampled states after a correction, not three
     *  scheduler tick numbers that could include missing samples.
     */
    public synchronized boolean settledForResearch(boolean pendingTeleport) {
        return !pendingTeleport && (correctionSampleCount == Long.MIN_VALUE
                || sampledCount - correctionSampleCount > MIN_STABLE_SAMPLES_AFTER_CORRECTION);
    }

    public synchronized Snapshot snapshot() {
        return snapshot(false);
    }

    /**
     * A pending outbound teleport must prevent comparisons, even after several
     * server tick samples. The transport tracker owns the pending-ID truth.
     */
    public synchronized Snapshot snapshot(boolean hasPendingTeleport) {
        // Reading diagnostics must NOT advance the serialized timeline clock.
        // Only actual packet/server events get a distinct observation stamp.
        long now = Math.max(lastNanos, clock.getAsLong());
        List<ServerTick> history = List.copyOf(new ArrayList<>(ticks));
        Diagnostic comparison;
        if (hasPendingTeleport && lastClient != null) {
            comparison = result(Status.PENDING_TELEPORT, lastClient, null, null);
        } else if (lastClient != null && lastCompletedComparison != null
                && lastCompletedComparison.correctionEpoch() == correctionEpoch
                && now >= lastCompletedAtNanos
                && now - lastCompletedAtNanos <= MAX_COMPLETED_AGE_NANOS) {
            // Show a recent COMPLETED pair, even if a newer packet arrived
            // after the last server tick. Never call it the latest position.
            comparison = lastCompletedComparison;
        } else if (lastClient != null && lastCompletedComparison != null
                && (pendingClient == null
                    || pendingClient.packetOrder() <= lastCompletedComparison.clientOrder())
                && now > lastCompletedAtNanos
                && now - lastCompletedAtNanos > MAX_COMPLETED_AGE_NANOS) {
            // A formerly valid pair must expire, even if the historical
            // matching server tick still sits in the 64-sample ring.
            comparison = result(Status.TOO_OLD, lastClient, null, null);
        } else {
            comparison = compare(history, lastClient,
                    correctionEpoch, correctionSampleCount, sampledCount, now);
        }
        return new Snapshot(sampledTicks, droppedTicks, history,
                clientPacketsObserved, lastClient, correctionEpoch,
                lastCorrection, correctionTick, comparison, guardStats());
    }

    private static Diagnostic compare(List<ServerTick> history, ClientPosition client,
                                      long currentEpoch, long correctionAtSample,
                                      long latestSample, long now) {
        if (client == null) return new Diagnostic(Status.NO_CLIENT_POSITION,
                null, null, null, null, currentEpoch);
        if (history.isEmpty()) return result(Status.NO_SERVER_TICK, client, null, null);
        if (client.correctionEpoch() != currentEpoch) {
            return result(Status.DIFFERENT_CONTEXT, client, null, null);
        }
        if (correctionAtSample != Long.MIN_VALUE
                && latestSample - correctionAtSample < MIN_STABLE_SAMPLES_AFTER_CORRECTION) {
            return result(Status.CORRECTION_WINDOW, client, null, null);
        }
        ServerTick firstFollowing = null;
        for (ServerTick tick : history) {
            if (tick.sampledNanos() >= client.observedNanos()) {
                firstFollowing = tick;
                break;
            }
        }
        if (firstFollowing == null) {
            // Do not interpret an older packet position as a current state.
            return result(now - client.observedNanos() > MAX_SAMPLE_LAG_NANOS
                    ? Status.TOO_OLD : Status.AWAITING_NEXT_TICK, client, null, null);
        }
        long lag = firstFollowing.sampledNanos() - client.observedNanos();
        if (lag < 0 || lag > MAX_SAMPLE_LAG_NANOS) {
            return result(Status.TOO_OLD, client, firstFollowing, lag);
        }
        if (firstFollowing.correctionEpoch() != client.correctionEpoch()
                || (correctionAtSample != Long.MIN_VALUE
                    && firstFollowing.sampleOrder()
                        <= correctionAtSample + MIN_STABLE_SAMPLES_AFTER_CORRECTION)) {
            return result(Status.CORRECTION_WINDOW, client, firstFollowing, lag);
        }

        MovementPacketBuffer.Position a = client.position();
        MovementPacketBuffer.Position b = firstFollowing.state().position();
        double diff = Math.hypot(Math.hypot(a.x() - b.x(), a.y() - b.y()), a.z() - b.z());
        return new Diagnostic(Status.READY_DIAGNOSTIC, client.packetOrder(),
                firstFollowing.tick(), lag, Double.isFinite(diff) ? diff : null, currentEpoch);
    }

    private static Diagnostic result(Status status, ClientPosition client,
                                     ServerTick tick, Long lag) {
        return new Diagnostic(status, client.packetOrder(),
                tick == null ? null : tick.tick(), lag, null, client.correctionEpoch());
    }

    private long monotonicTime() {
        long value = clock.getAsLong();
        // Distinct callbacks can share one nanoTime value. Preserve their
        // serialized observation order so a following server tick is never
        // confused with an earlier one at the exact same timestamp.
        long next = lastNanos == Long.MAX_VALUE ? Long.MAX_VALUE : lastNanos + 1;
        lastNanos = Math.max(next, value);
        return lastNanos;
    }
}
