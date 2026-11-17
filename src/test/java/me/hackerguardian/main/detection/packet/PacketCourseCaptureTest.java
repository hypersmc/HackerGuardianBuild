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
}
