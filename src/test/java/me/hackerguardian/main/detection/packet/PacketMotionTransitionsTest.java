package me.hackerguardian.main.detection.packet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PacketMotionTransitionsTest {

    @Test
    void knownPacketDeltaPatternsClassifyChangesButNotClientInputs() {
        var data = new PacketMotionTransitions();
        data.observe(1, 0, false, .10, 0, .20, 0, .10, 0);
        data.observe(2, 0, false, .20, 0, .16, 0, .10, 0);
        data.observe(3, 0, false, .16, 0, 0, .16, .11, 0);
        data.observe(4, 0, false, .16, 0, .17, 0, .11, 0);
        var out = data.snapshot();
        assertEquals(1, out.accelerating());
        assertEquals(1, out.decelerating());
        assertEquals(1, out.turning());
        assertEquals(1, out.steady());
        assertEquals(0, out.walkToSprint());
        assertEquals(0, out.sprintToWalk());
        assertEquals(0, out.taggedWalk());
        assertEquals(0, out.taggedSprint());
        assertEquals(PacketMotionTransitions.Phase.STEADY, out.lastPhase());
        assertEquals(out, data.snapshot(), "Admin snapshots must never add measurements");
    }

    @Test
    void postSwitchSamplesAreTaggedOnlyWithinSixServerTickHints() {
        var data = new PacketMotionTransitions();
        data.sprintChange(10, 2, true, false);
        assertTrue(data.observe(12, 2, false, .281, 0, .281, 0, .1276, 0));
        assertTrue(data.observe(16, 2, false, .281, 0, .216, 0, .0626, 0));
        assertFalse(data.observe(17, 2, false, .216, 0, .216, 0, .098, 0));
        var snapshot = data.snapshot();
        assertEquals(1, snapshot.sprintToWalk());
        assertEquals(0, snapshot.walkToSprint());
        assertEquals(2, snapshot.taggedWalk());
        assertEquals(2, snapshot.switchResiduals().walking().total());
        assertEquals(.1276, snapshot.switchResiduals().walking().max(), 1e-12);
        assertEquals(2, snapshot.switchDisplacements().enteringWalk().total());
        assertEquals(.281, snapshot.switchDisplacements().enteringWalk().p95(), 1e-12);
        assertEquals(0, snapshot.switchDisplacements().enteringWalk().nearZero());
        assertEquals(0, snapshot.switchDisplacements().enteringWalk().dragLike());
        assertEquals(0, snapshot.taggedSprint());
        assertFalse(snapshot.lastWasTransition());

        data.sprintChange(21, 2, false, true);
        assertTrue(data.observe(24, 2, true, .216, 0, .281, 0, .127, 0));
        assertEquals(1, data.snapshot().walkToSprint());
        assertEquals(1, data.snapshot().taggedSprint());
        assertEquals(1, data.snapshot().switchResiduals().sprinting().total());
        assertEquals(.281, data.snapshot().switchDisplacements().enteringSprint().p95(), 1e-12);
    }

    @Test
    void correctionOrUncertainPacketsCancelTransitionTagging() {
        var data = new PacketMotionTransitions();
        data.sprintChange(10, 1, false, true);
        data.interruption();
        assertFalse(data.observe(12, 1, true, .216, 0, .281, 0, .127, 0));
        assertEquals(0, data.snapshot().taggedSprint());
        assertEquals(0, data.snapshot().switchDisplacements().enteringSprint().total());
        data.sprintChange(20, 2, true, false);
        assertFalse(data.observe(22, 3, false, .281, 0, .216, 0, .10, 0),
                "A correction epoch change cannot share a post-switch window");
        assertEquals(0, data.snapshot().taggedWalk());
        assertEquals(0, data.snapshot().switchDisplacements().enteringWalk().total());
        assertEquals(2, data.snapshot().accelerating() +
                data.snapshot().decelerating() + data.snapshot().turning() +
                data.snapshot().steady(), "Both packets were still research-only phase samples");
    }

    @Test
    void switchResidualRingsRemainBoundedAndInvalidInputIsRejected() {
        var data = new PacketMotionTransitions();
        data.sprintChange(1, 0, true, false);
        for (int i = 0; i < 250; i++) {
            data.sprintChange(10L * (i + 1), 0, true, false);
            assertTrue(data.observe(10L * (i + 1) + 2, 0, false,
                    .281, 0, .281, 0, i / 1000.0, 0));
        }
        var snapshot = data.snapshot();
        assertEquals(250, snapshot.taggedWalk());
        assertEquals(128, snapshot.switchResiduals().walking().retained());
        assertEquals(250, snapshot.switchDisplacements().enteringWalk().total());
        assertEquals(128, snapshot.switchDisplacements().enteringWalk().retained());
        assertEquals(250, snapshot.switchResiduals().walking().total());
        assertEquals(.249, snapshot.switchResiduals().walking().max(), 1e-12);
        assertThrows(IllegalArgumentException.class,
                () -> data.observe(9999, 0, false, Double.NaN, 0,
                        .2, 0, .1, 0));
        assertEquals(snapshot, data.snapshot());
    }
}
