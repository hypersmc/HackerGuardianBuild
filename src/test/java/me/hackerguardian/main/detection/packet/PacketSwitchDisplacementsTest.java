package me.hackerguardian.main.detection.packet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PacketSwitchDisplacementsTest {

    @Test
    void nearZeroResidualCanRepresentRealMotionRatherThanStandingStill() {
        var samples = new PacketSwitchDisplacements();
        // Packet displacement .110 is nonzero even though the packet
        // matches the provisional drag prediction exactly (residual 0).
        samples.observe(false, .110, 0.000);
        samples.observe(false, 0.000, 0.000);
        samples.observe(false, 0.001, 0.000);
        samples.observe(false, 0.120, 0.098);
        var walk = samples.snapshot().enteringWalk();
        assertEquals(4, walk.total());
        assertEquals(4, walk.retained());
        assertEquals(2, walk.nearZero());
        assertEquals(1, walk.dragLike());
        assertEquals(.001, walk.p50(), 1e-12);
        assertEquals(.120, walk.p95(), 1e-12);
        assertEquals(.120, walk.max(), 1e-12);
        assertEquals(0, samples.snapshot().enteringSprint().total());
    }

    @Test
    void separateModesAndRollingWindowKeepOnlyLast128Measurements() {
        var samples = new PacketSwitchDisplacements();
        samples.observe(false, 0, 0); // must age out of retained near-zero count
        for (int i = 0; i < 200; i++) {
            samples.observe(false, i / 1000.0, .1);
        }
        samples.observe(true, .280, .127);
        var walk = samples.snapshot().enteringWalk();
        assertEquals(201, walk.total());
        assertEquals(128, walk.retained());
        assertEquals(0, walk.nearZero());
        assertEquals(0, walk.dragLike());
        assertEquals(.135, walk.p50(), 1e-12);
        assertEquals(.193, walk.p95(), 1e-12);
        assertEquals(.199, walk.max(), 1e-12);
        var sprint = samples.snapshot().enteringSprint();
        assertEquals(1, sprint.total());
        assertEquals(.280, sprint.p95(), 1e-12);
        assertEquals(0, sprint.dragLike());
    }

    @Test
    void inputChecksAndSnapshotsAreReadOnly() {
        var samples = new PacketSwitchDisplacements();
        var first = samples.snapshot();
        assertNull(first.enteringWalk().p95());
        assertThrows(IllegalArgumentException.class,
                () -> samples.observe(false, Double.NaN, 0));
        assertThrows(IllegalArgumentException.class,
                () -> samples.observe(false, Double.POSITIVE_INFINITY, 0));
        assertThrows(IllegalArgumentException.class,
                () -> samples.observe(true, -.01, 0));
        assertThrows(IllegalArgumentException.class,
                () -> samples.observe(true, .2, -.01));
        assertEquals(first, samples.snapshot());
        samples.observe(true, .25, .11);
        assertEquals(first, first);
        assertEquals(0, first.enteringSprint().retained());
        assertEquals(samples.snapshot(), samples.snapshot());
    }
}
