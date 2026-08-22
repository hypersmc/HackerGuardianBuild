package me.hackerguardian.main.detection.deterministic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningTimingTest {

    @Test
    void computesConservativeMinimumFromProgressPerTick() {
        assertEquals(450L, MiningTiming.conservativeMinimumBreakMs(0.10));
        assertEquals(200L, MiningTiming.conservativeMinimumBreakMs(0.20));
        assertEquals(0L, MiningTiming.conservativeMinimumBreakMs(1.0));
        assertEquals(0L, MiningTiming.conservativeMinimumBreakMs(0.0));
    }

    @Test
    void flagsOnlyLargeTimingDeficits() {
        long vanilla = MiningTiming.conservativeMinimumBreakMs(0.10);
        assertTrue(MiningTiming.isTooFast(100L, vanilla, 0.55, 75L));
        assertFalse(MiningTiming.isTooFast(180L, vanilla, 0.55, 75L));
        assertFalse(MiningTiming.isTooFast(450L, vanilla, 0.55, 75L));
        assertTrue(MiningTiming.severity(100L, vanilla, 0.55, 75L) >= 0.65);
    }
}
