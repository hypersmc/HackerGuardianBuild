package me.hackerguardian.main.detection.packet;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PacketCourseCaptureTest {
    @Test
    void phasesHaveIndependentCountersAndFinishIsIdempotent() {
        var capture = new PacketCourseCapture();
        capture.enter(PacketCourseCapture.Phase.WALK_STRAIGHT);
        capture.observe(PacketGroundReachability.Status.WARMUP, null, null);
        capture.observe(PacketGroundReachability.Status.TICK_GAP, null, null);
        capture.observe(PacketGroundReachability.Status.TICK_GAP, null, null);
        capture.enter(PacketCourseCapture.Phase.WALK_TURN_1);
        capture.observe(PacketGroundReachability.Status.CONTEXT_CHANGED, null, null);
        var report = capture.finish();
        assertEquals(2, report.size());
        assertEquals(3, report.get(0).packets());
        assertEquals(2, report.get(0).gapRejects());
        assertEquals(2, report.get(0).runs().timingClusters().max());
        assertEquals(1, report.get(1).contextRejects());
        assertEquals(report, capture.finish());
        assertThrows(IllegalStateException.class,
                () -> capture.enter(PacketCourseCapture.Phase.WALK_TURN_2));
    }

    @Test
    void phaseOrderIsEnforced() {
        var capture = new PacketCourseCapture();
        assertThrows(IllegalStateException.class,
                () -> capture.enter(PacketCourseCapture.Phase.SPRINT_STRAIGHT));
    }

    private static MovementPacketBuffer.PacketState state(double x, double z) {
        return new MovementPacketBuffer.PacketState(
                new MovementPacketBuffer.Position(x, 64, z), null, true, false, true);
    }

    private static PacketGroundReachability.Context context(long tick, boolean sprint) {
        var probe = new MovementTickTimeline.ProbeContext(true, true, true, true,
                "flat", sprint, "STONE");
        var environment = new MovementTickTimeline.Environment(true, false, true, false, false);
        var flags = new MovementTickTimeline.PlayerFlags(true, false, false, false,
                false, false);
        var input = new MovementTickTimeline.TickInput("world",
                new MovementPacketBuffer.Position(0, 64, 0),
                new MovementPacketBuffer.Velocity(0, 0, 0),
                flags, environment, probe);
        return new PacketGroundReachability.Context(tick, 0, true, false, input);
    }

    @Test
    void rawPacketRouteDeviationIsNotMaskedByReachingTheCheckpoint() {
        var legs = PacketCourseGeometry.build(0, 0, 0, 1, true);
        var capture = new PacketCourseCapture(legs);
        capture.enter(PacketCourseCapture.Phase.WALK_STRAIGHT);
        long tick = 1;
        for (double z : new double[]{8.5, 9, 9.5, 10, 10.5, 11, 11.5, 12}) {
            capture.observe(PacketGroundReachability.Status.CONSERVATIVE_REACHABLE,
                    state(.5, z), context(tick++, false));
        }
        capture.enter(PacketCourseCapture.Phase.WALK_TURN_1);
        for (double x : new double[]{.5, .1, -.3, -.7, -1.1, -1.5, -2, -2.5, -3}) {
            capture.observe(PacketGroundReachability.Status.CONSERVATIVE_REACHABLE,
                    state(x, 12.5), context(tick++, false));
        }
        var result = capture.finish().get(1);
        assertTrue(result.backwardSteps() >= 3);
        assertTrue(result.outsideCorridor() > 0);
        assertEquals("DETOUR", result.turn().quality());
    }

    @Test
    void verifiedServerSprintMismatchIsVisibleAsMixedMode() {
        var legs = PacketCourseGeometry.build(0, 0, 0, 1, true);
        var capture = new PacketCourseCapture(legs);
        capture.enter(PacketCourseCapture.Phase.WALK_STRAIGHT);
        for (int i = 0; i < 8; i++) {
            capture.observe(PacketGroundReachability.Status.CONSERVATIVE_REACHABLE,
                    state(.5, i), context(i + 1, true));
        }
        var result = capture.finish().get(0);
        assertEquals(8, result.mismatchedSprint());
        assertEquals("MIXED_MODE", PacketResearchCourse.quality(result));
    }
}
