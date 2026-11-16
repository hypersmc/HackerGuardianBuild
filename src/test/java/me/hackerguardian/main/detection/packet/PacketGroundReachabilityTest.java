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
    void tickHintsExplainGapsAndWarmupsWithoutChangingModeledPacketCount() {
        var model = new PacketGroundReachability(version("1.20.2"));
        model.observe(at(0, 64, 0, true), context(1));
        model.observe(at(.2, 64, 0, true), context(2));
        model.observe(at(.4, 64, 0, true), context(4)); // TICK_GAP
        model.observe(at(.6, 64, 0, true), context(5)); // timing warmup
        model.observe(at(.8, 64, 0, true), context(6)); // modeled
        var initial = model.snapshot();
        assertEquals(1, initial.modeledTriples());
        assertEquals(4, initial.noDecision());
        assertEquals(2, initial.tickHints().warmupInitial());
        assertEquals(1, initial.tickHints().warmupAfterTiming());
        assertEquals(1, initial.tickHints().actualTickGapRejects());
        assertEquals(1, initial.tickHints().twoTickGaps());
        assertEquals(3, initial.tickHints().adjacentHints());
        assertEquals(initial.headingSkips().warmup(), initial.tickHints().attributedWarmups());
        assertEquals(initial, model.snapshot());

        // A same-hint packet destroys the triple chain, as before.
        model.observe(at(.81, 64, 0, true), context(6));
        model.observe(at(.82, 64, 0, true), context(7));
        model.observe(at(.83, 64, 0, true), context(8));
        var afterRepeat = model.snapshot();
        assertEquals(1, afterRepeat.modeledTriples());
        assertEquals(1, afterRepeat.tickHints().repeatedHints());
        assertEquals(1, afterRepeat.tickHints().actualAmbiguousRejects());
        assertEquals(3, afterRepeat.tickHints().warmupAfterTiming());
        assertEquals(afterRepeat.headingSkips().warmup(),
                afterRepeat.tickHints().attributedWarmups());
        assertEquals(afterRepeat.positionPackets(),
                afterRepeat.modeledTriples() + afterRepeat.noDecision());
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
    void acceptedPacketTriplesPopulateSeparateWalkAndSprintResiduals() {
        var model = new PacketGroundReachability(version("1.20.2"));
        model.observe(at(0, 64, 0, true), context(1));
        model.observe(at(.216, 64, 0, true), context(2));
        model.observe(at(.432, 64, 0, true), context(3));
        model.observe(at(.648, 64, 0, true), context(4));

        var walk = model.snapshot().residuals().walking();
        assertEquals(2, walk.total());
        assertEquals(0, walk.outside());
        assertEquals(.216 * (1 - .546), walk.p50(), 1e-12);
        assertEquals(walk.p50(), walk.p95(), 1e-12);

        var input = flatGround();
        var sprinting = new MovementTickTimeline.TickInput(input.worldKey(),
                input.position(), input.velocity(), input.flags(), input.environment(),
                new MovementTickTimeline.ProbeContext(true, true, true, true,
                        "", true, "STONE"));
        model.observe(at(.929, 64, 0, true),
                new PacketGroundReachability.Context(5, 0, true, false, sprinting));
        assertEquals(PacketGroundReachability.Status.CONTEXT_CHANGED,
                model.snapshot().lastStatus(),
                "Sprint transition must not enter either residual distribution");
        model.observe(at(1.210, 64, 0, true),
                new PacketGroundReachability.Context(6, 0, true, false, sprinting));
        model.observe(at(1.491, 64, 0, true),
                new PacketGroundReachability.Context(7, 0, true, false, sprinting));
        model.observe(at(1.772, 64, 0, true),
                new PacketGroundReachability.Context(8, 0, true, false, sprinting));
        var snapshot = model.snapshot();
        assertEquals(4, snapshot.modeledTriples());
        assertEquals(2, snapshot.residuals().walking().total());
        assertEquals(0, snapshot.residuals().sprinting().total(),
                "Post-switch residuals must not contaminate steady sprint statistics");
        assertEquals(2, snapshot.transitions().switchResiduals().sprinting().total());
        assertEquals(1, snapshot.transitions().walkToSprint());
        assertEquals(.281 * (1 - .546),
                snapshot.transitions().switchResiduals().sprinting().p50(), 1e-12);
        assertTrue(snapshot.transitions().switchResiduals().sprinting().p50()
                > snapshot.residuals().walking().p50());
        assertEquals(snapshot, model.snapshot(), "Admin reads cannot change profiles");

        model.observe(at(1.8, 64, 0, false),
                new PacketGroundReachability.Context(9, 0, true, false, sprinting));
        model.correction();
        assertEquals(4, model.snapshot().modeledTriples());
        assertEquals(2, model.snapshot().transitions().switchResiduals().sprinting().total(),
                "Unsupported client-ground claims and correction resets must not sample residuals");
        assertEquals(0, model.snapshot().residuals().sprinting().total());
    }

    @Test
    void sprintMomentumAfterSwitchBackToWalkingDoesNotPolluteWalkP95OrMax() {
        var model = new PacketGroundReachability(version("1.20.2"));
        var normal = flatGround();
        var sprint = new MovementTickTimeline.TickInput(normal.worldKey(),
                normal.position(), normal.velocity(), normal.flags(), normal.environment(),
                new MovementTickTimeline.ProbeContext(true, true, true, true,
                        "", true, "STONE"));
        double x = 0;
        for (int tick = 1; tick <= 35; tick++) {
            boolean sprinting = tick >= 9 && tick < 24;
            // Deliberately retain full sprint displacement for the first
            // four received walking packets AFTER server sprint flag flips.
            double distance = sprinting || (tick >= 24 && tick <= 27) ? .281 : .216;
            x += distance;
            model.observe(at(x, 64, 0, true), new PacketGroundReachability.Context(
                    tick, 0, true, false, sprinting ? sprint : normal));
        }

        var result = model.snapshot();
        assertEquals(1, result.transitions().walkToSprint());
        assertEquals(1, result.transitions().sprintToWalk());
        assertTrue(result.transitions().taggedWalk() > 0);
        assertTrue(result.transitions().taggedSprint() > 0);
        assertTrue(result.residuals().walking().total() > 0);
        assertTrue(result.residuals().sprinting().total() > 0);
        assertEquals(.216 * (1 - .546), result.residuals().walking().p95(), 1e-9);
        assertEquals(.216 * (1 - .546), result.residuals().walking().max(), 1e-9);
        assertTrue(result.transitions().switchResiduals().walking().max()
                > result.residuals().walking().max(),
                "Old sprint momentum must remain in the separate sprint-to-walk tail");
        assertEquals(result.modeledTriples(),
                result.residuals().walking().total()
                        + result.residuals().sprinting().total()
                        + result.transitions().taggedWalk()
                        + result.transitions().taggedSprint());
        assertEquals(result, model.snapshot());
    }

    @Test
    void packetDistanceDisambiguatesZeroResidualCoastingFromStationaryInterval() {
        var model = new PacketGroundReachability(version("1.20.2"));
        var walk = flatGround();
        var sprint = new MovementTickTimeline.TickInput(walk.worldKey(),
                walk.position(), walk.velocity(), walk.flags(), walk.environment(),
                new MovementTickTimeline.ProbeContext(true, true, true, true,
                        "", true, "STONE"));
        double x = 0;
        for (int t = 1; t <= 10; t++) {
            x += t <= 3 ? .216 : .281;
            model.observe(at(x, 64, 0, true),
                    new PacketGroundReachability.Context(t, 0, true, false,
                            t <= 3 ? walk : sprint));
        }

        // Synthetic zero-input decay, with strictly positive packet
        // displacement despite a model residual close to zero.
        double decay = .281;
        for (int t = 11; t <= 15; t++) {
            decay *= .546;
            x += decay;
            model.observe(at(x, 64, 0, true),
                    new PacketGroundReachability.Context(t, 0, true, false, walk));
        }
        var coasting = model.snapshot().transitions();
        assertEquals(1, coasting.sprintToWalk());
        assertTrue(coasting.switchDisplacements().enteringWalk().total() >= 2);
        assertTrue(coasting.switchDisplacements().enteringWalk().dragLike() >= 2,
                "Nonzero packet motion can have nearly zero input residual");
        assertEquals(0, coasting.switchDisplacements().enteringWalk().nearZero(),
                "The initial decay steps are not stationary");
        assertTrue(coasting.switchDisplacements().enteringWalk().p95() > .01);
        assertEquals(0.0, coasting.switchResiduals().walking().p95(), 1e-9);

        // A motionless packet interval is different from coasting.
        // Reset to a separate post-switch window so both groups can
        // be compared without changing existing reachability rules.
        var stopped = new PacketMotionTransitions();
        stopped.sprintChange(20, 0, true, false);
        stopped.observe(22, 0, false, 0, 0, 0, 0, 0, 0);
        stopped.observe(23, 0, false, 0, 0, 0, 0, 0, 0);
        var still = stopped.snapshot().switchDisplacements().enteringWalk();
        assertEquals(2, still.nearZero());
        assertEquals(0, still.dragLike());
        assertEquals(0.0, still.p95(), 0.0);
    }

    @Test
    void syntheticResearchOutsideIsRecordedButNotTreatedAsARealCheatVerdict() {
        var model = new PacketGroundReachability(version("1.21.11"));
        model.observe(at(0, 64, 0, true), context(1));
        model.observe(at(.20, 64, 0, true), context(2));
        model.observe(at(1.1, 64, 0, true), context(3));
        var snapshot = model.snapshot();
        var walk = snapshot.residuals().walking();
        assertEquals(1, snapshot.modeledTriples());
        assertEquals(1, snapshot.researchedOutside());
        assertEquals(1, walk.outside());
        assertEquals(1, walk.total());
        assertEquals(.9 - .546 * .2, walk.p50(), 1e-12);
        assertTrue(walk.maxExcess() > .4);
        assertEquals(0, snapshot.residuals().sprinting().total());
    }

    @Test
    void rightAngleClientPacketMotionIsMeasuredWithoutUsingBukkitYaw() {
        var model = new PacketGroundReachability(version("1.20.2"));
        model.observe(at(0, 64, 0, true), context(1));
        model.observe(at(.2, 64, 0, true), context(2));
        model.observe(at(.2, 64, .2, true), context(3)); // actual 90-degree XZ turn

        var result = model.snapshot();
        assertEquals(1, result.modeledTriples());
        assertEquals(1, result.transitions().turning());
        assertEquals(1, result.transitions().headings().measured());
        assertEquals(1, result.transitions().headings().over35());
        assertEquals(90, result.transitions().headings().p95(), 1e-9);
        assertEquals(0, result.transitions().headings().lowMotion());
        assertEquals(2, result.noDecision());
    }

    @Test
    void headingsExplainExcludedPacketsWithoutInventingDirectionalAngles() {
        var model = new PacketGroundReachability(version("1.20.2"));
        model.observe(at(0, 64, 0, true), null); // no server sample
        model.observe(at(0, 64, 0, true), context(1)); // warmup
        model.observe(at(.2, 64, 0, true), context(2)); // warmup
        model.observe(at(.2, 64, .2, true), context(3)); // 90-degree turn
        model.observe(at(.2001, 64, .2001, true), context(4)); // too slow for heading
        model.observe(at(.2, 64, .2, false), context(5)); // client claim invalid
        model.observe(at(.2, 64, .2, true), context(6)); // warmup
        model.observe(at(.3, 64, .2, true), context(8)); // tick gap

        var creative = new MovementTickTimeline.TickInput("world",
                flatGround().position(), flatGround().velocity(),
                new MovementTickTimeline.PlayerFlags(false, true, false, false, false, true),
                flatGround().environment(), flatGround().probeContext());
        model.observe(at(.4, 64, .2, true), new PacketGroundReachability.Context(
                9, 0, true, false, creative));
        model.observe(at(.5, 64, .2, true), new PacketGroundReachability.Context(
                10, 0, false, true, flatGround())); // correction pending
        model.observe(at(.6, 64, .2, true), new PacketGroundReachability.Context(
                11, 0, true, false, flatGround(), false, 200_000_000L));

        var result = model.snapshot();
        var excluded = result.headingSkips();
        assertEquals(11, result.positionPackets());
        assertEquals(2, result.modeledTriples());
        assertEquals(9, result.noDecision());
        assertEquals(3, excluded.warmup());
        assertEquals(2, excluded.timing());
        assertEquals(1, excluded.context());
        assertEquals(1, excluded.invalidPacket());
        assertEquals(1, excluded.correction());
        assertEquals(1, excluded.unavailable());
        assertEquals(result.noDecision(), excluded.warmup() + excluded.timing()
                + excluded.context() + excluded.invalidPacket()
                + excluded.correction() + excluded.unavailable());
        assertEquals(1, result.transitions().headings().measured());
        assertEquals(1, result.transitions().headings().lowMotion());
        assertEquals(90, result.transitions().headings().max(), 1e-9);
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
