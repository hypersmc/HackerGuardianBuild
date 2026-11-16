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
