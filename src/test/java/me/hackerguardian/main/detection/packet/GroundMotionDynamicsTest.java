package me.hackerguardian.main.detection.packet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GroundMotionDynamicsTest {
    @Test
    void classifiesStartsStopsTurnsAndChangesWithoutCheatVerdicts() {
        var d = new GroundMotionDynamics();
        d.observe(2, 0, .20, 0, false, "STONE");       // first vector
        d.observe(3, 0, .22, 0, false, "STONE");       // steady
        d.observe(4, 0, .30, 0, false, "STONE");       // accelerate
        d.observe(5, 0, .29, .01, false, "STONE");     // steady
        d.observe(6, 0, 0, .30, false, "STONE");       // turn
        d.observe(7, 0, 0, .20, false, "STONE");       // decelerate
        d.observe(8, 0, 0, 0, false, "STONE");         // stop
        d.observe(9, 0, 0, .18, false, "STONE");       // start
        var s = d.snapshot();
        assertEquals(8, s.eligibleVectors());
        assertEquals(7, s.comparedTransitions());
        assertEquals(1, s.starts());
        assertEquals(1, s.stops());
        assertEquals(1, s.turns());
        assertEquals(1, s.accelerations());
        assertEquals(1, s.decelerations());
        assertEquals(2, s.steady());
        assertEquals(GroundMotionDynamics.Phase.START, s.lastPhase());
        assertEquals("STONE", s.lastSurface());
        assertEquals(7, s.absoluteSpeedChange().total());
        assertEquals(1, s.turnAngles().total());
        assertEquals(2, s.slowingRetention().total());
        assertTrue(s.turnAngles().p50() > 80);
        assertTrue(s.slowingRetention().p50() >= 0);
        assertEquals(s, d.snapshot(), "Read-only introspection must not update any counters");
    }

    @Test
    void correctionAndMissingSampleInterruptTheThreePositionChain() {
        var d = new GroundMotionDynamics();
        d.observe(5, 0, .20, 0, false, "STONE");
        d.observe(6, 0, .25, 0, false, "STONE");
        d.interrupt(); // no client data, teleport, knockback, etc
        d.observe(8, 1, .80, 0, false, "STONE");
        assertEquals(1, d.snapshot().comparedTransitions());
        assertEquals(1, d.snapshot().discontinuities());
        assertEquals(1, d.snapshot().accelerations());
        d.observe(10, 1, 0, .80, false, "STONE");  // skipped tick
        assertEquals(GroundMotionDynamics.Phase.INTERRUPTED, d.snapshot().lastPhase());
        assertEquals(2, d.snapshot().discontinuities());
        assertEquals(1, d.snapshot().comparedTransitions());
        d.interrupt();
        d.interrupt();  // repeated idle diagnostics should not add more breaks
        assertEquals(3, d.snapshot().discontinuities());
    }

    @Test
    void doesNotCompareSprintChangeOrGroundMaterialSwitch() {
        var d = new GroundMotionDynamics();
        d.observe(1, 0, .2, 0, false, "STONE");
        d.observe(2, 0, .3, 0, true, "STONE");
        assertEquals(GroundMotionDynamics.Phase.SPRINT_CHANGE, d.snapshot().lastPhase());
        d.observe(3, 0, .3, 0, true, "GRASS_BLOCK");
        assertEquals(GroundMotionDynamics.Phase.SURFACE_CHANGE, d.snapshot().lastPhase());
        d.observe(4, 0, .3, 0, true, "GRASS_BLOCK");
        assertEquals(1, d.snapshot().comparedTransitions());
        assertEquals(1, d.snapshot().sprintChanges());
        assertEquals(1, d.snapshot().surfaceChanges());
        assertEquals(0, d.snapshot().turns());
    }

    @Test
    void emptyAndStationaryMotionDoNotInventFrictionMeasurements() {
        var d = new GroundMotionDynamics();
        assertNull(d.snapshot().slowingRetention().p50());
        d.observe(1, 0, 0, 0, false, "STONE");
        d.observe(2, 0, 0, 0, false, "STONE");
        assertEquals(GroundMotionDynamics.Phase.STEADY, d.snapshot().lastPhase());
        assertEquals(0, d.snapshot().slowingRetention().retained());
        assertNull(d.snapshot().slowingRetention().p95());
        assertEquals(0, d.snapshot().absoluteSpeedChange().p50(), 0);
    }

    @Test
    void retainsAtMost128TransitionValues() {
        var d = new GroundMotionDynamics();
        for (int tick = 1; tick <= 300; tick++) {
            double speed = (tick & 1) == 0 ? .15 : .25;
            d.observe(tick, 0, speed, 0, false, "STONE");
        }
        var snap = d.snapshot();
        assertEquals(300, snap.eligibleVectors());
        assertEquals(299, snap.comparedTransitions());
        assertEquals(128, snap.absoluteSpeedChange().retained());
        assertEquals(299, snap.absoluteSpeedChange().total());
        assertTrue(snap.absoluteSpeedChange().p95() <= .101);
        assertEquals(0, snap.discontinuities());
    }

    @Test
    void invalidDisplacementIsNeverRecorded() {
        var d = new GroundMotionDynamics();
        assertThrows(IllegalArgumentException.class,
                () -> d.observe(1, 0, Double.NaN, 0, false, "STONE"));
        assertThrows(IllegalArgumentException.class,
                () -> d.observe(1, 0, 0, Double.POSITIVE_INFINITY, false, "STONE"));
        assertThrows(IllegalArgumentException.class,
                () -> d.observe(1, 0, 0, 0, false, null));
        assertEquals(0, d.snapshot().eligibleVectors());
    }
}
