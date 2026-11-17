package me.hackerguardian.main.detection.packet;

import java.util.Arrays;

/**
 * Research-only changes between consecutive eligible ground displacement
 * observations. No client key states or vanilla friction coefficients are
 * available here. "Slowing" is an observed speed change, NOT proof that the
 * player released a key, and retention is NOT a block friction measurement.
 *
 * All frames are primitive, copied server-observed displacement vectors.
 * O(1) update, at most three 128-element rings. Percentiles sort only when
 * staff request a snapshot. No reporting, scores, learning or enforcement.
 */
public final class GroundMotionDynamics {
    private static final int CAPACITY = 128;
    // Descriptive phase buckets only: never apply these to player moderation.
    private static final double STILL = 0.025;         // blocks/50 ms
    private static final double MOVING = 0.075;        // blocks/50 ms
    private static final double CHANGE = 0.025;        // blocks/50 ms
    private static final double TURN_DEGREES = 35.0;

    public enum Phase {
        WARMUP, START, STOP, TURN, ACCELERATE, DECELERATE, STEADY,
        INTERRUPTED, SURFACE_CHANGE, SPRINT_CHANGE
    }

    public record Distribution(long total, int retained, Double p50,
                               Double p95, Double max) {}

    public record Snapshot(long eligibleVectors, long comparedTransitions,
                           long starts, long stops, long turns,
                           long accelerations, long decelerations, long steady,
                           long discontinuities, long surfaceChanges,
                           long sprintChanges, Phase lastPhase,
                           String lastSurface,
                           Distribution absoluteSpeedChange,
                           Distribution turnAngles,
                           Distribution slowingRetention) {}

    private record Frame(long tick, long epoch, double dx, double dz,
                         boolean sprint, String surface) {
        double speed() { return Math.hypot(dx, dz); }
    }

    private final Ring delta = new Ring();
    private final Ring angles = new Ring();
    private final Ring retention = new Ring();
    private Frame previous;
    private long eligibleVectors, comparedTransitions;
    private long starts, stops, turns, accelerations, decelerations, steady;
    private long discontinuities, surfaceChanges, sprintChanges;
    private Phase lastPhase = Phase.WARMUP;
    private String lastSurface = "unknown";

    /** Called only for pairs eligible under MovementGroundProbe's safeguards. */
    public synchronized void observe(long tick, long epoch, double dxPer50,
                                     double dzPer50, boolean sprinting, String surface) {
        if (!Double.isFinite(dxPer50) || !Double.isFinite(dzPer50)
                || surface == null || surface.isBlank() || tick < 0) {
            throw new IllegalArgumentException("Complete, finite displacement required");
        }
        Frame current = new Frame(tick, epoch, dxPer50, dzPer50, sprinting, surface);
        eligibleVectors++;
        lastSurface = surface;
        if (previous == null) {
            lastPhase = Phase.WARMUP;
        } else if (tick != previous.tick + 1 || epoch != previous.epoch) {
            discontinuities++;
            lastPhase = Phase.INTERRUPTED;
        } else if (!previous.surface.equals(surface) || surface.equals("UNKNOWN")) {
            surfaceChanges++;
            lastPhase = Phase.SURFACE_CHANGE;
        } else if (previous.sprint != sprinting) {
            sprintChanges++;
            lastPhase = Phase.SPRINT_CHANGE;
        } else {
            double a = previous.speed(), b = current.speed();
            if (!Double.isFinite(a) || !Double.isFinite(b)) {
                // For huge finite vectors hypot can overflow; do not retain.
                discontinuities++;
                lastPhase = Phase.INTERRUPTED;
            } else {
                comparedTransitions++;
                delta.add(Math.abs(b - a));
                if (a <= STILL && b >= MOVING) {
                    starts++;
                    lastPhase = Phase.START;
                } else if (a >= MOVING && b <= STILL) {
                    stops++;
                    lastPhase = Phase.STOP;
                    retention.add(b / a); // Apparent ratio, NOT friction.
                } else {
                    double angle = 0;
                    if (a >= MOVING && b >= MOVING) {
                        double dot = (previous.dx * current.dx + previous.dz * current.dz) / (a * b);
                        if (Double.isFinite(dot)) {
                            angle = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
                        }
                    }
                    if (angle >= TURN_DEGREES) {
                        turns++;
                        angles.add(angle);
                        lastPhase = Phase.TURN;
                    } else if (b - a >= CHANGE) {
                        accelerations++;
                        lastPhase = Phase.ACCELERATE;
                    } else if (a - b >= CHANGE) {
                        decelerations++;
                        lastPhase = Phase.DECELERATE;
                        if (a >= MOVING) retention.add(b / a);
                    } else {
                        steady++;
                        lastPhase = Phase.STEADY;
                    }
                }
            }
        }
        previous = current;
    }

    /** A correction, missing sample, flight, unsupported surface, etc. */
    public synchronized void interrupt() {
        if (previous != null) {
            discontinuities++;
            previous = null;
            lastPhase = Phase.INTERRUPTED;
        }
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(eligibleVectors, comparedTransitions, starts, stops,
                turns, accelerations, decelerations, steady, discontinuities,
                surfaceChanges, sprintChanges, lastPhase, lastSurface,
                delta.snapshot(), angles.snapshot(), retention.snapshot());
    }

    private static final class Ring {
        private final double[] values = new double[CAPACITY];
        private long total;
        private int next, size;

        void add(double value) {
            if (!Double.isFinite(value) || value < 0) return;
            values[next] = value;
            next = (next + 1) % CAPACITY;
            size = Math.min(CAPACITY, size + 1);
            total++;
        }

        Distribution snapshot() {
            if (size == 0) return new Distribution(0, 0, null, null, null);
            double[] sorted = Arrays.copyOf(values, size);
            Arrays.sort(sorted);
            return new Distribution(total, size, nearest(sorted, .50),
                    nearest(sorted, .95), sorted[size - 1]);
        }

        private static double nearest(double[] sorted, double fraction) {
            return sorted[Math.max(0, (int) Math.ceil(sorted.length * fraction) - 1)];
        }
    }
}
