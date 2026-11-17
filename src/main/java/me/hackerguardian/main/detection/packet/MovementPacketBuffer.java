package me.hackerguardian.main.detection.packet;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/**
 * Bounded, thread-safe receipt history for movement packets and server-intent context.
 *
 * Client packets are observations, not validated player state. Server teleports
 * and velocity events are separately labeled Bukkit event context, NOT proof
 * that a client received or acknowledged a network packet.
 *
 * Never infer cheating from a raw observation count, rate, or coordinate alone.
 */
public final class MovementPacketBuffer {

    public enum Kind {
        GROUND, POSITION, LOOK, POSITION_LOOK
    }

    public enum ContextKind {
        SERVER_TELEPORT, SERVER_VELOCITY
    }

    public record Position(double x, double y, double z) {
        public Position {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("Position must be finite");
            }
        }
    }

    public record Rotation(float yaw, float pitch) {
        public Rotation {
            if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
                throw new IllegalArgumentException("Rotation must be finite");
            }
        }
    }

    public record Velocity(double x, double y, double z) {
        public Velocity {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("Velocity must be finite");
            }
        }
    }

    /**
     * Each value can be absent because the packet type does not carry it.
     * A failed decode has decoded=false; it is still counted as a packet,
     * but does not contribute to the last-known raw client fields.
     */
    public record PacketState(Position position, Rotation rotation, Boolean onGround,
                              Boolean horizontalCollision, boolean decoded) {
        public static PacketState missing() {
            return new PacketState(null, null, null, null, false);
        }

        public static PacketState kindsOnly() {
            return new PacketState(null, null, null, null, true);
        }
    }

    public record Observation(long sequence, long arrivalNanos, Kind kind, PacketState state) {}

    /**
     * Context sequence is independent of client-packet sequence. A single
     * System.nanoTime clock lets staff correlate approximate ordering, but
     * different threads/packet transport may still reorder true send times.
     */
    public record ServerContext(long sequence, long arrivalNanos, ContextKind kind,
                                String worldKey, Position position, Rotation rotation,
                                Velocity velocity, String cause) {}

    public record LastKnown(Position position, long positionSequence,
                            Rotation rotation, long rotationSequence,
                            Boolean onGround, Boolean horizontalCollision,
                            long groundSequence) {}

    public record Snapshot(long totalReceived, long dropped, long decodeFailures,
                           List<Observation> observations, long totalContexts,
                           long droppedContexts, List<ServerContext> contexts) {
        public int retained() {
            return observations.size();
        }

        /**
         * Last value actually supplied by a successfully decoded client packet.
         * Components can come from different packets. Never treat this as a
         * verified atomic player location or position after a server teleport.
         */
        public LastKnown lastKnownClientFields() {
            Position position = null;
            Rotation rotation = null;
            Boolean onGround = null;
            Boolean collision = null;
            long positionSeq = 0, rotationSeq = 0, groundSeq = 0;
            for (Observation observation : observations) {
                PacketState state = observation.state();
                if (state == null || !state.decoded()) continue;
                if (state.position() != null) {
                    position = state.position();
                    positionSeq = observation.sequence();
                }
                if (state.rotation() != null) {
                    rotation = state.rotation();
                    rotationSeq = observation.sequence();
                }
                if (state.onGround() != null) {
                    onGround = state.onGround();
                    collision = state.horizontalCollision();
                    groundSeq = observation.sequence();
                }
            }
            return new LastKnown(position, positionSeq, rotation, rotationSeq,
                    onGround, collision, groundSeq);
        }

        /**
         * Only retained observations are available for this time window. If
         * history was overwritten within the window, the counts are lower bounds.
         */
        public WindowStats recentWindow(long nowNanos, long windowNanos) {
            if (windowNanos <= 0L) throw new IllegalArgumentException("windowNanos must be positive");
            long ground = 0, position = 0, look = 0, positionLook = 0;
            for (Observation observation : observations) {
                long age = nowNanos - observation.arrivalNanos();
                if (age < 0 || age > windowNanos) continue;
                switch (observation.kind()) {
                    case GROUND -> ground++;
                    case POSITION -> position++;
                    case LOOK -> look++;
                    case POSITION_LOOK -> positionLook++;
                }
            }
            boolean truncated = dropped > 0 && !observations.isEmpty()
                    && nowNanos - observations.get(0).arrivalNanos() >= 0
                    && nowNanos - observations.get(0).arrivalNanos() < windowNanos;
            return new WindowStats(windowNanos, ground, position, look, positionLook, truncated);
        }
    }

    public record WindowStats(long windowNanos, long ground, long position,
                              long look, long positionLook, boolean truncated) {
        public long count() {
            return ground + position + look + positionLook;
        }

        public double ratePerSecond() {
            return count() * 1_000_000_000.0 / windowNanos;
        }
    }

    private final int maxPerPlayer;
    private final int maxContexts;
    private final LongSupplier clock;
    private final ConcurrentMap<UUID, PlayerStream> streams = new ConcurrentHashMap<>();

    public MovementPacketBuffer(int maxPerPlayer) {
        this(maxPerPlayer, System::nanoTime);
    }

    // Clock injection keeps ordering tests deterministic.
    MovementPacketBuffer(int maxPerPlayer, LongSupplier clock) {
        if (clock == null) throw new IllegalArgumentException("clock cannot be null");
        this.maxPerPlayer = Math.max(1, Math.min(maxPerPlayer, 8192));
        this.maxContexts = Math.max(8, Math.min(64, this.maxPerPlayer / 4));
        this.clock = clock;
    }

    public void record(UUID playerId, Kind kind) {
        record(playerId, kind, PacketState.kindsOnly());
    }

    /** Safe from the ProtocolLib packet receive callback. Never touches Bukkit state. */
    public void record(UUID playerId, Kind kind, PacketState state) {
        if (playerId == null || kind == null) return;
        PlayerStream stream = streams.computeIfAbsent(playerId, unused -> new PlayerStream());
        synchronized (stream) {
            long now = stream.timestamp(clock.getAsLong());
            long seq = ++stream.received;
            if (stream.entries.size() >= maxPerPlayer) {
                stream.entries.removeFirst();
                stream.dropped++;
            }
            PacketState value = state == null ? PacketState.missing() : state;
            if (!value.decoded()) stream.decodeFailures++;
            stream.entries.addLast(new Observation(seq, now, kind, value));
        }
    }

    /** Call only on the Bukkit event thread with previously copied immutable values. */
    public void recordServerTeleport(UUID playerId, String worldKey, Position position,
                                     Rotation rotation, String cause) {
        if (playerId == null || position == null || rotation == null) return;
        recordContext(playerId, ContextKind.SERVER_TELEPORT, worldKey,
                position, rotation, null, cause);
    }

    /** Call only on the Bukkit event thread with previously copied immutable values. */
    public void recordServerVelocity(UUID playerId, String worldKey, Velocity velocity) {
        if (playerId == null || velocity == null) return;
        recordContext(playerId, ContextKind.SERVER_VELOCITY, worldKey,
                null, null, velocity, "");
    }

    private void recordContext(UUID playerId, ContextKind kind, String worldKey,
                               Position position, Rotation rotation, Velocity velocity, String cause) {
        PlayerStream stream = streams.computeIfAbsent(playerId, unused -> new PlayerStream());
        synchronized (stream) {
            long now = stream.timestamp(clock.getAsLong());
            long seq = ++stream.contextReceived;
            if (stream.contexts.size() >= maxContexts) {
                stream.contexts.removeFirst();
                stream.contextDropped++;
            }
            stream.contexts.addLast(new ServerContext(seq, now, kind,
                    worldKey == null ? "" : worldKey, position, rotation, velocity,
                    cause == null ? "" : cause));
        }
    }

    public Snapshot snapshot(UUID playerId) {
        if (playerId == null) return new Snapshot(0, 0, 0, List.of(), 0, 0, List.of());
        PlayerStream stream = streams.get(playerId);
        if (stream == null) return new Snapshot(0, 0, 0, List.of(), 0, 0, List.of());
        synchronized (stream) {
            return new Snapshot(stream.received, stream.dropped, stream.decodeFailures,
                    Collections.unmodifiableList(new ArrayList<>(stream.entries)),
                    stream.contextReceived, stream.contextDropped,
                    Collections.unmodifiableList(new ArrayList<>(stream.contexts)));
        }
    }

    public void clearPlayer(UUID playerId) {
        if (playerId != null) streams.remove(playerId);
    }

    public void clear() {
        streams.clear();
    }

    private static final class PlayerStream {
        private final Deque<Observation> entries = new ArrayDeque<>();
        private final Deque<ServerContext> contexts = new ArrayDeque<>();
        private long received;
        private long dropped;
        private long decodeFailures;
        private long contextReceived;
        private long contextDropped;
        private long lastNanos = Long.MIN_VALUE;

        private long timestamp(long nanos) {
            lastNanos = Math.max(nanos, lastNanos);
            return lastNanos;
        }
    }
}
