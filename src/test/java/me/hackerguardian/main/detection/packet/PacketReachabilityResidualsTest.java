package me.hackerguardian.main.detection.packet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PacketReachabilityResidualsTest {
    @Test
    void emptyBucketsDoNotInventErrorsOrPercentiles() {
        var stats = new PacketReachabilityResiduals().snapshot();
        assertEquals(0, stats.walking().total());
        assertEquals(0, stats.sprinting().retained());
        assertFalse(stats.walking().available());
        assertNull(stats.walking().p50());
        assertNull(stats.walking().maxExcess());
    }

    @Test
    void groupsHaveSeparateNearestRankResidualPercentilesAndOutsideCounts() {
        var residuals = new PacketReachabilityResiduals();
        for (double value : new double[]{.10, .20, .30, .40}) {
            residuals.observe(false, value, value == .40 ? .10 : 0);
        }
        residuals.observe(true, .09, 0);
        residuals.observe(true, .18, .02);
        var snapshot = residuals.snapshot();
        var walk = snapshot.walking();
        assertEquals(4, walk.total());
        assertEquals(4, walk.retained());
        assertEquals(1, walk.outside());
        assertEquals(.20, walk.p50(), 1e-12);
        assertEquals(.40, walk.p95(), 1e-12);
        assertEquals(.40, walk.max(), 1e-12);
        assertEquals(.10, walk.maxExcess(), 1e-12);
        var sprint = snapshot.sprinting();
        assertEquals(2, sprint.total());
        assertEquals(1, sprint.outside());
        assertEquals(.09, sprint.p50(), 1e-12);
        assertEquals(.18, sprint.p95(), 1e-12);
        assertEquals(.18, sprint.max(), 1e-12);
        assertEquals(.02, sprint.maxExcess(), 1e-12);
        assertEquals(snapshot, residuals.snapshot(), "Read-only stats cannot change counters");
    }

    @Test
    void boundedRingEvictsOldResidualsButKeepsLifetimeTotalAndOutsideCount() {
        var residuals = new PacketReachabilityResiduals();
        residuals.observe(false, .5, .3);
        for (int i = 0; i < 200; i++) residuals.observe(false, i / 1000.0, 0);

        var snapshot = residuals.snapshot().walking();
        assertEquals(201, snapshot.total());
        assertEquals(128, snapshot.retained());
        assertEquals(1, snapshot.outside());
        assertEquals(.136, snapshot.p50(), 1e-12);  // last 128: .072 ... .199
        assertEquals(.193, snapshot.p95(), 1e-12);
        assertEquals(.199, snapshot.max(), 1e-12);
        assertEquals(0.0, snapshot.maxExcess(), 1e-12,
                "Peak excess describes retained window, not an evicted historical point");
    }

    @Test
    void rejectsPoisonedMeasurementsWithoutMutatingAnyStats() {
        var residuals = new PacketReachabilityResiduals();
        assertThrows(IllegalArgumentException.class,
                () -> residuals.observe(false, Double.NaN, 0));
        assertThrows(IllegalArgumentException.class,
                () -> residuals.observe(true, Double.POSITIVE_INFINITY, 0));
        assertThrows(IllegalArgumentException.class,
                () -> residuals.observe(false, -.01, 0));
        assertThrows(IllegalArgumentException.class,
                () -> residuals.observe(true, .1, -.01));
        assertThrows(IllegalArgumentException.class,
                () -> residuals.observe(true, .1, .2));
        assertEquals(0, residuals.snapshot().walking().total());
        assertEquals(0, residuals.snapshot().sprinting().total());
        residuals.observe(false, 0, 0);
        assertEquals(0.0, residuals.snapshot().walking().p95(), 1e-12);
    }
}
