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
    private static final int MAX_TICKS = 64;
    private static final long MIN_STABLE_TICKS_AFTER_CORRECTION = 2L;

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

    public record TickInput(String worldKey, MovementPacketBuffer.Position position,
                            MovementPacketBuffer.Velocity velocity,
                            PlayerFlags flags, Environment environment) {
        public TickInput {
            if (worldKey == null || worldKey.isBlank() || position == null
                    || velocity == null || flags == null || environment == null) {
                throw new IllegalArgumentException("Incomplete server tick input");
            }
        }
    }

    public record ServerTick(long tick, long sampledNanos, long correctionEpoch,
                             TickInput state) {}

    public record ClientPosition(long packetOrder, long observedNanos,
                                 long serverTickHint, long correctionEpoch,
                                 MovementPacketBuffer.Position position) {}

    public record Diagnostic(Status status, Long clientOrder,
                             Long followingServerTick, Long tickLagNanos,
                             Double positionDifference, long correctionEpoch) {}

    public record Snapshot(long sampledTicks, long droppedTicks,
                           List<ServerTick> ticks, long clientPacketsObserved,
                           ClientPosition lastClient, long correctionEpoch,
                           Correction lastCorrection, long correctionTick,
                           Diagnostic comparison) {
        public ServerTick latestTick() {
            return ticks.isEmpty() ? null : ticks.get(ticks.size() - 1);
        }
    }

    private final LongSupplier clock;
    private final Deque<ServerTick> ticks = new ArrayDeque<>();
    private long sampledTicks, droppedTicks, clientPacketsObserved;
    private long lastNanos = Long.MIN_VALUE;
    private long correctionEpoch, correctionTick = Long.MIN_VALUE;
    private Correction lastCorrection;
    private ClientPosition lastClient;

    public MovementTickTimeline() {
        this(System::nanoTime);
    }

    MovementTickTimeline(LongSupplier clock) {
        if (clock == null) throw new IllegalArgumentException("clock required");
        this.clock = clock;
    }

    /** Call on the Bukkit main thread, once per scheduled server tick. */
    public synchronized void sampleServerTick(long serverTick, TickInput input) {
        if (input == null || serverTick < 0 || serverTick <= sampledTicks) {
            throw new IllegalArgumentException("Tick numbers must increase and input must be valid");
        }
        long at = monotonicTime();
        ServerTick previous = ticks.peekLast();
        if (previous != null && !previous.state().worldKey().equals(input.worldKey())) {
            // Even if a plugin changes a world without a Bukkit teleport
            // callback, never compare positions across world boundaries.
            correctionEpoch++;
            correctionTick = sampledTicks;
            lastCorrection = Correction.WORLD_CHANGE;
        }
        sampledTicks = serverTick;
        if (ticks.size() == MAX_TICKS) {
            ticks.removeFirst();
            droppedTicks++;
        }
        ticks.addLast(new ServerTick(serverTick, at, correctionEpoch, input));
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
    }

    /**
     * Invalidates comparisons that could cross a teleport or external velocity.
     * It does not establish when a client actually received the change.
     */
    public synchronized void markCorrection(Correction reason) {
        if (reason == null) return;
        monotonicTime();
        correctionEpoch++;
        correctionTick = sampledTicks;
        lastCorrection = reason;
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
        return new Snapshot(sampledTicks, droppedTicks, history,
                clientPacketsObserved, lastClient, correctionEpoch,
                lastCorrection, correctionTick, hasPendingTeleport && lastClient != null
                        ? result(Status.PENDING_TELEPORT, lastClient, null, null)
                        : compare(history, lastClient,
                                correctionEpoch, correctionTick, sampledTicks, now));
    }

    private static Diagnostic compare(List<ServerTick> history, ClientPosition client,
                                      long currentEpoch, long correctionAtTick,
                                      long latestTick, long now) {
        if (client == null) return new Diagnostic(Status.NO_CLIENT_POSITION,
                null, null, null, null, currentEpoch);
        if (history.isEmpty()) return result(Status.NO_SERVER_TICK, client, null, null);
        if (client.correctionEpoch() != currentEpoch) {
            return result(Status.DIFFERENT_CONTEXT, client, null, null);
        }
        if (latestTick - correctionAtTick < MIN_STABLE_TICKS_AFTER_CORRECTION
                && correctionAtTick != Long.MIN_VALUE) {
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
                || firstFollowing.tick() <= correctionAtTick + MIN_STABLE_TICKS_AFTER_CORRECTION
                && correctionAtTick != Long.MIN_VALUE) {
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
