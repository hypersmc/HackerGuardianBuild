package me.hackerguardian.main.detection.packet;

import me.hackerguardian.compat.MinecraftVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PacketPhysicsValidationTest {
    private static final double INPUT = .1274;
    private static final double MARGIN = .10;
    private static final double DRAG = .546;

    @Test
    void modeledMotionIsStratifiedWithoutTrustingCameraYaw() {
        var study = new PacketPhysicsValidation();
        study.observe(false, false, .2, 0, .2, 0, .10, INPUT, MARGIN);
        study.observe(false, false, .2, 0, 0, .2, .17, INPUT, MARGIN);
        study.observe(true, false, 0, .2, 0, .3, .15, INPUT, MARGIN);
        study.observe(true, false, .2, 0, 0, -.3, .20, INPUT, MARGIN);
        study.observe(false, true, .2, 0, .2, 0, .15, INPUT, MARGIN);
        study.observe(false, false, .2, 0, 0, 0, .05, INPUT, MARGIN);
        study.observe(false, false, 0, 0, .05, 0, .05, INPUT, MARGIN);

        var s = study.snapshot();
        assertEquals(7, s.total());
        assertEquals(1, s.walkStraight().total());
        assertEquals(1, s.walkTurn().total());
        assertEquals(1, s.sprintStraight().total());
        assertEquals(1, s.sprintTurn().total());
        assertEquals(1, s.postSwitch().total());
        assertEquals(1, s.quiet().total());
        assertEquals(1, s.lowVector().total());
        assertEquals(0, s.outside());
        assertEquals(4, s.margin());
        assertEquals(s, study.snapshot());
    }

    @Test
    void quantifiesNominalAccelerationVsOnlyUncertaintyMargin() {
        var study = new PacketPhysicsValidation();
        for (double residual : new double[]{.12, .18, .30}) {
            study.observe(false, false, .20, 0, .21, 0,
                    residual, INPUT, MARGIN);
        }
        var g = study.snapshot().walkStraight();
        assertEquals(3, g.total());
        assertEquals(1, g.nominal());
        assertEquals(1, g.margin());
        assertEquals(1, g.outside());
        assertEquals(.30 / (INPUT + MARGIN), g.p95Ratio(), 1e-10);
        assertEquals(.30 - (INPUT + MARGIN), g.maxExcess(), 1e-10);
    }

    @Test
    void boundedRollingPercentilesDoNotEraseLifetimeExceedances() {
        var study = new PacketPhysicsValidation();
        study.observe(false, false, .2, 0, .2, 0, .40, INPUT, MARGIN);
        for (int i = 0; i < 200; i++) {
            study.observe(false, false, .2, 0, .2, 0, .05, INPUT, MARGIN);
        }
        var g = study.snapshot().walkStraight();
        assertEquals(201, g.total());
        assertEquals(128, g.retained());
        assertEquals(1, g.outside());
        assertEquals(.05 / (INPUT + MARGIN), g.p95Ratio(), 1e-10);
        assertEquals(0.0, g.maxExcess(), 0.0,
                "Max excess only describes retained window, not lifetime max");
    }

    @Test
    void invalidCalculationsNeverMutateTheValidationCounters() {
        var study = new PacketPhysicsValidation();
        assertThrows(IllegalArgumentException.class,
                () -> study.observe(false, false, 0, 0, 0, 0,
                        Double.NaN, INPUT, MARGIN));
        assertThrows(IllegalArgumentException.class,
                () -> study.observe(false, false, 0, 0, 0, 0,
                        .1, 0, MARGIN));
        assertThrows(IllegalArgumentException.class,
                () -> study.observe(false, false, 0, 0, 0, 0,
                        .1, INPUT, Double.POSITIVE_INFINITY));
        assertEquals(0, study.snapshot().total());
    }

    private static MovementPacketBuffer.PacketState position(double x, double z) {
        return new MovementPacketBuffer.PacketState(
                new MovementPacketBuffer.Position(x, 64, z),
                null, true, false, true);
    }

    private static PacketGroundReachability.Context context(long tick, boolean sprint) {
        var state = new MovementTickTimeline.TickInput("world",
                new MovementPacketBuffer.Position(0, 64, 0),
                new MovementPacketBuffer.Velocity(0, 0, 0),
                new MovementTickTimeline.PlayerFlags(true, false, false, false, false, false),
                new MovementTickTimeline.Environment(true, false, true, false, false),
                new MovementTickTimeline.ProbeContext(true, true, true, true,
                        "flat", sprint, "STONE"));
        return new PacketGroundReachability.Context(tick, 0, true, false, state);
    }

    @Test
    void independentBoundedInputMotionFixtureFitsEnvelopeThenSpikeExceeds() {
        var profile = PacketGroundReachability.Profile.forMinecraft(
                MinecraftVersion.parse("1.21.11"));
        var model = new PacketGroundReachability(profile);
        double x = 0, z = 0, vx = 0, vz = 0;

        for (int tick = 1; tick <= 150; tick++) {
            // Independent synthetic reference: v(next) = drag * v(previous) + u,
            // |u| <= 0.10, always beneath the nominal unknown-input bound.
            // Direction steps introduce turns; 60 and 110 change server sprint state.
            int direction = (tick / 17) % 4;
            double ux = direction == 0 ? .10 : direction == 2 ? -.10 : 0;
            double uz = direction == 1 ? .10 : direction == 3 ? -.10 : 0;
            vx = DRAG * vx + ux;
            vz = DRAG * vz + uz;
            x += vx;
            z += vz;
            boolean sprint = tick >= 60 && tick < 110;
            model.observe(position(x, z), context(tick, sprint));
        }
        var before = model.snapshot();
        assertEquals(before.modeledTriples(), before.physics().total());
        assertEquals(0, before.physics().outside());
        assertEquals(0, before.physics().margin());
        assertTrue(before.physics().walkStraight().total() > 0);
        assertTrue(before.physics().sprintStraight().total() > 0);
        assertTrue(before.physics().walkTurn().total()
                        + before.physics().sprintTurn().total() > 0);
        assertTrue(before.physics().postSwitch().total() > 0);

        // A synthetic coordinate jump is an outlier in this provisional disk,
        // but the result still remains research-only, not a cheating finding.
        x += .6;
        model.observe(position(x, z), context(151, false));
        var after = model.snapshot();
        assertEquals(before.modeledTriples() + 1, after.modeledTriples());
        assertEquals(before.physics().outside() + 1, after.physics().outside());
        assertEquals(PacketGroundReachability.Status.OUTSIDE_RESEARCH_ENVELOPE,
                after.lastStatus());
    }
}
