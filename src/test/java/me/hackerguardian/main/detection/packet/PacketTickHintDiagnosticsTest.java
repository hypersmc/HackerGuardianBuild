package me.hackerguardian.main.detection.packet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PacketTickHintDiagnosticsTest {

    private static PacketGroundReachability.Context hint(long tick, long epoch) {
        // No Bukkit calls: this is an immutable server-tick hint fixture.
        return new PacketGroundReachability.Context(tick, epoch,
                true, false, null, true, 10_000_000L);
    }

    @Test
    void rawHintsDistinguishOneTickRepeatSkippedAndReversedHints() {
        var research = new PacketTickHintDiagnostics();
        for (int tick : new int[]{10, 11, 11, 13, 17, 16}) {
            research.observeContext(hint(tick, 0));
        }
        var s = research.snapshot();
        assertEquals(6, s.observedHints());
        assertEquals(5, s.rawHintPairs());
        assertEquals(1, s.adjacentHints());
        assertEquals(1, s.repeatedHints());
        assertEquals(1, s.twoTickGaps());
        assertEquals(1, s.largerGaps());
        assertEquals(1, s.reverseHints());
        assertEquals(-1L, s.lastHintDelta());
        assertEquals(s, research.snapshot());
    }

    @Test
    void contextSampleTimingAndMissingHintsRemainObservationsOnly() {
        var research = new PacketTickHintDiagnostics();
        research.observeContext(null);
        research.observeContext(hint(5, 0));
        research.observeContext(new PacketGroundReachability.Context(
                6, 0, true, false, null, false, 200_000_000L));
        research.observeContext(new PacketGroundReachability.Context(
                7, 0, true, false, null, true, -1));
        var s = research.snapshot();
        assertEquals(1, s.absentHints());
        assertEquals(3, s.observedHints());
        assertEquals(2, s.adjacentHints());
        assertEquals(1, s.unstableTickSpacing());
        assertEquals(2, s.staleServerSamples());
        assertEquals(0, s.actualTickGapRejects(),
                "Raw gaps and predictor rejection outcomes must not be conflated");
    }

    @Test
    void correctionAndEpochChangesDoNotCreateInventedCrossBoundaryHintDeltas() {
        var research = new PacketTickHintDiagnostics();
        research.observeContext(hint(1, 0));
        research.observeContext(hint(2, 0));
        research.observeContext(hint(30, 1)); // unknown correction between callbacks
        research.observeContext(hint(31, 1));
        research.correction(); // explicit teleport/velocity guard
        research.observeContext(hint(100, 2));
        var s = research.snapshot();
        assertEquals(2, s.adjacentHints());
        assertEquals(1, s.epochResets());
        assertEquals(0, s.largerGaps(),
                "Do not report a correction as a missed client simulation tick");
        assertNull(s.lastHintDelta());
    }

    @Test
    void warmupCausesTrackTheLatestGateRatherThanInventingTimingFailures() {
        var research = new PacketTickHintDiagnostics();
        research.observeOutcome(PacketGroundReachability.Status.WARMUP);
        research.observeOutcome(PacketGroundReachability.Status.WARMUP);
        research.observeOutcome(PacketGroundReachability.Status.TICK_GAP);
        research.observeOutcome(PacketGroundReachability.Status.WARMUP);
        research.observeOutcome(PacketGroundReachability.Status.AMBIGUOUS_TICK);
        research.observeOutcome(PacketGroundReachability.Status.WARMUP);
        research.observeOutcome(PacketGroundReachability.Status.WARMUP);
        research.observeOutcome(PacketGroundReachability.Status.CONTEXT_CHANGED);
        research.observeOutcome(PacketGroundReachability.Status.WARMUP);
        research.correction();
        research.observeOutcome(PacketGroundReachability.Status.WARMUP);
        research.observeOutcome(PacketGroundReachability.Status.INVALID_GROUND_CLAIM);
        research.observeOutcome(PacketGroundReachability.Status.WARMUP);
        var s = research.snapshot();
        assertEquals(2, s.warmupInitial());
        assertEquals(3, s.warmupAfterTiming());
        assertEquals(1, s.warmupAfterContext());
        assertEquals(1, s.warmupAfterCorrection());
        assertEquals(1, s.warmupAfterOther());
        assertEquals(8, s.attributedWarmups());
        assertEquals(1, s.actualTickGapRejects());
        assertEquals(1, s.actualAmbiguousRejects());
    }
}
