package me.hackerguardian.main.detection.packet;

/**
 * Observe-only, Bukkit SERVER-SAMPLED idle/moving transitions.
 *
 * This is intentionally separate from client-claim-gated motion dynamics:
 * Minecraft may stop sending position claims when idle, yet the server
 * continues to have a position each tick. A server-side stop is NOT proof
 * that the client released a movement key, and missing packets are not proof
 * of a cheat. The optional recent-packet bit is just a provenance hint.
 *
 * No finding, alarm, report, ML label, kick, or setback is emitted here.
 * Constant memory and O(1) updates.
 */
public final class GroundIdleTransitions {
    private static final long MIN_TICK_NANOS = 25_000_000L;
    private static final long MAX_TICK_NANOS = 125_000_000L;
    private static final double IDLE_MAX = 0.015;  // blocks per observed 50 ms
    private static final double MOVING_MIN = 0.075;
    private static final double MAX_VERTICAL_CHANGE = 0.03;
    private static final int REQUIRED_CONSECUTIVE_SAMPLES = 2;

    public enum State { UNKNOWN, IDLE, MOVING }
    public enum Status { WARMUP, VALID, UNKNOWN_CONTEXT, UNSTABLE_TICK,
                         DISCONTINUITY, TRANSITION_PENDING }

    public record Snapshot(long capturedTicks, long eligibleIntervals,
                           long noDecisionTicks, long serverStarts,
                           long serverStops, long startsWithRecentClient,
                           long stopsWithoutRecentClient, long interruptedChains,
                           State state, Status lastStatus, String lastBlock,
                           boolean lastHadRecentClient, Double lastBlocksPer50ms) {}

    private record Frame(long tick, long nanos, long epoch,
                         MovementTickTimeline.TickInput data) {}

    private Frame previous;
    private State stable = State.UNKNOWN;
    private State pending = State.UNKNOWN;
    private int pendingCount;
    private long captured, eligible, noDecision, starts, stops;
    private long startsRecentClient, stopsNoRecentClient, interrupted;
    private Status lastStatus = Status.WARMUP;
    private String lastBlock = "UNKNOWN";
    private boolean lastRecentClient;
    private Double lastSpeed;

    /**
     * Called once on the Bukkit sampling thread with already-copied immutable
     * input. The settled flag includes pending-teleport protection.
     */
    public synchronized void sample(long tick, long observedNanos, long epoch,
                                    MovementTickTimeline.TickInput input,
                                    boolean settled, boolean recentClientPosition) {
        if (input == null) throw new IllegalArgumentException("server tick input required");
        captured++;
        lastRecentClient = recentClientPosition;
        lastBlock = input.probeContext().groundMaterial();
        lastSpeed = null;
        Status status;
        if (!settled || !eligibleFrame(input)) {
            status = Status.UNKNOWN_CONTEXT;
            resetChain();
        } else if (previous == null) {
            status = Status.WARMUP;
        } else if (previous.tick() + 1 != tick || previous.epoch() != epoch
                || !previous.data().worldKey().equals(input.worldKey())
                || !previous.data().probeContext().groundMaterial().equals(lastBlock)
                || previous.data().probeContext().sprinting() != input.probeContext().sprinting()
                || !eligibleFrame(previous.data())) {
            status = Status.DISCONTINUITY;
            resetChain();
        } else {
            long nanos = observedNanos - previous.nanos();
            if (nanos < MIN_TICK_NANOS || nanos > MAX_TICK_NANOS) {
                status = Status.UNSTABLE_TICK;
                resetChain();
            } else {
                var a = previous.data().position();
                var b = input.position();
                double vertical = Math.abs(b.y() - a.y());
                double speed = Math.hypot(b.x() - a.x(), b.z() - a.z())
                        * (50_000_000.0 / nanos);
                if (!Double.isFinite(speed) || vertical > MAX_VERTICAL_CHANGE) {
                    status = Status.UNKNOWN_CONTEXT;
                    resetChain();
                } else {
                    eligible++;
                    lastSpeed = speed;
                    State observed = speed <= IDLE_MAX ? State.IDLE
                            : speed >= MOVING_MIN ? State.MOVING : State.UNKNOWN;
                    if (observed == State.UNKNOWN) {
                        pending = State.UNKNOWN;
                        pendingCount = 0;
                        status = Status.TRANSITION_PENDING;
                    } else {
                        if (pending != observed) {
                            pending = observed;
                            pendingCount = 1;
                        } else {
                            pendingCount++;
                        }
                        if (pendingCount >= REQUIRED_CONSECUTIVE_SAMPLES && stable != observed) {
                            if (stable == State.IDLE && observed == State.MOVING) {
                                starts++;
                                if (recentClientPosition) startsRecentClient++;
                            } else if (stable == State.MOVING && observed == State.IDLE) {
                                stops++;
                                if (!recentClientPosition) stopsNoRecentClient++;
                            }
                            stable = observed;
                        }
                        status = stable == observed ? Status.VALID : Status.TRANSITION_PENDING;
                    }
                }
            }
        }
        if (status != Status.VALID) noDecision++;
        lastStatus = status;
        // A rejected frame is never used to bridge an uncertain interval.
        previous = (status == Status.UNKNOWN_CONTEXT
                || status == Status.UNSTABLE_TICK
                || status == Status.DISCONTINUITY) ? null
                : new Frame(tick, observedNanos, epoch, input);
    }

    private static boolean eligibleFrame(MovementTickTimeline.TickInput input) {
        var flags = input.flags();
        var env = input.environment();
        var ctx = input.probeContext();
        if (!flags.serverOnGround() || flags.flying() || flags.gliding()
                || flags.swimming() || flags.inVehicle() || flags.creativeOrSpectator()) return false;
        if (!ctx.verified() || !ctx.ordinaryFlatSurface()
                || !ctx.noActiveEffects() || !ctx.defaultWalkSpeed()
                || ctx.groundMaterial().equals("UNKNOWN")) return false;
        return env.feetPassable() && env.headPassable()
                && !env.belowPassable() && !env.feetLiquid() && !env.belowLiquid();
    }

    private void resetChain() {
        if (previous != null) interrupted++;
        previous = null;
        stable = State.UNKNOWN;
        pending = State.UNKNOWN;
        pendingCount = 0;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(captured, eligible, noDecision, starts, stops,
                startsRecentClient, stopsNoRecentClient, interrupted,
                stable, lastStatus, lastBlock, lastRecentClient, lastSpeed);
    }
}
