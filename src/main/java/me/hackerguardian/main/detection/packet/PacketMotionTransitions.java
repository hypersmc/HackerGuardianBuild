package me.hackerguardian.main.detection.packet;

/**
 * Read-only, bounded research into client-PACKET motion around observed
 * Bukkit sprint-state changes. A server-side sprint-state change is NOT a
 * decoded client sprint-intent packet and does NOT authenticate key presses.
 *
 * The following six scheduler-tick hints form a deliberately conservative
 * "transition" observation window. They are not six verified client ticks.
 * The first two packet claims after a switch re-warm the reachability model.
 *
 * Acceleration/deceleration/turn phases are descriptive; they are not
 * evidence-grade physics, client input, or a cheating classification.
 * No Bukkit/World/Player access here. O(1) cost per modeled packet.
 */
public final class PacketMotionTransitions {
    private static final long TRANSITION_TICK_HINTS = 6L;
    private static final double SPEED_CHANGE = 0.025;
    private static final double TURN_DEGREES = 35.0;
    private static final double MIN_TURN_SPEED = 0.075;

    public enum Phase { ACCELERATING, DECELERATING, TURNING, STEADY, NONE }

    public record Snapshot(long walkToSprint, long sprintToWalk,
                           long taggedWalk, long taggedSprint,
                           long accelerating, long decelerating, long turning,
                           long steady, long taggedOutside,
                           Phase lastPhase, boolean lastWasTransition,
                           PacketReachabilityResiduals.Snapshot switchResiduals,
                           PacketSwitchDisplacements.Snapshot switchDisplacements) {}

    private final PacketReachabilityResiduals switchResiduals = new PacketReachabilityResiduals();
    private final PacketSwitchDisplacements switchDisplacements = new PacketSwitchDisplacements();
    private long walkToSprint, sprintToWalk, taggedWalk, taggedSprint;
    private long accelerating, decelerating, turning, steady, taggedOutside;
    private long transitionEpoch = Long.MIN_VALUE;
    private long untilTick = Long.MIN_VALUE;
    private boolean targetSprint;
    private Phase lastPhase = Phase.NONE;
    private boolean lastWasTransition;

    /**
     * Called only for a supported, contiguous packet claim pair with the
     * same world, correction epoch and ground material but differing
     * SERVER-OBSERVED sprint flags. No physical triple crosses this event.
     */
    public synchronized void sprintChange(long tickHint, long epoch,
                                          boolean fromSprint, boolean toSprint) {
        if (fromSprint == toSprint) return;
        if (toSprint) walkToSprint++;
        else sprintToWalk++;
        targetSprint = toSprint;
        transitionEpoch = epoch;
        untilTick = tickHint + TRANSITION_TICK_HINTS;
        lastPhase = Phase.NONE;
        lastWasTransition = false;
    }

    /**
     * Returns true if the modeled triple is attributed to the post-switch
     * window, and must NOT be included in the steady walk/sprint profile.
     */
    public synchronized boolean observe(long tickHint, long epoch, boolean sprint,
                                        double prevDx, double prevDz,
                                        double currentDx, double currentDz,
                                        double residual, double excess) {
        if (!Double.isFinite(prevDx) || !Double.isFinite(prevDz)
                || !Double.isFinite(currentDx) || !Double.isFinite(currentDz)
                || !Double.isFinite(residual) || !Double.isFinite(excess)
                || residual < 0 || excess < 0) {
            throw new IllegalArgumentException("Non-finite packet motion");
        }
        double first = Math.hypot(prevDx, prevDz);
        double second = Math.hypot(currentDx, currentDz);
        if (!Double.isFinite(first) || !Double.isFinite(second)) {
            throw new IllegalArgumentException("Unbounded packet displacement");
        }

        boolean tagged = transitionEpoch == epoch && targetSprint == sprint
                && tickHint > 0 && tickHint <= untilTick;
        if (tagged) {
            // Both rings record the same accepted client packet triple:
            // residual is required unknown input, displacement is actual XZ
            // distance from decoded client packets. Zero residual alone
            // must never be interpreted as the player standing still.
            switchResiduals.observe(sprint, residual, excess);
            switchDisplacements.observe(sprint, second, residual);
            if (sprint) taggedSprint++;
            else taggedWalk++;
            if (excess > 0) taggedOutside++;
        }

        double angle = 0;
        if (first >= MIN_TURN_SPEED && second >= MIN_TURN_SPEED) {
            double normalized = (prevDx * currentDx + prevDz * currentDz) / (first * second);
            if (Double.isFinite(normalized)) {
                angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, normalized))));
            }
        }
        if (angle >= TURN_DEGREES) {
            turning++;
            lastPhase = Phase.TURNING;
        } else if (second - first >= SPEED_CHANGE) {
            accelerating++;
            lastPhase = Phase.ACCELERATING;
        } else if (first - second >= SPEED_CHANGE) {
            decelerating++;
            lastPhase = Phase.DECELERATING;
        } else {
            steady++;
            lastPhase = Phase.STEADY;
        }
        lastWasTransition = tagged;
        return tagged;
    }

    /** Any invalid context or timing gap aborts the active tagging window. */
    public synchronized void interruption() {
        untilTick = Long.MIN_VALUE;
        transitionEpoch = Long.MIN_VALUE;
        lastPhase = Phase.NONE;
        lastWasTransition = false;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(walkToSprint, sprintToWalk, taggedWalk, taggedSprint,
                accelerating, decelerating, turning, steady, taggedOutside,
                lastPhase, lastWasTransition, switchResiduals.snapshot(),
                switchDisplacements.snapshot());
    }
}
