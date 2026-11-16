package me.hackerguardian.main.detection.packet;

/**
 * Experimental, observe-only horizontal ground-motion envelope.
 *
 * NOT a vanilla physics simulation, cheat check, or finding generator.
 * The loose threshold merely identifies rare samples worth evaluating with
 * a future version-specific predictor. Do not use for punishments or labels.
 *
 * Only consumes immutable Bukkit thread server tick samples. Correction
 * epochs and client observation availability come from MovementTickTimeline.
 */
public final class MovementGroundProbe {

    // Research threshold, deliberately far above normal walking/sprinting.
    // This is NOT an authoritative Minecraft maximum movement speed.
    private static final double RESEARCH_ENVELOPE_BLOCKS_PER_50MS = 1.20;
    private static final long MIN_TICK_NS = 25_000_000L;
    private static final long MAX_TICK_NS = 125_000_000L;

    public enum Result {
        WARMUP, NO_CLIENT_DATA, CORRECTION, UNKNOWN_PHYSICS,
        SPECIAL_MOVEMENT, UNSAFE_TERRAIN, TICK_GAP, UNSTABLE_TICK,
        WITHIN_RESEARCH_ENVELOPE, RESEARCH_OUTLIER
    }

    public record Snapshot(long tickSamples, long evaluated, long noDecision,
                           long researchOutliers, long consecutiveOutlierSamples,
                           Result lastResult, long lastTick,
                           Double horizontalDistance, Double envelope,
                           long lastResearchOutlierTick, long unsafeTerrainSamples,
                           long noClientDataSamples, long specialMovementSamples,
                           String lastSurfaceReason) {}

    private record Previous(long tick, long nanos, long epoch,
                            MovementTickTimeline.TickInput input) {}

    private Previous previous;
    private long samples, evaluated, noDecision, outliers, consecutive;
    private Result lastResult = Result.WARMUP;
    private long lastTick = -1, lastOutlierTick = -1;
    private Double lastDistance, lastEnvelope;
    private long unsafeTerrainSamples, noClientDataSamples, specialMovementSamples;
    private String lastSurfaceReason = "not observed";

    /**
     * Called under a session lock, once per server tick when enabled.
     * A retained "outlier" is research telemetry only. No enforcement path.
     */
    public synchronized void sample(long tick, long nanos, long epoch,
                                    MovementTickTimeline.TickInput input,
                                    boolean settled, boolean hasRecentClient) {
        if (input == null) throw new IllegalArgumentException("input required");
        samples++;
        lastTick = tick;
        lastDistance = null;
        lastEnvelope = null;
        Result next;

        if (previous == null) {
            next = Result.WARMUP;
        } else if (!settled || previous.epoch() != epoch
                || !previous.input().worldKey().equals(input.worldKey())) {
            next = Result.CORRECTION;
        } else if (!hasRecentClient) {
            next = Result.NO_CLIENT_DATA;
        } else if (tick != previous.tick() + 1) {
            next = Result.TICK_GAP;
        } else {
            long elapsed = nanos - previous.nanos();
            if (elapsed < MIN_TICK_NS || elapsed > MAX_TICK_NS) {
                next = Result.UNSTABLE_TICK;
            } else if (!eligibleModes(previous.input()) || !eligibleModes(input)) {
                next = Result.SPECIAL_MOVEMENT;
            } else if (!eligibleSurface(previous.input()) || !eligibleSurface(input)) {
                next = Result.UNSAFE_TERRAIN;
            } else if (!eligiblePhysics(previous.input()) || !eligiblePhysics(input)) {
                next = Result.UNKNOWN_PHYSICS;
            } else {
                var a = previous.input().position();
                var b = input.position();
                double dy = Math.abs(b.y() - a.y());
                double horizontal = Math.hypot(b.x() - a.x(), b.z() - a.z());
                var velocity = input.velocity();
                var oldVelocity = previous.input().velocity();

                // Vertical steps/jumps, recent external motion and other
                // complex circumstances must NOT be evaluated here.
                if (dy > 0.03 || Math.hypot(velocity.x(), velocity.z()) > 0.7
                        || Math.hypot(oldVelocity.x(), oldVelocity.z()) > 0.7
                        || Math.abs(velocity.y()) > 0.5 || Math.abs(oldVelocity.y()) > 0.5) {
                    next = Result.SPECIAL_MOVEMENT;
                } else if (!Double.isFinite(horizontal)) {
                    next = Result.UNKNOWN_PHYSICS;
                } else {
                    double envelope = RESEARCH_ENVELOPE_BLOCKS_PER_50MS
                            * (elapsed / 50_000_000.0);
                    lastDistance = horizontal;
                    lastEnvelope = envelope;
                    evaluated++;
                    if (horizontal > envelope) {
                        // Counting an outlier never creates a finding. Requiring
                        // consecutive outliers is a hint, not a guilt threshold.
                        consecutive++;
                        outliers++;
                        lastOutlierTick = tick;
                        next = Result.RESEARCH_OUTLIER;
                    } else {
                        consecutive = 0;
                        next = Result.WITHIN_RESEARCH_ENVELOPE;
                    }
                }
            }
        }

        if (next != Result.WITHIN_RESEARCH_ENVELOPE
                && next != Result.RESEARCH_OUTLIER) {
            consecutive = 0;
            noDecision++;
        }
        if (next == Result.UNSAFE_TERRAIN) {
            unsafeTerrainSamples++;
            lastSurfaceReason = !eligibleSurface(previous.input())
                    ? surfaceReason(previous.input())
                    : surfaceReason(input);
        } else if (next == Result.NO_CLIENT_DATA) {
            noClientDataSamples++;
        } else if (next == Result.SPECIAL_MOVEMENT) {
            specialMovementSamples++;
        }
        lastResult = next;
        previous = new Previous(tick, nanos, epoch, input);
    }

    private static String surfaceReason(MovementTickTimeline.TickInput input) {
        var e = input.environment();
        var p = input.probeContext();
        if (!p.verified()) return "profile unverified";
        if (!e.feetPassable()) return "feet blocked";
        if (!e.headPassable()) return "head blocked";
        if (!p.ordinaryFlatSurface()) {
            return p.surfaceReason().isBlank() ? "ordinary surface not verified"
                    : p.surfaceReason();
        }
        if (e.belowPassable()) return "below passable";
        if (e.feetLiquid() || e.belowLiquid()) return "liquid nearby";
        return "terrain unknown";
    }

    private static boolean eligibleModes(MovementTickTimeline.TickInput input) {
        var f = input.flags();
        return f.serverOnGround() && !f.flying() && !f.gliding()
                && !f.swimming() && !f.inVehicle() && !f.creativeOrSpectator();
    }

    private static boolean eligibleSurface(MovementTickTimeline.TickInput input) {
        var e = input.environment();
        var context = input.probeContext();
        return context.verified() && context.ordinaryFlatSurface()
                && e.feetPassable() && e.headPassable() && !e.belowPassable()
                && !e.feetLiquid() && !e.belowLiquid();
    }

    private static boolean eligiblePhysics(MovementTickTimeline.TickInput input) {
        var context = input.probeContext();
        return context.noActiveEffects() && context.defaultWalkSpeed();
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(samples, evaluated, noDecision, outliers, consecutive,
                lastResult, lastTick, lastDistance, lastEnvelope, lastOutlierTick,
                unsafeTerrainSamples, noClientDataSamples, specialMovementSamples,
                lastSurfaceReason);
    }
}
