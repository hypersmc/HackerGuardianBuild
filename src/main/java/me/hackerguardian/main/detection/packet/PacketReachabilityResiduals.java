package me.hackerguardian.main.detection.packet;

import java.util.Arrays;

/**
 * Bounded research-only summaries of residuals calculated from incoming
 * client POSITION/POSITION_LOOK packet coordinates, NOT Bukkit positions.
 *
 * residual = |packet displacement - drag * prior packet displacement|
 * is the UNKNOWN-INPUT contribution needed by the provisional model.
 * It is not a predictor error, evidence of cheating, or a measured key press.
 * excess = max(0, residual - conservative reachable disk radius).
 *
 * Per-player memory stays constant (128 observations per movement mode).
 * Sorting/percentiles run only on admin snapshot requests, never per packet.
 */
public final class PacketReachabilityResiduals {
    private static final int CAPACITY = 128;

    public record ModeStats(long total, int retained, long outside,
                            Double p50, Double p95, Double max,
                            Double maxExcess) {
        public boolean available() { return retained > 0; }
    }

    public record Snapshot(ModeStats walking, ModeStats sprinting) {}

    private final Ring walking = new Ring();
    private final Ring sprinting = new Ring();

    /** Called only after a packet triple passes every model eligibility gate. */
    public synchronized void observe(boolean sprinting, double residual, double excess) {
        if (!Double.isFinite(residual) || residual < 0
                || !Double.isFinite(excess) || excess < 0 || excess > residual) {
            throw new IllegalArgumentException("Invalid packet reachability residual/excess");
        }
        (sprinting ? this.sprinting : walking).add(residual, excess);
    }

    /** Never changes counters or retained packet measurements. */
    public synchronized Snapshot snapshot() {
        return new Snapshot(walking.stats(), sprinting.stats());
    }

    private static final class Ring {
        private final double[] residuals = new double[CAPACITY];
        private final double[] excesses = new double[CAPACITY];
        private int size, next;
        private long total, outside;

        private void add(double residual, double excess) {
            residuals[next] = residual;
            excesses[next] = excess;
            next = (next + 1) % CAPACITY;
            size = Math.min(CAPACITY, size + 1);
            total++;
            if (excess > 0) outside++;
        }

        private ModeStats stats() {
            if (size == 0) return new ModeStats(total, 0, outside, null, null, null, null);
            double[] sorted = Arrays.copyOf(residuals, size);
            Arrays.sort(sorted);
            double maxExcess = 0;
            for (int i = 0; i < size; i++) maxExcess = Math.max(maxExcess, excesses[i]);
            return new ModeStats(total, size, outside, nearest(sorted, .50),
                    nearest(sorted, .95), sorted[size - 1], maxExcess);
        }

        private static double nearest(double[] sorted, double fraction) {
            return sorted[Math.max(0, (int) Math.ceil(sorted.length * fraction) - 1)];
        }
    }
}
