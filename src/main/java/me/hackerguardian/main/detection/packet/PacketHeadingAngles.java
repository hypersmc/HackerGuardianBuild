package me.hackerguardian.main.detection.packet;

import java.util.Arrays;

/**
 * Bounded, observe-only heading changes between two consecutive horizontal
 * displacement vectors decoded from actual client POSITION packets.
 *
 * This is NOT client camera yaw, an authenticated movement-input direction,
 * or a turn-cheat detection. Comparisons are made only after the existing
 * PacketGroundReachability packet/context/correction gates accept a triple.
 *
 * Two vectors must both move at least 0.075 blocks per accepted packet
 * interval to define a useful heading. A player turning their camera while
 * travelling straight will correctly produce a zero heading change.
 *
 * Fixed-size 128-angle ring; sorting is performed only on staff snapshots.
 */
public final class PacketHeadingAngles {
    public static final double MIN_DIRECTION_SPEED = 0.075;
    private static final int CAPACITY = 128;

    public record Snapshot(long measured, int retained, long lowMotion,
                           long over5, long over15, long over35,
                           Double p50, Double p95, Double max) {}

    private final double[] degrees = new double[CAPACITY];
    private int next, size;
    private long measured, lowMotion, over5, over15, over35;

    /**
     * Return measured heading change [0,180], or null if either vector is
     * too short. The latter is a no-angle observation, not a packet failure.
     * Caller already validates finite displacement and safe context.
     */
    public synchronized Double observe(double previousDx, double previousDz,
                                       double currentDx, double currentDz) {
        if (!Double.isFinite(previousDx) || !Double.isFinite(previousDz)
                || !Double.isFinite(currentDx) || !Double.isFinite(currentDz)) {
            throw new IllegalArgumentException("Finite client packet deltas required");
        }
        double previousSpeed = Math.hypot(previousDx, previousDz);
        double currentSpeed = Math.hypot(currentDx, currentDz);
        if (!Double.isFinite(previousSpeed) || !Double.isFinite(currentSpeed)) {
            throw new IllegalArgumentException("Unbounded client packet deltas");
        }
        if (previousSpeed < MIN_DIRECTION_SPEED || currentSpeed < MIN_DIRECTION_SPEED) {
            lowMotion++;
            return null;
        }

        // Normalize *before* dot and cross products. atan2 avoids the
        // imprecision of acos for small heading changes and gives [0,180].
        double px = previousDx / previousSpeed, pz = previousDz / previousSpeed;
        double cx = currentDx / currentSpeed, cz = currentDz / currentSpeed;
        double dot = px * cx + pz * cz;
        double cross = px * cz - pz * cx;
        double angle = Math.toDegrees(Math.atan2(Math.abs(cross), dot));
        if (!Double.isFinite(angle)) {
            throw new IllegalArgumentException("Unbounded heading angle");
        }

        degrees[next] = angle;
        next = (next + 1) % CAPACITY;
        size = Math.min(size + 1, CAPACITY);
        measured++;
        if (angle >= 5.0) over5++;
        if (angle >= 15.0) over15++;
        if (angle >= 35.0) over35++;
        return angle;
    }

    public synchronized Snapshot snapshot() {
        if (size == 0) {
            return new Snapshot(measured, 0, lowMotion, over5, over15, over35,
                    null, null, null);
        }
        double[] sorted = Arrays.copyOf(degrees, size);
        Arrays.sort(sorted);
        return new Snapshot(measured, size, lowMotion, over5, over15, over35,
                nearest(sorted, .50), nearest(sorted, .95), sorted[size - 1]);
    }

    private static double nearest(double[] sorted, double percentile) {
        return sorted[Math.max(0, (int) Math.ceil(sorted.length * percentile) - 1)];
    }
}
