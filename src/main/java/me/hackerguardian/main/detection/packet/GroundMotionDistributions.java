package me.hackerguardian.main.detection.packet;

import java.util.Arrays;

/**
 * Session-only, bounded descriptive statistics for eligible ground-motion
 * samples. These are SERVER-OBSERVED displacements, not predicted Minecraft
 * speeds, confidence bounds, anti-cheat scores or ML training labels.
 *
 * Each group retains at most 128 values. Percentiles describe the most recent
 * retained observations, while totalSamples is a lifetime session counter.
 */
public final class GroundMotionDistributions {
    private static final int CAPACITY = 128;

    public record Stats(long totalSamples, int retained, Double mean,
                        Double p50, Double p95, Double max) {
        public boolean available() {
            return retained > 0;
        }
    }

    public record Snapshot(Stats walking, Stats sprinting) {}

    private final Ring walking = new Ring();
    private final Ring sprinting = new Ring();

    /**
     * Normalize a valid server-side horizontal displacement to blocks per
     * 50ms. Do not make a claim about client input timing or tick length.
     * The caller must already have rejected laggy/unsupported sample pairs.
     */
    public synchronized void observe(boolean sprinting, double blocksPer50ms) {
        if (!Double.isFinite(blocksPer50ms) || blocksPer50ms < 0) {
            throw new IllegalArgumentException("Finite non-negative displacement required");
        }
        (sprinting ? this.sprinting : walking).add(blocksPer50ms);
    }

    /** Sorting happens only when staff request a snapshot, not each tick. */
    public synchronized Snapshot snapshot() {
        return new Snapshot(walking.stats(), sprinting.stats());
    }

    private static final class Ring {
        private final double[] values = new double[CAPACITY];
        private long total;
        private int size, next;

        private void add(double value) {
            values[next] = value;
            next = (next + 1) % CAPACITY;
            if (size < CAPACITY) size++;
            total++;
        }

        private Stats stats() {
            if (size == 0) return new Stats(total, 0, null, null, null, null);
            double[] sorted = Arrays.copyOf(values, size);
            Arrays.sort(sorted);
            double sum = 0;
            for (double v : sorted) sum += v;
            return new Stats(total, size, sum / size,
                    nearestRank(sorted, .50), nearestRank(sorted, .95),
                    sorted[size - 1]);
        }

        private static double nearestRank(double[] sorted, double percentile) {
            int rank = Math.max(0, (int) Math.ceil(percentile * sorted.length) - 1);
            return sorted[rank];
        }
    }
}
