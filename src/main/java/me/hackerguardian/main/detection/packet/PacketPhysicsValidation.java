package me.hackerguardian.main.detection.packet;

import java.util.Arrays;

/**
 * Observation-only validation of a provisional packet reachability envelope.
 *
 * All inputs are genuine, decoded POSITION/POSITION_LOOK triple calculations
 * already admitted by PacketGroundReachability, not Bukkit displacement.
 * A ratio > 1 means "outside provisional research envelope", NEVER cheating.
 * No model coefficients or eligibility gates are changed by this observer.
 *
 * The last 128 normalized residuals per stratum are retained; lifetime
 * nominal/margin/outside counts survive ring eviction. Constant-time inserts.
 */
public final class PacketPhysicsValidation {
    private static final int WINDOW = 128;
    private static final double QUIET_DISPLACEMENT = .015;
    private static final double HEADING_VECTOR_MIN = .075;
    private static final double TURN_ANGLE_DEGREES = 15.0;

    public enum Stratum {
        QUIET, LOW_VECTOR, WALK_STRAIGHT, WALK_TURN,
        SPRINT_STRAIGHT, SPRINT_TURN, POST_SWITCH
    }

    public record Group(long total, long nominal, long margin, long outside,
                        int retained, Double p50Ratio, Double p95Ratio,
                        Double maxRatio, Double maxExcess) {}

    public record Snapshot(Group quiet, Group lowVector, Group walkStraight,
                           Group walkTurn, Group sprintStraight, Group sprintTurn,
                           Group postSwitch) {
        public long total() {
            return quiet.total() + lowVector.total() + walkStraight.total()
                    + walkTurn.total() + sprintStraight.total() + sprintTurn.total()
                    + postSwitch.total();
        }

        public long outside() {
            return quiet.outside() + lowVector.outside()
                    + walkStraight.outside() + walkTurn.outside()
                    + sprintStraight.outside() + sprintTurn.outside()
                    + postSwitch.outside();
        }

        public long margin() {
            return quiet.margin() + lowVector.margin()
                    + walkStraight.margin() + walkTurn.margin()
                    + sprintStraight.margin() + sprintTurn.margin()
                    + postSwitch.margin();
        }
    }

    private final Ring[] groups = Arrays.stream(Stratum.values())
            .map(ignored -> new Ring()).toArray(Ring[]::new);

    /** Only call after the ORIGINAL model accepted and evaluated a packet triple. */
    public void observe(boolean sprint, boolean postSwitch,
                        double prevDx, double prevDz, double dx, double dz,
                        double residual, double nominalAcceleration,
                        double uncertainty) {
        if (!Double.isFinite(prevDx) || !Double.isFinite(prevDz)
                || !Double.isFinite(dx) || !Double.isFinite(dz)
                || !Double.isFinite(residual) || residual < 0
                || !Double.isFinite(nominalAcceleration) || nominalAcceleration <= 0
                || !Double.isFinite(uncertainty) || uncertainty < 0
                || !Double.isFinite(nominalAcceleration + uncertainty)) {
            throw new IllegalArgumentException("Non-finite or invalid research input");
        }
        double radius = nominalAcceleration + uncertainty;
        Stratum group = classify(sprint, postSwitch, prevDx, prevDz, dx, dz);
        groups[group.ordinal()].add(residual, nominalAcceleration, radius);
    }

    public static Stratum classify(boolean sprint, boolean postSwitch,
                                   double prevDx, double prevDz, double dx, double dz) {
        if (postSwitch) return Stratum.POST_SWITCH;
        double previous = Math.hypot(prevDx, prevDz);
        double current = Math.hypot(dx, dz);
        if (!Double.isFinite(previous) || !Double.isFinite(current)) {
            throw new IllegalArgumentException("Invalid packet vectors");
        }
        if (current <= QUIET_DISPLACEMENT) return Stratum.QUIET;
        if (previous < HEADING_VECTOR_MIN || current < HEADING_VECTOR_MIN) {
            return Stratum.LOW_VECTOR;
        }
        double cosine = (prevDx * dx + prevDz * dz) / (previous * current);
        // Clamp floating-point cosine overshoot before acos.
        double degrees = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cosine))));
        if (degrees >= TURN_ANGLE_DEGREES) {
            return sprint ? Stratum.SPRINT_TURN : Stratum.WALK_TURN;
        }
        return sprint ? Stratum.SPRINT_STRAIGHT : Stratum.WALK_STRAIGHT;
    }

    public Snapshot snapshot() {
        Group[] snapshot = new Group[groups.length];
        for (int i = 0; i < groups.length; i++) snapshot[i] = groups[i].snapshot();
        return new Snapshot(snapshot[0], snapshot[1], snapshot[2],
                snapshot[3], snapshot[4], snapshot[5], snapshot[6]);
    }

    private static final class Ring {
        private final double[] ratios = new double[WINDOW];
        private final double[] excess = new double[WINDOW];
        private int size, next;
        private long total, nominal, margin, outside;

        void add(double residual, double nominalAcceleration, double radius) {
            double ratio = residual / radius;
            if (!Double.isFinite(ratio)) throw new IllegalArgumentException("Invalid ratio");
            ratios[next] = ratio;
            excess[next] = Math.max(0, residual - radius);
            next = (next + 1) % WINDOW;
            size = Math.min(WINDOW, size + 1);
            total++;
            if (residual <= nominalAcceleration) nominal++;
            else if (residual <= radius) margin++;
            else outside++;
        }

        Group snapshot() {
            if (size == 0) return new Group(total, nominal, margin, outside,
                    0, null, null, null, null);
            double[] sorted = Arrays.copyOf(ratios, size);
            Arrays.sort(sorted);
            double maxExcess = 0;
            for (int i = 0; i < size; i++) maxExcess = Math.max(maxExcess, excess[i]);
            return new Group(total, nominal, margin, outside, size,
                    rank(sorted, .50), rank(sorted, .95), sorted[size - 1], maxExcess);
        }

        private static double rank(double[] sorted, double percentile) {
            return sorted[Math.max(0, (int) Math.ceil(sorted.length * percentile) - 1)];
        }
    }
}
