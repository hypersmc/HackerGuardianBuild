package me.hackerguardian.main.detection.packet;

import java.util.Arrays;

/**
 * Session-only, bounded horizontal displacement of genuine client POSITION
 * packet pairs that belong to a server-observed sprint-switch window.
 *
 * Units are blocks per accepted packet interval, not blocks/50ms: the
 * server-tick hint is NOT an authenticated client simulation tick.
 *
 * "nearZero" means observed distance <= 0.002 blocks per packet interval.
 * "dragLike" counts non-near-zero observations with a residual <= 0.005
 * against the provisional zero-input drag model. Neither proves a key
 * release, friction coefficient, or legitimate movement.
 *
 * Two 128-slot rings; sorting and near-zero/drag-like tallying are done
 * only when staff request a snapshot, never in the packet callback.
 */
public final class PacketSwitchDisplacements {
    public static final double NEAR_ZERO = 0.002;
    public static final double DRAG_LIKE_RESIDUAL = 0.005;
    private static final int CAPACITY = 128;

    public record Stats(long total, int retained, Double p50, Double p95,
                        Double max, int nearZero, int dragLike) {}

    public record Snapshot(Stats enteringWalk, Stats enteringSprint) {}

    private final Ring enteringWalk = new Ring();
    private final Ring enteringSprint = new Ring();

    public synchronized void observe(boolean enteringSprint, double distance,
                                     double residual) {
        if (!Double.isFinite(distance) || distance < 0
                || !Double.isFinite(residual) || residual < 0) {
            throw new IllegalArgumentException("Valid packet distance and residual required");
        }
        (enteringSprint ? this.enteringSprint : this.enteringWalk).add(distance, residual);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(enteringWalk.stats(), enteringSprint.stats());
    }

    private static final class Ring {
        private final double[] distances = new double[CAPACITY];
        private final double[] residuals = new double[CAPACITY];
        private int next, size;
        private long total;

        private void add(double distance, double residual) {
            distances[next] = distance;
            residuals[next] = residual;
            next = (next + 1) % CAPACITY;
            size = Math.min(CAPACITY, size + 1);
            total++;
        }

        private Stats stats() {
            if (size == 0) return new Stats(total, 0, null, null, null, 0, 0);
            double[] sorted = Arrays.copyOf(distances, size);
            Arrays.sort(sorted);
            int still = 0, dragLike = 0;
            for (int i = 0; i < size; i++) {
                if (distances[i] <= NEAR_ZERO) {
                    still++;
                } else if (residuals[i] <= DRAG_LIKE_RESIDUAL) {
                    dragLike++;
                }
            }
            return new Stats(total, size, nearest(sorted, .50),
                    nearest(sorted, .95), sorted[size - 1], still, dragLike);
        }

        private static double nearest(double[] sorted, double fraction) {
            return sorted[Math.max(0, (int) Math.ceil(sorted.length * fraction) - 1)];
        }
    }
}
