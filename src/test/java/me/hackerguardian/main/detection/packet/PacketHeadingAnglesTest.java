package me.hackerguardian.main.detection.packet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PacketHeadingAnglesTest {

    private static Double angle(PacketHeadingAngles angles, double degrees) {
        double radians = Math.toRadians(degrees);
        return angles.observe(.2, 0, .2 * Math.cos(radians), .2 * Math.sin(radians));
    }

    @Test
    void measuresRealDisplacementHeadingsIncludingSharpTurnsButNotCameraYaw() {
        var angles = new PacketHeadingAngles();
        double[] expected = {0, 10, 20, 40, 90, 180};
        for (double direction : expected) {
            assertEquals(direction, angle(angles, direction), 1e-9);
        }
        var stats = angles.snapshot();
        assertEquals(6, stats.measured());
        assertEquals(6, stats.retained());
        assertEquals(0, stats.lowMotion());
        assertEquals(5, stats.over5());
        assertEquals(4, stats.over15());
        assertEquals(3, stats.over35());
        assertEquals(20, stats.p50(), 1e-9);
        assertEquals(180, stats.p95(), 1e-9);
        assertEquals(180, stats.max(), 1e-9);
        assertEquals(stats, angles.snapshot(), "Admin snapshots must not alter packet counters");
    }

    @Test
    void lowSpeedOrZeroDisplacementCannotInventAnAngle() {
        var angles = new PacketHeadingAngles();
        assertNull(angles.observe(0, 0, .2, 0));
        assertNull(angles.observe(.2, 0, 0, 0));
        assertNull(angles.observe(.0749, 0, 0, .3));
        assertEquals(3, angles.snapshot().lowMotion());
        assertEquals(0, angles.snapshot().measured());
        assertNull(angles.snapshot().p95());
        assertEquals(0, angles.snapshot().over35());

        // Just above the directional minimum, a real 90 degree packet
        // movement change counts even when the player does not rotate
        // their camera (no yaw values appear in this class).
        assertEquals(90, angles.observe(.08, 0, 0, .08), 1e-9);
        assertEquals(1, angles.snapshot().measured());
        assertEquals(1, angles.snapshot().over35());
    }

    @Test
    void ringsRetainOnlyLast128AnglesButLifetimeThresholdCountersRemain() {
        var angles = new PacketHeadingAngles();
        for (int i = 0; i < 72; i++) angle(angles, 90);
        for (int i = 0; i < 200; i++) angle(angles, 0);
        var stats = angles.snapshot();
        assertEquals(272, stats.measured());
        assertEquals(128, stats.retained());
        assertEquals(72, stats.over35(), "Lifetime category count must remain cumulative");
        assertEquals(72, stats.over15());
        assertEquals(72, stats.over5());
        assertEquals(0, stats.p50(), 1e-9);
        assertEquals(0, stats.p95(), 1e-9);
        assertEquals(0, stats.max(), 1e-9);
    }

    @Test
    void invalidNumbersCannotPoisonTheRollingDistribution() {
        var angles = new PacketHeadingAngles();
        assertThrows(IllegalArgumentException.class,
                () -> angles.observe(Double.NaN, 0, .2, 0));
        assertThrows(IllegalArgumentException.class,
                () -> angles.observe(.2, 0, Double.POSITIVE_INFINITY, 0));
        assertThrows(IllegalArgumentException.class,
                () -> angles.observe(Double.MAX_VALUE, Double.MAX_VALUE, .2, 0));
        assertEquals(0, angles.snapshot().measured());
        assertEquals(0, angles.snapshot().lowMotion());
        angle(angles, 45);
        assertEquals(45, angles.snapshot().p95(), 1e-9);
    }
}
