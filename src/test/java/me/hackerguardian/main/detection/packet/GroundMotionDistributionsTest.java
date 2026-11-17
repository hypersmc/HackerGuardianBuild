package me.hackerguardian.main.detection.packet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GroundMotionDistributionsTest {

    @Test
    void emptyGroupsDoNotFabricateStatistics() {
        var snapshot = new GroundMotionDistributions().snapshot();
        assertEquals(0, snapshot.walking().retained());
        assertEquals(0, snapshot.sprinting().retained());
        assertFalse(snapshot.walking().available());
        assertNull(snapshot.walking().p50());
        assertNull(snapshot.sprinting().p95());
        assertNull(snapshot.sprinting().max());
    }

    @Test
    void walkAndSprintHaveIndependentPercentilesAndCounts() {
        var data = new GroundMotionDistributions();
        for (double v : new double[]{0.1, 0.2, 0.3, 0.4}) data.observe(false, v);
        data.observe(true, 0.5);
        data.observe(true, 0.8);
        var result = data.snapshot();

        var walk = result.walking();
        assertEquals(4, walk.totalSamples());
        assertEquals(4, walk.retained());
        assertEquals(0.25, walk.mean(), 1e-10);
        assertEquals(0.2, walk.p50(), 1e-10);
        assertEquals(0.4, walk.p95(), 1e-10);
        assertEquals(0.4, walk.max(), 1e-10);

        var sprint = result.sprinting();
        assertEquals(2, sprint.totalSamples());
        assertEquals(2, sprint.retained());
        assertEquals(0.65, sprint.mean(), 1e-10);
        assertEquals(0.5, sprint.p50(), 1e-10);
        assertEquals(0.8, sprint.p95(), 1e-10);
    }

    @Test
    void retainsOnlyLast128PerModeInsteadOfGrowingWithSessionLength() {
        var data = new GroundMotionDistributions();
        for (int i = 0; i < 200; i++) data.observe(false, i);
        for (int i = 0; i < 300; i++) data.observe(true, i / 10.0);

        var walk = data.snapshot().walking();
        assertEquals(200, walk.totalSamples());
        assertEquals(128, walk.retained());
        assertEquals(135.5, walk.mean(), 1e-10);
        assertEquals(135, walk.p50(), 1e-10);
        assertEquals(193, walk.p95(), 1e-10);
        assertEquals(199, walk.max(), 1e-10);

        var sprint = data.snapshot().sprinting();
        assertEquals(300, sprint.totalSamples());
        assertEquals(128, sprint.retained());
        assertEquals(29.9, sprint.max(), 1e-10);
        assertEquals(128, sprint.retained());
    }

    @Test
    void invalidOrNegativeDisplacementsCannotPoisonStatistics() {
        var data = new GroundMotionDistributions();
        assertThrows(IllegalArgumentException.class, () -> data.observe(false, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> data.observe(true, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> data.observe(false, -1.0));
        data.observe(false, 0);
        assertEquals(1, data.snapshot().walking().retained());
        assertEquals(0.0, data.snapshot().walking().p95(), 0);
        assertEquals(0, data.snapshot().sprinting().retained());
    }

    @Test
    void snapshotDoesNotChangeSamplesOrAgeData() {
        var data = new GroundMotionDistributions();
        data.observe(false, 0.2);
        var snapshot = data.snapshot();
        for (int i = 0; i < 200; i++) assertEquals(snapshot, data.snapshot());
        data.observe(false, 0.4);
        assertEquals(1, snapshot.walking().totalSamples());
        assertEquals(2, data.snapshot().walking().totalSamples());
    }
}
