package me.hackerguardian.main.detection.packet;

/**
 * Observe-only provenance counters for incoming client-position packet
 * observations relative to the most recently SAMPLED BUKKIT SERVER TICK.
 *
 * A difference of +1 between server tick hints NEVER proves that the two
 * incoming packets represent adjacent client simulation ticks. These
 * measurements explain lost research eligibility; they do not change it.
 *
 * Memory and per-packet processing are constant. Nothing is persisted,
 * emitted as a violation, or used to relax the packet predictor.
 */
public final class PacketTickHintDiagnostics {
    public enum WarmupOrigin { INITIAL, TICK_TIMING, CONTEXT, CORRECTION, OTHER }

    public record Snapshot(long observedHints, long absentHints,
                           long adjacentHints, long repeatedHints,
                           long twoTickGaps, long largerGaps, long reverseHints,
                           long epochResets, long staleServerSamples,
                           long unstableTickSpacing, long actualTickGapRejects,
                           long actualAmbiguousRejects,
                           long warmupInitial, long warmupAfterTiming,
                           long warmupAfterContext, long warmupAfterCorrection,
                           long warmupAfterOther, Long lastHintDelta) {
        public long rawHintPairs() {
            return adjacentHints + repeatedHints + twoTickGaps
                    + largerGaps + reverseHints;
        }
        public long attributedWarmups() {
            return warmupInitial + warmupAfterTiming + warmupAfterContext
                    + warmupAfterCorrection + warmupAfterOther;
        }
    }

    private long observedHints, absentHints, adjacent, repeated;
    private long twoTickGaps, largerGaps, reverse, epochResets;
    private long staleSamples, unstableSpacing, actualTickGaps, actualAmbiguous;
    private long warmupInitial, warmupTiming, warmupContext, warmupCorrection, warmupOther;
    private Long previousHint, lastDelta;
    private long lastEpoch;
    private WarmupOrigin origin = WarmupOrigin.INITIAL;

    /** Called for every incoming decoded-position packet research invocation. */
    public void observeContext(PacketGroundReachability.Context context) {
        if (context == null || context.serverTick() <= 0) {
            absentHints++;
            previousHint = null;
            lastDelta = null;
            return;
        }
        observedHints++;
        if (!context.serverTickStable()) unstableSpacing++;
        long age = context.serverSampleAgeNanos();
        if (age < 0 || age > 125_000_000L) staleSamples++;

        if (previousHint != null && context.correctionEpoch() != lastEpoch) {
            epochResets++;
            previousHint = null;
        }
        long tick = context.serverTick();
        if (previousHint != null) {
            long delta = tick - previousHint;
            lastDelta = delta;
            if (delta == 1) adjacent++;
            else if (delta == 0) repeated++;
            else if (delta == 2) twoTickGaps++;
            else if (delta > 2) largerGaps++;
            else reverse++;
        } else {
            lastDelta = null;
        }
        previousHint = tick;
        lastEpoch = context.correctionEpoch();
    }

    /** Called exactly once with the model's existing outcome per position packet. */
    public void observeOutcome(PacketGroundReachability.Status status) {
        switch (status) {
            case WARMUP -> {
                switch (origin) {
                    case INITIAL -> warmupInitial++;
                    case TICK_TIMING -> warmupTiming++;
                    case CONTEXT -> warmupContext++;
                    case CORRECTION -> warmupCorrection++;
                    case OTHER -> warmupOther++;
                }
            }
            case TICK_GAP -> {
                actualTickGaps++;
                origin = WarmupOrigin.TICK_TIMING;
            }
            case AMBIGUOUS_TICK -> {
                actualAmbiguous++;
                origin = WarmupOrigin.TICK_TIMING;
            }
            case STALE_SERVER_SAMPLE -> origin = WarmupOrigin.TICK_TIMING;
            case CONTEXT_CHANGED, UNSUPPORTED_CONTEXT -> origin = WarmupOrigin.CONTEXT;
            case RECENT_CORRECTION -> origin = WarmupOrigin.CORRECTION;
            case INVALID_GROUND_CLAIM, NO_SERVER_SAMPLE, DISABLED_VERSION ->
                    origin = WarmupOrigin.OTHER;
            case CONSERVATIVE_REACHABLE, OUTSIDE_RESEARCH_ENVELOPE -> {
                // No interruption: subsequent normal samples need no warmup.
            }
        }
    }

    /** Explicit correction fence. Do not compare hints across this event. */
    public void correction() {
        previousHint = null;
        lastDelta = null;
        origin = WarmupOrigin.CORRECTION;
    }

    public Snapshot snapshot() {
        return new Snapshot(observedHints, absentHints, adjacent, repeated,
                twoTickGaps, largerGaps, reverse, epochResets, staleSamples,
                unstableSpacing, actualTickGaps, actualAmbiguous,
                warmupInitial, warmupTiming, warmupContext, warmupCorrection,
                warmupOther, lastDelta);
    }
}
