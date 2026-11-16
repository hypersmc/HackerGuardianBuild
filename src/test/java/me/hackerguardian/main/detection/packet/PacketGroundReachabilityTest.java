package me.hackerguardian.main.detection.packet;

import me.hackerguardian.compat.MinecraftVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PacketGroundReachabilityTest {
    private static PacketGroundReachability.Profile version(String mc) {
        return PacketGroundReachability.Profile.forMinecraft(MinecraftVersion.parse(mc));
    }

    private static MovementTickTimeline.TickInput flatGround() {
        return new MovementTickTimeline.TickInput("world",
                new MovementPacketBuffer.Position(0, 64, 0),
                new MovementPacketBuffer.Velocity(0, 0, 0),
                new MovementTickTimeline.PlayerFlags(true, false, false, false, false, false),
                new MovementTickTimeline.Environment(true, false, true, false, false),
                new MovementTickTimeline.ProbeContext(true, true, true, true, "", false, "STONE"));
    }

    private static MovementPacketBuffer.PacketState at(double x, double y, double z, Boolean ground) {
        return new MovementPacketBuffer.PacketState(
                new MovementPacketBuffer.Position(x, y, z), null, ground, false, true);
    }

    private static PacketGroundReachability.Context context(long tick) {
        return new PacketGroundReachability.Context(tick, 0, true, false, flatGround());
    }

    @Test
    void versionProfilesAreExplicitAndFutureOrUnknownFailClosed() {
        assertEquals("1.20.x", version("1.20.2").family());
        assertEquals("1.21.x", version("1.21.11").family());
        assertEquals(.546, version("1.20.2").ordinaryDrag(), 1e-10);
        assertNull(version("26.2"), "Even if compatibility builds, physics is not yet calibrated for 26.x");
        assertNull(version("1.22.1"));
        assertNull(version("1.19.4"));
        assertNull(PacketGroundReachability.Profile.forMinecraft(null));
        var model = new PacketGroundReachability(null);
        model.observe(at(1, 64, 0, true), context(1));
        assertEquals(PacketGroundReachability.Status.DISABLED_VERSION, model.snapshot().lastStatus());
        assertEquals(0, model.snapshot().modeledTriples());
    }

    @Test
    void genuineClientPositionTriplesYieldUnknownInputReachabilityEnvelope() {
        var model = new PacketGroundReachability(version("1.20.2"));
        model.observe(at(0, 64, 0, true), context(1));
        model.observe(at(.216, 64, 0, true), context(2));
        model.observe(at(.432, 64, 0, true), context(3));

        var snap = model.snapshot();
        assertEquals(3, snap.positionPackets());
        assertEquals(1, snap.modeledTriples());
        assertEquals(2, snap.noDecision()); // first two claims warm up
        assertEquals(0, snap.researchedOutside());
        assertEquals(PacketGroundReachability.Status.CONSERVATIVE_REACHABLE, snap.lastStatus());
        assertEquals(.216, snap.lastActual(), 1e-9);
        assertEquals(0, snap.lastExcess(), 1e-9);
        assertTrue(snap.lastReachable() > .2);
        assertEquals(snap, model.snapshot(), "Diagnostics must be read-only");
    }

    @Test
    void syntheticHugeDisplacementIsResearchOutlierButNotEnforcement() {
        var model = new PacketGroundReachability(version("1.21.11"));
        model.observe(at(0, 64, 0, true), context(1));
        model.observe(at(.216, 64, 0, true), context(2));
        model.observe(at(1.1, 64, 0, true), context(3));
        var s = model.snapshot();
        assertEquals(1, s.modeledTriples());
        assertEquals(1, s.researchedOutside());
        assertTrue(s.lastExcess() > .4);
        assertEquals(PacketGroundReachability.Status.OUTSIDE_RESEARCH_ENVELOPE, s.lastStatus());
        model.observe(at(1.31, 64, 0, true), context(4));
        assertEquals(PacketGroundReachability.Status.UNSUPPORTED_CONTEXT, model.snapshot().lastStatus(),
                "Previous anomalous displacement cannot seed an unbounded next prediction");
    }

    @Test
    void ambiguousOrSkippedSchedulerTicksCannotBeInventedAsClientPhysicsTicks() {
        var model = new PacketGroundReachability(version("1.20.2"));
        model.observe(at(0, 64, 0, true), context(1));
        model.observe(at(.1, 64, 0, true), context(1));
        assertEquals(PacketGroundReachability.Status.AMBIGUOUS_TICK, model.snapshot().lastStatus());
        assertEquals(1, model.snapshot().ambiguousTicks());
        model.observe(at(.2, 64, 0, true), context(3));
        model.observe(at(.4, 64, 0, true), context(5));
        assertEquals(PacketGroundReachability.Status.TICK_GAP, model.snapshot().lastStatus());
        assertEquals(0, model.snapshot().modeledTriples());
    }

    @Test
    void teleportAndVelocityCorrectionsHardResetPacketHistory() {
        var model = new PacketGroundReachability(version("1.20.2"));
        model.observe(at(0, 64, 0, true), context(1));
        model.observe(at(.2, 64, 0, true), context(2));
        model.correction();
        model.observe(at(300, 64, 0, true), new PacketGroundReachability.Context(
                3, 1, false, true, flatGround()));
        assertEquals(PacketGroundReachability.Status.RECENT_CORRECTION,
                model.snapshot().lastStatus());
        model.observe(at(300, 64, 0, true), new PacketGroundReachability.Context(
                4, 1, false, false, flatGround()));
        model.observe(at(300, 64, 0, true), new PacketGroundReachability.Context(
                5, 1, true, false, flatGround()));
        assertEquals(PacketGroundReachability.Status.WARMUP, model.snapshot().lastStatus());
        assertEquals(0, model.snapshot().modeledTriples());
        assertTrue(model.snapshot().corrections() >= 3);
    }

    @Test
    void invalidOnGroundClaimsOrUnavailableServerContextRemainUndecidable() {
        var model = new PacketGroundReachability(version("1.21.11"));
        model.observe(at(0, 64, 0, true), null);
        assertEquals(PacketGroundReachability.Status.NO_SERVER_SAMPLE, model.snapshot().lastStatus());
        model.observe(at(0, 64, 0, null), context(1));
        model.observe(at(0, 64, 0, false), context(2));
        assertEquals(PacketGroundReachability.Status.INVALID_GROUND_CLAIM,
                model.snapshot().lastStatus());
        model.observe(MovementPacketBuffer.PacketState.missing(), context(3));
        assertEquals(0, model.snapshot().modeledTriples());
    }

    @Test
    void staleOrLaggyServerSampleCannotBeUsedAsClientSimulationTime() {
        var model = new PacketGroundReachability(version("1.20.2"));
        model.observe(at(0, 64, 0, true), new PacketGroundReachability.Context(
                1, 0, true, false, flatGround(), false, 0));
        assertEquals(PacketGroundReachability.Status.STALE_SERVER_SAMPLE,
                model.snapshot().lastStatus());
        model.observe(at(0, 64, 0, true), new PacketGroundReachability.Context(
                2, 0, true, false, flatGround(), true, 250_000_000L));
        assertEquals(PacketGroundReachability.Status.STALE_SERVER_SAMPLE,
                model.snapshot().lastStatus());
        assertEquals(0, model.snapshot().modeledTriples());
    }

    @Test
    void surfaceModeAndWorldMustRemainConsistentAcrossThreeClaims() {
        var model = new PacketGroundReachability(version("1.20.2"));
        model.observe(at(0, 64, 0, true), context(1));
        model.observe(at(.2, 64, 0, true), context(2));
        var grass = new MovementTickTimeline.TickInput("world",
                flatGround().position(), flatGround().velocity(),
                flatGround().flags(), flatGround().environment(),
                new MovementTickTimeline.ProbeContext(true, true, true, true, "", false, "GRASS_BLOCK"));
        model.observe(at(.4, 64, 0, true),
                new PacketGroundReachability.Context(3, 0, true, false, grass));
        assertEquals(PacketGroundReachability.Status.CONTEXT_CHANGED,
                model.snapshot().lastStatus());
        assertEquals(0, model.snapshot().modeledTriples());

        var creative = new MovementTickTimeline.TickInput("world",
                flatGround().position(), flatGround().velocity(),
                new MovementTickTimeline.PlayerFlags(false, true, false, false, false, true),
                flatGround().environment(), flatGround().probeContext());
        model.observe(at(.6, 64, 0, true),
                new PacketGroundReachability.Context(4, 0, true, false, creative));
        assertEquals(PacketGroundReachability.Status.UNSUPPORTED_CONTEXT,
                model.snapshot().lastStatus());
    }

    @Test
    void threePacketVerticalMotionIsExcludedFromGroundModel() {
        var model = new PacketGroundReachability(version("1.20.2"));
        model.observe(at(0, 64, 0, true), context(1));
        model.observe(at(.2, 64, 0, true), context(2));
        model.observe(at(.4, 64.8, 0, true), context(3));
        assertEquals(PacketGroundReachability.Status.UNSUPPORTED_CONTEXT,
                model.snapshot().lastStatus());
        assertEquals(0, model.snapshot().modeledTriples());
    }

    @Test
    void boundedSessionStateDoesNotGrowWithPacketCount() {
        var model = new PacketGroundReachability(version("1.20.2"));
        for (int i = 1; i <= 5000; i++) {
            model.observe(at(i * .2, 64, 0, true), context(i));
        }
        var s = model.snapshot();
        assertEquals(5000, s.positionPackets());
        assertEquals(4998, s.modeledTriples());
        assertEquals(0, s.researchedOutside());
    }
}
