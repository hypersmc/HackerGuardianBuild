package me.hackerguardian.main.detection.packet;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PacketCourseTurnStudyTest {
    private static MovementPacketBuffer.Position p(double x, double z) {
        return new MovementPacketBuffer.Position(x, 64, z);
    }

    private static List<MovementPacketBuffer.Position> approach() {
        // Facing south; actual movement towards the first (0,12) checkpoint.
        return List.of(p(.5, 8.5), p(.5, 9.25), p(.5, 10),
                p(.5, 10.75), p(.5, 11.5), p(.5, 12));
    }

    @Test
    void correctlyMeasuresLeftAndRightAcrossCheckpoint() {
        for (boolean left : List.of(true, false)) {
            var legs = PacketCourseGeometry.build(0, 0, 0, 1, left);
            var study = new PacketCourseTurnStudy(legs.get(0), legs.get(1), approach());
            double sign = left ? 1 : -1;
            for (double offset : new double[]{.25, .75, 1.25, 2, 2.75, 3.5, 4.25}) {
                study.observeAfter(p(.5 + sign * offset, 12.5));
            }
            var result = study.snapshot();
            assertEquals(left ? "LEFT" : "RIGHT", result.expectedDirection());
            assertEquals(result.expectedDirection(), result.observedDirection());
            assertEquals("MATCH", result.quality());
            assertNotNull(result.signedAngle());
            assertEquals(left ? -90 : 90, result.signedAngle(), 12);
            assertEquals(0, result.oppositeSteps());
        }
    }

    @Test
    void wrongWayThenCorrectedIsRecordedAsDetour() {
        var legs = PacketCourseGeometry.build(0, 0, 0, 1, true);
        var previous = new ArrayList<>(approach());
        // The player moves RIGHT rather than LEFT, then returns.
        previous.add(p(.25, 12.5));
        previous.add(p(-.1, 12.5));
        previous.add(p(-.45, 12.5));
        previous.add(p(-.8, 12.5));
        previous.add(p(-.45, 12.5));
        previous.add(p(.0, 12.5));
        var study = new PacketCourseTurnStudy(legs.get(0), legs.get(1), previous);
        for (double offset : new double[]{.25, .8, 1.4, 2.25, 3.5}) {
            study.observeAfter(p(.5 + offset, 12.5));
        }
        var result = study.snapshot();
        assertEquals("LEFT", result.expectedDirection());
        assertTrue(result.oppositeSteps() >= 2, "Should retain wrong-side movements before the checkpoint");
        assertEquals("DETOUR", result.quality());
    }

    @Test
    void noActualMovementDoesNotInventAQuarterTurn() {
        var legs = PacketCourseGeometry.build(0, 0, 0, 1, true);
        var study = new PacketCourseTurnStudy(legs.get(0), legs.get(1), approach());
        for (int i = 0; i < 10; i++) study.observeAfter(p(.5, 12.5));
        var result = study.snapshot();
        assertEquals("UNMEASURED", result.quality());
        assertNull(result.signedAngle());
        assertEquals("UNKNOWN", result.observedDirection());
    }

    @Test
    void straightSprintTransitionIsNotMisrepresentedAsNinetyDegreeTurn() {
        var legs = PacketCourseGeometry.build(0, 0, 0, 1, true);
        var study = new PacketCourseTurnStudy(legs.get(2), legs.get(3), List.of());
        assertEquals("TRANSITION", study.snapshot().quality());
        assertFalse(study.snapshot().hasTurn());
    }

    @Test
    void teleportFenceInvalidatesCrossBoundaryAngle() {
        var legs = PacketCourseGeometry.build(0, 0, 0, 1, true);
        var study = new PacketCourseTurnStudy(legs.get(0), legs.get(1), approach());
        study.correction();
        study.observeAfter(p(2.5, 12.5));
        study.observeAfter(p(4.5, 12.5));
        assertEquals("UNMEASURED", study.snapshot().quality());
    }
}
