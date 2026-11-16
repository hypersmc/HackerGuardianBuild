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
 * Bounded, thread-safe receive-order history of inbound movement packet types.
 *
 * This first increment intentionally records ONLY timing and packet kind.
 * It is NOT a movement physics validator, cheat detector, or ground truth
 * about client tick timing: proxy/network batching can alter arrival cadence.
 */
public final class MovementPacketBuffer {

    public enum Kind {
        GROUND, POSITION, LOOK, POSITION_LOOK
    }

    public record Observation(long sequence, long arrivalNanos, Kind kind) {}

    public record Snapshot(long totalReceived, long dropped, List<Observation> observations) {
        public int retained() {
            return observations.size();
        }

        /**
         * Counts packet kinds in the recent receive-time window. Only the bounded
         * retained history is available, so a sufficiently busy client may have
         * truncated the requested window. No result is a cheat verdict.
         *
         * @param nowNanos System.nanoTime() from the same VM/clock origin
         * @param windowNanos positive receive-time window duration
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
            // Dropped history is relevant only if the oldest retained observation
            // lies inside this window: older drops cannot affect the recent counts.
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
    private final LongSupplier clock;
    private final ConcurrentMap<UUID, PlayerStream> streams = new ConcurrentHashMap<>();

    public MovementPacketBuffer(int maxPerPlayer) {
        this(maxPerPlayer, System::nanoTime);
    }

    // Package-private clock injection allows deterministic tests, including clock regressions.
    MovementPacketBuffer(int maxPerPlayer, LongSupplier clock) {
        if (clock == null) throw new IllegalArgumentException("clock cannot be null");
        this.maxPerPlayer = Math.max(1, Math.min(maxPerPlayer, 8192));
        this.clock = clock;
    }

    /**
     * Safe on a Netty/ProtocolLib receive thread. Never touches Bukkit state.
     */
    public void record(UUID playerId, Kind kind) {
        if (playerId == null || kind == null) return;
        PlayerStream stream = streams.computeIfAbsent(playerId, unused -> new PlayerStream());
        synchronized (stream) {
            long now = clock.getAsLong();
            // Do not let a test clock or uncommon clock regression reorder observations.
            now = Math.max(now, stream.lastNanos);
            stream.lastNanos = now;
            long seq = ++stream.received;
            if (stream.entries.size() >= maxPerPlayer) {
                stream.entries.removeFirst();
                stream.dropped++;
            }
            stream.entries.addLast(new Observation(seq, now, kind));
        }
    }

    public Snapshot snapshot(UUID playerId) {
        if (playerId == null) return new Snapshot(0, 0, List.of());
        PlayerStream stream = streams.get(playerId);
        if (stream == null) return new Snapshot(0, 0, List.of());
        synchronized (stream) {
            return new Snapshot(
                    stream.received,
                    stream.dropped,
                    Collections.unmodifiableList(new ArrayList<>(stream.entries))
            );
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
        private long received;
        private long dropped;
        private long lastNanos = Long.MIN_VALUE;
    }
}
