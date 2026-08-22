package me.hackerguardian.main.detection.learning;

import java.util.UUID;

/** Persistent trust/collection state for one baseline-learning player. */
public final class LearningPlayerState {

    private final UUID playerId;
    private String playerName;
    private long firstTrustedMs;
    private long lastSeenMs;
    private long collectedMs;
    private long lastProbeMs;
    private boolean trustedLastSeen;

    public LearningPlayerState(UUID playerId) {
        if (playerId == null) throw new IllegalArgumentException("playerId is required");
        this.playerId = playerId;
    }

    public synchronized void markSeen(String name, boolean trusted, long nowMs) {
        if (name != null && !name.isBlank()) this.playerName = name;
        this.lastSeenMs = Math.max(this.lastSeenMs, nowMs);
        this.trustedLastSeen = trusted;
        if (trusted && firstTrustedMs <= 0L) firstTrustedMs = nowMs;
    }

    public synchronized void addCollected(long deltaMs) {
        if (deltaMs <= 0L) return;
        long next = collectedMs + deltaMs;
        collectedMs = next < collectedMs ? Long.MAX_VALUE : next;
    }

    public synchronized void markProbe(long nowMs) {
        lastProbeMs = Math.max(lastProbeMs, nowMs);
    }

    synchronized void restore(String playerName,
                              long firstTrustedMs,
                              long lastSeenMs,
                              long collectedMs,
                              long lastProbeMs,
                              boolean trustedLastSeen) {
        this.playerName = playerName == null ? "" : playerName;
        this.firstTrustedMs = Math.max(0L, firstTrustedMs);
        this.lastSeenMs = Math.max(0L, lastSeenMs);
        this.collectedMs = Math.max(0L, collectedMs);
        this.lastProbeMs = Math.max(0L, lastProbeMs);
        this.trustedLastSeen = trustedLastSeen;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                playerId,
                playerName == null ? "" : playerName,
                firstTrustedMs,
                lastSeenMs,
                collectedMs,
                lastProbeMs,
                trustedLastSeen
        );
    }

    public static final class Snapshot {
        private final UUID playerId;
        private final String playerName;
        private final long firstTrustedMs;
        private final long lastSeenMs;
        private final long collectedMs;
        private final long lastProbeMs;
        private final boolean trustedLastSeen;

        Snapshot(UUID playerId,
                 String playerName,
                 long firstTrustedMs,
                 long lastSeenMs,
                 long collectedMs,
                 long lastProbeMs,
                 boolean trustedLastSeen) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.firstTrustedMs = firstTrustedMs;
            this.lastSeenMs = lastSeenMs;
            this.collectedMs = collectedMs;
            this.lastProbeMs = lastProbeMs;
            this.trustedLastSeen = trustedLastSeen;
        }

        public UUID getPlayerId() { return playerId; }
        public String getPlayerName() { return playerName; }
        public long getFirstTrustedMs() { return firstTrustedMs; }
        public long getLastSeenMs() { return lastSeenMs; }
        public long getCollectedMs() { return collectedMs; }
        public long getLastProbeMs() { return lastProbeMs; }
        public boolean isTrustedLastSeen() { return trustedLastSeen; }
        public double getCollectedHours() { return collectedMs / 3_600_000.0; }
    }
}
