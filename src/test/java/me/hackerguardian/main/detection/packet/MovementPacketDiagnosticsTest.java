package me.hackerguardian.main.detection.packet;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementPacketDiagnosticsTest {

    @Test
    void defaultSummaryFitsMinecraftChatAndPreservesImportantTransportCounters() {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        var buffer = new MovementPacketBuffer(256, clock::get);
        UUID player = UUID.randomUUID();
        buffer.record(player, MovementPacketBuffer.Kind.POSITION,
                new MovementPacketBuffer.PacketState(
                        new MovementPacketBuffer.Position(10, 64, 20),
                        null, false, null, true));

        var transport = new MovementPacketTransport(clock::get);
        transport.outboundTeleport(2);
        transport.inboundConfirm(2);
        transport.inboundConfirm(1); // A legitimate unknown/late ID is not a cheat finding.

        var lines = MovementPacketDiagnostics.render("HypersMC", buffer.snapshot(player),
                transport.snapshot(), MovementPacketDiagnostics.View.SUMMARY, clock.get());

        assertEquals(5, lines.size(), "Never flood chat by default");
        assertTrue(lines.get(0).contains("HypersMC"));
        assertTrue(lines.stream().anyMatch(s -> s.contains("Decode errors 0")));
        assertTrue(lines.stream().anyMatch(s -> s.contains("On-ground false")));
        assertTrue(lines.stream().anyMatch(s -> s.contains("Collision UNKNOWN")));
        assertTrue(lines.stream().anyMatch(s -> s.contains("matched 1")
                && s.contains("pending 0") && s.contains("unmatched 1")));
        assertTrue(lines.get(lines.size() - 1).contains("move|net"));
        assertFalse(lines.stream().anyMatch(s -> s.contains("cheat verdict")));
    }

    @Test
    void optionalMovementAndNetworkViewsStayShortAndSpecific() {
        AtomicLong clock = new AtomicLong(3_000_000_000L);
        UUID id = UUID.randomUUID();
        var buffer = new MovementPacketBuffer(2, clock::get);
        buffer.record(id, MovementPacketBuffer.Kind.POSITION);
        buffer.record(id, MovementPacketBuffer.Kind.LOOK);
        buffer.record(id, MovementPacketBuffer.Kind.POSITION_LOOK);
        buffer.recordServerTeleport(id, "world",
                new MovementPacketBuffer.Position(1, 90, 2),
                new MovementPacketBuffer.Rotation(0, 0), "COMMAND");

        var transport = new MovementPacketTransport(clock::get);
        transport.outboundTeleport(42);
        transport.movement();
        transport.inboundConfirm(42);
        transport.observedOutboundVelocity(7, 7,
                new MovementPacketBuffer.Velocity(0.5, 0, -0.5));

        var movement = MovementPacketDiagnostics.render("Tester", buffer.snapshot(id),
                transport.snapshot(), MovementPacketDiagnostics.View.MOVEMENT, clock.get());
        var network = MovementPacketDiagnostics.render("Tester", buffer.snapshot(id),
                transport.snapshot(), MovementPacketDiagnostics.View.NETWORK, clock.get());
        assertTrue(movement.size() <= 6);
        assertTrue(network.size() <= 6);
        assertTrue(movement.stream().anyMatch(s -> s.contains("Overwritten 1")));
        assertTrue(movement.stream().anyMatch(s -> s.contains("5s truncated")));
        assertTrue(network.stream().anyMatch(s -> s.contains("Velocity wire 1")
                && s.contains("self 1") && s.contains("other 0")));
        assertTrue(network.stream().anyMatch(s -> s.contains("Self entity ID 7")
                && s.contains("last wire ID 7")));
        assertTrue(network.stream().anyMatch(s -> s.contains("TELEPORT (COMMAND)")));
    }

    @Test
    void tickViewStaysCompactAndDoesNotCallRawDeltaCheatEvidence() {
        AtomicLong clock = new AtomicLong(1000);
        var timeline = new MovementTickTimeline(clock::get);
        timeline.observeClientPosition(new MovementPacketBuffer.Position(1, 64, 2));
        clock.addAndGet(20_000_000L);
        timeline.sampleServerTick(1, new MovementTickTimeline.TickInput("world",
                new MovementPacketBuffer.Position(1.1, 64, 2),
                new MovementPacketBuffer.Velocity(0, -0.1, 0),
                new MovementTickTimeline.PlayerFlags(true, false, false, false, false, false),
                new MovementTickTimeline.Environment(true, false, true, false, false)));
        var buffer = new MovementPacketBuffer(8, clock::get);
        var lines = MovementPacketDiagnostics.render("Tester", buffer.snapshot(UUID.randomUUID()),
                null, timeline.snapshot(), MovementPacketDiagnostics.View.TICK, clock.get());
        assertEquals(6, lines.size());
        assertTrue(lines.stream().anyMatch(line -> line.contains("Last pair delta")
                && line.contains("not verdict")));
        var absent = new MovementTickTimeline();
        var empty = MovementPacketDiagnostics.render("Tester", buffer.snapshot(UUID.randomUUID()),
                null, absent.snapshot(), MovementPacketDiagnostics.View.TICK, clock.get());
        assertEquals(2, empty.size());
        assertTrue(empty.get(1).contains("No server tick samples"));
    }

    @Test
    void guardViewKeepsHistoricalCountsAvailableWithoutFloodingChat() {
        AtomicLong clock = new AtomicLong(1);
        var timeline = new MovementTickTimeline(clock::incrementAndGet);
        timeline.sampleServerTick(1, guardTestTick());
        timeline.observeClientPosition(new MovementPacketBuffer.Position(1, 64, 2));
        timeline.markCorrection(MovementTickTimeline.Correction.BUKKIT_TELEPORT);
        timeline.observeClientPosition(new MovementPacketBuffer.Position(2, 64, 2));
        timeline.sampleServerTick(2, guardTestTick());

        var buffer = new MovementPacketBuffer(8, clock::get);
        var lines = MovementPacketDiagnostics.render("HypersMC",
                buffer.snapshot(UUID.randomUUID()), null, timeline.snapshot(),
                MovementPacketDiagnostics.View.GUARDS, clock.get());
        assertEquals(6, lines.size());
        assertTrue(lines.stream().anyMatch(line -> line.contains("Boundaries TP 1")
                && line.contains("Velocity 0")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("Invalidated pending 1")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("Suppressed recovery 1")
                && line.contains("pending TP 0")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("Last guard CORRECTION_WINDOW")));
        assertEquals(MovementPacketDiagnostics.View.GUARDS,
                MovementPacketDiagnostics.View.parse("guards"));

        var none = MovementPacketDiagnostics.render("HypersMC",
                buffer.snapshot(UUID.randomUUID()), null, null,
                MovementPacketDiagnostics.View.GUARDS, clock.get());
        assertEquals(2, none.size());
        assertTrue(none.get(1).contains("unavailable"));
    }

    private static MovementTickTimeline.TickInput guardTestTick() {
        return new MovementTickTimeline.TickInput("world",
                new MovementPacketBuffer.Position(2, 64, 2),
                new MovementPacketBuffer.Velocity(0, 0, 0),
                new MovementTickTimeline.PlayerFlags(true, false, false, false, false, false),
                new MovementTickTimeline.Environment(true, false, true, false, false));
    }

    @Test
    void researchProbeViewIsShortAndDisabledStateIsExplicit() {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        var buffer = new MovementPacketBuffer(16, clock::get);
        var sample = new MovementTickTimeline.TickInput("world",
                new MovementPacketBuffer.Position(1, 64, 2),
                new MovementPacketBuffer.Velocity(0, 0, 0),
                new MovementTickTimeline.PlayerFlags(true, false, false, false, false, false),
                new MovementTickTimeline.Environment(true, false, true, false, false),
                new MovementTickTimeline.ProbeContext(true, true, true, true));
        var probe = new MovementGroundProbe();
        probe.sample(1, 1_000_000_000L, 0, sample, true, true);
        probe.sample(2, 1_050_000_000L, 0, sample, true, true);

        var lines = MovementPacketDiagnostics.render("HypersMC",
                buffer.snapshot(UUID.randomUUID()), null, null, probe.snapshot(),
                MovementPacketDiagnostics.View.PROBE, clock.get());
        assertEquals(6, lines.size());
        assertTrue(lines.stream().anyMatch(line -> line.contains("NOT a cheat check")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("eligible 1")
                && line.contains("no-decision 1")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("RESEARCH_ENVELOPE")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("research outliers 0")));

        var disabled = MovementPacketDiagnostics.render("HypersMC",
                buffer.snapshot(UUID.randomUUID()), null, null, null,
                MovementPacketDiagnostics.View.PROBE, clock.get());
        assertEquals(2, disabled.size());
        assertTrue(disabled.get(1).contains("OFF"));
        assertEquals(MovementPacketDiagnostics.View.PROBE,
                MovementPacketDiagnostics.View.parse("plausibility"));
    }

    @Test
    void probeViewExplainsWhySurvivalOnAPathHasNoEligibleSamples() {
        AtomicLong clock = new AtomicLong(2_000_000_000L);
        var buffer = new MovementPacketBuffer(16, clock::get);
        var probe = new MovementGroundProbe();
        var path = new MovementTickTimeline.TickInput("world",
                new MovementPacketBuffer.Position(1, 64.9375, 2),
                new MovementPacketBuffer.Velocity(0, 0, 0),
                new MovementTickTimeline.PlayerFlags(true, false, false, false, false, false),
                new MovementTickTimeline.Environment(false, false, true, false, false),
                new MovementTickTimeline.ProbeContext(true, false, true, true, "feet=DIRT_PATH"));
        probe.sample(1, 2_000_000_000L, 0, path, true, true);
        probe.sample(2, 2_050_000_000L, 0, path, true, true);
        var lines = MovementPacketDiagnostics.render("HypersMC",
                buffer.snapshot(UUID.randomUUID()), null, null, probe.snapshot(),
                MovementPacketDiagnostics.View.PROBE, clock.get());
        assertEquals(6, lines.size());
        assertTrue(lines.stream().anyMatch(line -> line.contains("terrain skips 1")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("Surface clue: feet=DIRT_PATH")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("No-client 0")));
    }

    @Test
    void profileViewIsSixLinesAndSeparatesWalkSprintStatistics() {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        var probe = new MovementGroundProbe();
        var walk = profileFrame(0, false);
        var walkNext = profileFrame(.25, false);
        var sprint = profileFrame(.5, true);
        var sprintNext = profileFrame(.85, true);
        probe.sample(1, 1_000_000_000L, 0, walk, true, true);
        probe.sample(2, 1_050_000_000L, 0, walkNext, true, true);
        probe.sample(3, 1_100_000_000L, 0, sprint, true, true);
        probe.sample(4, 1_150_000_000L, 0, sprintNext, true, true);

        var buffer = new MovementPacketBuffer(8, clock::get);
        var lines = MovementPacketDiagnostics.render("HypersMC",
                buffer.snapshot(UUID.randomUUID()), null, null, probe.snapshot(),
                MovementPacketDiagnostics.View.PROFILE, clock.get());
        assertEquals(6, lines.size());
        assertTrue(lines.stream().anyMatch(line -> line.contains("NOT a cheat check")));
        assertTrue(lines.stream().anyMatch(line ->
                line.contains("Walk n=1/1") && line.contains("p50 0.250")));
        assertTrue(lines.stream().anyMatch(line ->
                line.contains("Sprint n=1/1") && line.contains("p50 0.350")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("transition skips 1")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("last 128")));
        assertEquals(MovementPacketDiagnostics.View.PROFILE,
                MovementPacketDiagnostics.View.parse("distribution"));

        var disabled = MovementPacketDiagnostics.render("HypersMC",
                buffer.snapshot(UUID.randomUUID()), null, null, null,
                MovementPacketDiagnostics.View.PROFILE, clock.get());
        assertEquals(2, disabled.size());
        assertTrue(disabled.get(1).contains("OFF"));
    }

    private static MovementTickTimeline.TickInput profileFrame(double x, boolean sprinting) {
        return new MovementTickTimeline.TickInput("world",
                new MovementPacketBuffer.Position(x, 64, 2),
                new MovementPacketBuffer.Velocity(.20, 0, 0),
                new MovementTickTimeline.PlayerFlags(true, false, false, false, false, false),
                new MovementTickTimeline.Environment(true, false, true, false, false),
                new MovementTickTimeline.ProbeContext(true, true, true, true, "", sprinting));
    }

    @Test
    void dynamicsViewStaysSixLinesAndNeverCallsMeasuredSlowdownFriction() {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        var probe = new MovementGroundProbe();
        probe.sample(1, 1_000_000_000L, 0, dynamicFrame(0), true, true);
        probe.sample(2, 1_050_000_000L, 0, dynamicFrame(.2), true, true);
        probe.sample(3, 1_100_000_000L, 0, dynamicFrame(.4), true, true);
        probe.sample(4, 1_150_000_000L, 0, dynamicFrame(.7), true, true);

        var packets = new MovementPacketBuffer(8, clock::get);
        var lines = MovementPacketDiagnostics.render("HypersMC",
                packets.snapshot(UUID.randomUUID()), null, null,
                probe.snapshot(), MovementPacketDiagnostics.View.DYNAMICS, clock.get());
        assertEquals(6, lines.size());
        assertTrue(lines.stream().anyMatch(line -> line.contains("Research only")
                && line.contains("eligible 3") && line.contains("vectors 3")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("Last probe")
                && line.contains("block STONE")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("Gate ACCEPTED")
                && line.contains("unknown 0") && line.contains("changed 0")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("Accel 1")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("Steady 1")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("slowdown")));
        assertEquals(MovementPacketDiagnostics.View.DYNAMICS,
                MovementPacketDiagnostics.View.parse("dyn"));
        assertEquals(MovementPacketDiagnostics.View.DYNAMICS,
                MovementPacketDiagnostics.View.parse("motion"));

        var disabled = MovementPacketDiagnostics.render("HypersMC",
                packets.snapshot(UUID.randomUUID()), null, null, null,
                MovementPacketDiagnostics.View.DYNAMICS, clock.get());
        assertEquals(2, disabled.size());
        assertTrue(disabled.get(1).contains("OFF"));
    }

    @Test
    void zeroDynamicVectorsAreExplainedInsteadOfLookingLikeSuccessfulMeasurements() {
        var probe = new MovementGroundProbe();
        var fixture = new MovementTickTimeline.TickInput("world",
                new MovementPacketBuffer.Position(0, 64, 2),
                new MovementPacketBuffer.Velocity(.2, 0, 0),
                new MovementTickTimeline.PlayerFlags(true, false, false, false, false, false),
                new MovementTickTimeline.Environment(true, false, true, false, false),
                // Old fixture has no known ground material: profile can count
                // it, but dynamics must refuse a cross-material prediction.
                new MovementTickTimeline.ProbeContext(true, true, true, true));
        var moved = new MovementTickTimeline.TickInput("world",
                new MovementPacketBuffer.Position(.2, 64, 2),
                fixture.velocity(), fixture.flags(), fixture.environment(), fixture.probeContext());
        probe.sample(1, 50_000_000L, 0, fixture, true, true);
        probe.sample(2, 100_000_000L, 0, moved, true, true);
        var packets = new MovementPacketBuffer(8);
        var lines = MovementPacketDiagnostics.render("HypersMC",
                packets.snapshot(UUID.randomUUID()), null, null, probe.snapshot(),
                MovementPacketDiagnostics.View.DYNAMICS, System.nanoTime());
        assertEquals(6, lines.size());
        assertTrue(lines.stream().anyMatch(line -> line.contains("eligible 1")
                && line.contains("vectors 0")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("UNKNOWN_MATERIAL")
                && line.contains("unknown 1")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("block UNKNOWN")));
    }

    private static MovementTickTimeline.TickInput dynamicFrame(double x) {
        return new MovementTickTimeline.TickInput("world",
                new MovementPacketBuffer.Position(x, 64, 2),
                new MovementPacketBuffer.Velocity(.2, 0, 0),
                new MovementTickTimeline.PlayerFlags(true, false, false, false, false, false),
                new MovementTickTimeline.Environment(true, false, true, false, false),
                new MovementTickTimeline.ProbeContext(true, true, true, true,
                        "", false, "STONE"));
    }

    @Test
    void idleViewShowsServerOriginAndPacketFreshnessWithoutSuggestingKeyRelease() {
        var probe = new MovementGroundProbe();
        for (int tick = 1; tick <= 7; tick++) {
            double x = tick <= 3 ? 0 : tick <= 5 ? (tick - 3) * .2 : .4;
            boolean hasClientPacket = tick == 4 || tick == 5;
            probe.sample(tick, tick * 50_000_000L, 0,
                    dynamicFrame(x), true, hasClientPacket);
        }
        var packets = new MovementPacketBuffer(8);
        var lines = MovementPacketDiagnostics.render("HypersMC",
                packets.snapshot(UUID.randomUUID()), null, null, probe.snapshot(),
                MovementPacketDiagnostics.View.IDLE, System.nanoTime());
        assertEquals(6, lines.size());
        assertTrue(lines.stream().anyMatch(line -> line.contains("SERVER samples")
                && line.contains("NOT client key presses")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("Observed starts 1")
                && line.contains("stops 1")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("stops w/o recent packet 1")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("State IDLE")
                && line.contains("STONE")));
        assertEquals(MovementPacketDiagnostics.View.IDLE,
                MovementPacketDiagnostics.View.parse("startstop"));

        var disabled = MovementPacketDiagnostics.render("HypersMC",
                packets.snapshot(UUID.randomUUID()), null, null, null,
                MovementPacketDiagnostics.View.IDLE, System.nanoTime());
        assertEquals(2, disabled.size());
        assertTrue(disabled.get(1).contains("OFF"));
    }

    @Test
    void rawPacketPredictionViewNamesActualSourceAndAvoidsVerdicts() {
        var model = new PacketGroundReachability(
                PacketGroundReachability.Profile.forMinecraft(
                        me.hackerguardian.compat.MinecraftVersion.parse("1.20.2")));
        var input = new MovementTickTimeline.TickInput("world",
                new MovementPacketBuffer.Position(0, 64, 0),
                new MovementPacketBuffer.Velocity(0, 0, 0),
                new MovementTickTimeline.PlayerFlags(true, false, false, false, false, false),
                new MovementTickTimeline.Environment(true, false, true, false, false),
                new MovementTickTimeline.ProbeContext(true, true, true, true, "", false, "STONE"));
        for (int tick = 1; tick <= 3; tick++) {
            model.observe(new MovementPacketBuffer.PacketState(
                    new MovementPacketBuffer.Position(.2 * (tick - 1), 64, 0),
                    null, true, false, true),
                    new PacketGroundReachability.Context(tick, 0, true, false, input));
        }

        var buffer = new MovementPacketBuffer(8);
        var output = MovementPacketDiagnostics.render("HypersMC",
                buffer.snapshot(UUID.randomUUID()), null, null, null,
                model.snapshot(), MovementPacketDiagnostics.View.PREDICT, System.nanoTime());
        assertEquals(6, output.size());
        assertTrue(output.stream().anyMatch(line -> line.contains("Real client POSITION packets")
                && line.contains("research ONLY")));
        assertTrue(output.stream().anyMatch(line -> line.contains("Profile 1.20.x")
                && line.contains("modeled 1")));
        assertTrue(output.stream().anyMatch(line -> line.contains("CONSERVATIVE_REACHABLE")
                && line.contains("outside research 0")));
        assertTrue(output.stream().anyMatch(line -> line.contains("excess")));
        assertEquals(MovementPacketDiagnostics.View.PREDICT,
                MovementPacketDiagnostics.View.parse("pktphysics"));

        var off = MovementPacketDiagnostics.render("HypersMC",
                buffer.snapshot(UUID.randomUUID()), null, null, null,
                null, MovementPacketDiagnostics.View.PREDICT, System.nanoTime());
        assertEquals(2, off.size());
        assertTrue(off.get(1).contains("OFF"));
    }

    @Test
    void packetResidualViewIsCompactAndUsesOnlyActualPacketResearchData() {
        var model = new PacketGroundReachability(
                PacketGroundReachability.Profile.forMinecraft(
                        me.hackerguardian.compat.MinecraftVersion.parse("1.20.2")));
        var input = new MovementTickTimeline.TickInput("world",
                new MovementPacketBuffer.Position(0, 64, 0),
                new MovementPacketBuffer.Velocity(0, 0, 0),
                new MovementTickTimeline.PlayerFlags(true, false, false, false, false, false),
                new MovementTickTimeline.Environment(true, false, true, false, false),
                new MovementTickTimeline.ProbeContext(true, true, true, true,
                        "", false, "STONE"));
        var sprint = new MovementTickTimeline.TickInput("world",
                input.position(), input.velocity(), input.flags(), input.environment(),
                new MovementTickTimeline.ProbeContext(true, true, true, true,
                        "", true, "STONE"));
        for (int t = 1; t <= 4; t++) {
            model.observe(new MovementPacketBuffer.PacketState(
                    new MovementPacketBuffer.Position(.2 * (t - 1), 64, 0),
                    null, true, false, true),
                    new PacketGroundReachability.Context(t, 0, true, false, input));
        }
        for (int t = 5; t <= 8; t++) {
            model.observe(new MovementPacketBuffer.PacketState(
                    new MovementPacketBuffer.Position(.8 + .28 * (t - 4), 64, 0),
                    null, true, false, true),
                    new PacketGroundReachability.Context(t, 0, true, false, sprint));
        }

        var buffer = new MovementPacketBuffer(8);
        var lines = MovementPacketDiagnostics.render("HypersMC",
                buffer.snapshot(UUID.randomUUID()), null, null, null,
                model.snapshot(), MovementPacketDiagnostics.View.RESIDUALS, System.nanoTime());
        assertEquals(6, lines.size(), "Residuals should not flood Minecraft chat");
        assertTrue(lines.stream().anyMatch(l -> l.contains("STEADY packet input residual")
                && l.contains("research ONLY")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("Walk n=2/2")
                && l.contains("r50") && l.contains("r95") && l.contains("max")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("Sprint n=0")
                && l.contains("no modeled triples")),
                "Initial post-switch samples must be separated from steady sprinting");
        assertTrue(lines.stream().anyMatch(l -> l.contains("Outside W 0 S 0")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("Switch tails excluded W 0 S 2")
                && l.contains("no-decision 4")));
        assertEquals(MovementPacketDiagnostics.View.RESIDUALS,
                MovementPacketDiagnostics.View.parse("res"));
        assertEquals(MovementPacketDiagnostics.View.RESIDUALS,
                MovementPacketDiagnostics.View.parse("predictstats"));

        var off = MovementPacketDiagnostics.render("HypersMC",
                buffer.snapshot(UUID.randomUUID()), null, null, null,
                null, MovementPacketDiagnostics.View.RESIDUALS, System.nanoTime());
        assertEquals(2, off.size());
        assertTrue(off.get(1).contains("OFF"));
    }

    @Test
    void packetSprintTransitionViewIsCompactAndLabelsSourceOfSprintState() {
        var model = new PacketGroundReachability(
                PacketGroundReachability.Profile.forMinecraft(
                        me.hackerguardian.compat.MinecraftVersion.parse("1.20.2")));
        var first = new MovementTickTimeline.TickInput("world",
                new MovementPacketBuffer.Position(0, 64, 0),
                new MovementPacketBuffer.Velocity(0, 0, 0),
                new MovementTickTimeline.PlayerFlags(true, false, false, false, false, false),
                new MovementTickTimeline.Environment(true, false, true, false, false),
                new MovementTickTimeline.ProbeContext(true, true, true, true,
                        "", false, "STONE"));
        var sprint = new MovementTickTimeline.TickInput("world",
                first.position(), first.velocity(), first.flags(), first.environment(),
                new MovementTickTimeline.ProbeContext(true, true, true, true,
                        "", true, "STONE"));
        for (int i = 1; i <= 4; i++) {
            model.observe(new MovementPacketBuffer.PacketState(
                    new MovementPacketBuffer.Position(.216 * i, 64, 0),
                    null, true, false, true),
                    new PacketGroundReachability.Context(i, 0, true, false, first));
        }
        for (int i = 5; i <= 8; i++) {
            model.observe(new MovementPacketBuffer.PacketState(
                    new MovementPacketBuffer.Position(.864 + .281 * (i - 4), 64, 0),
                    null, true, false, true),
                    new PacketGroundReachability.Context(i, 0, true, false, sprint));
        }
        var buffer = new MovementPacketBuffer(16);
        var lines = MovementPacketDiagnostics.render("HypersMC",
                buffer.snapshot(UUID.randomUUID()), null, null, null,
                model.snapshot(), MovementPacketDiagnostics.View.SWITCHES,
                System.nanoTime());
        assertEquals(6, lines.size());
        assertTrue(lines.stream().anyMatch(line -> line.contains("Bukkit sprint label")
                && line.contains("RESEARCH")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("Walk>sprint 1")
                && line.contains("Sprint>walk 0")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("W>S n2")
                && line.contains("r95 0.128")
                && line.contains("d95 0.281")
                && line.contains("near0 0")
                && line.contains("drag~ 0")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("S>W n0")
                && line.contains("r95 n/a") && line.contains("d95 n/a")));
        assertEquals(MovementPacketDiagnostics.View.SWITCHES,
                MovementPacketDiagnostics.View.parse("trans"));

        var disabled = MovementPacketDiagnostics.render("HypersMC",
                buffer.snapshot(UUID.randomUUID()), null, null, null,
                null, MovementPacketDiagnostics.View.SWITCHES,
                System.nanoTime());
        assertEquals(2, disabled.size());
        assertTrue(disabled.get(1).contains("OFF"));
    }

    @Test
    void packetHeadingViewExplainsTruePositionAnglesAndNoDecisionReasons() {
        var model = new PacketGroundReachability(
                PacketGroundReachability.Profile.forMinecraft(
                        me.hackerguardian.compat.MinecraftVersion.parse("1.20.2")));
        var serverState = new MovementTickTimeline.TickInput("world",
                new MovementPacketBuffer.Position(0, 64, 0),
                new MovementPacketBuffer.Velocity(0, 0, 0),
                new MovementTickTimeline.PlayerFlags(true, false, false, false, false, false),
                new MovementTickTimeline.Environment(true, false, true, false, false),
                new MovementTickTimeline.ProbeContext(true, true, true, true,
                        "", false, "STONE"));
        model.observe(new MovementPacketBuffer.PacketState(
                new MovementPacketBuffer.Position(0, 64, 0),
                null, true, false, true),
                new PacketGroundReachability.Context(1, 0, true, false, serverState));
        model.observe(new MovementPacketBuffer.PacketState(
                new MovementPacketBuffer.Position(.2, 64, 0),
                null, true, false, true),
                new PacketGroundReachability.Context(2, 0, true, false, serverState));
        model.observe(new MovementPacketBuffer.PacketState(
                new MovementPacketBuffer.Position(.2, 64, .2),
                null, true, false, true),
                new PacketGroundReachability.Context(3, 0, true, false, serverState));

        var packetBuffer = new MovementPacketBuffer(8);
        var lines = MovementPacketDiagnostics.render("HypersMC",
                packetBuffer.snapshot(UUID.randomUUID()), null, null, null,
                model.snapshot(), MovementPacketDiagnostics.View.HEADING, System.nanoTime());
        assertEquals(6, lines.size());
        assertTrue(lines.stream().anyMatch(line -> line.contains("Real client packet XZ headings")
                && line.contains("NOT camera yaw")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("Angles 1/1")
                && line.contains("p95 90.000") && line.contains("max 90.000")));
        assertTrue(lines.stream().anyMatch(line -> line.contains(">=35deg 1")
                && line.contains("too-slow 0")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("Modeled 1")
                && line.contains("no-decision 2") && line.contains("timing 0")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("warmup 2")
                && line.contains("unavailable 0")));
        assertEquals(MovementPacketDiagnostics.View.HEADING,
                MovementPacketDiagnostics.View.parse("heading"));
        assertEquals(MovementPacketDiagnostics.View.HEADING,
                MovementPacketDiagnostics.View.parse("angles"));

        var disabled = MovementPacketDiagnostics.render("HypersMC",
                packetBuffer.snapshot(UUID.randomUUID()), null, null, null,
                null, MovementPacketDiagnostics.View.HEADING, System.nanoTime());
        assertEquals(2, disabled.size());
        assertTrue(disabled.get(1).contains("OFF"));

        var unsupported = MovementPacketDiagnostics.render("HypersMC",
                packetBuffer.snapshot(UUID.randomUUID()), null, null, null,
                new PacketGroundReachability(null).snapshot(),
                MovementPacketDiagnostics.View.HEADING, System.nanoTime());
        assertEquals(2, unsupported.size());
        assertTrue(unsupported.get(1).contains("unavailable"));
    }

    @Test
    void missingTransportIsReportedWithoutInventingNetworkSuccess() {
        UUID id = UUID.randomUUID();
        var buffer = new MovementPacketBuffer(2);
        var lines = MovementPacketDiagnostics.render("Test", buffer.snapshot(id), null,
                MovementPacketDiagnostics.View.SUMMARY, System.nanoTime());
        assertTrue(lines.stream().anyMatch(s -> s.contains("Teleport tracking unavailable")));
        assertEquals(5, lines.size());
    }

    @Test
    void unknownModesAreRejectedAndAliasesAccepted() {
        assertEquals(MovementPacketDiagnostics.View.SUMMARY,
                MovementPacketDiagnostics.View.parse(null));
        assertEquals(MovementPacketDiagnostics.View.SUMMARY,
                MovementPacketDiagnostics.View.parse("summary"));
        assertEquals(MovementPacketDiagnostics.View.MOVEMENT,
                MovementPacketDiagnostics.View.parse("MOVE"));
        assertEquals(MovementPacketDiagnostics.View.NETWORK,
                MovementPacketDiagnostics.View.parse("net"));
        assertEquals(MovementPacketDiagnostics.View.TICK,
                MovementPacketDiagnostics.View.parse("tick"));
        assertEquals(MovementPacketDiagnostics.View.GUARDS,
                MovementPacketDiagnostics.View.parse("safety"));
        assertNull(MovementPacketDiagnostics.View.parse("everything"));
    }
}
