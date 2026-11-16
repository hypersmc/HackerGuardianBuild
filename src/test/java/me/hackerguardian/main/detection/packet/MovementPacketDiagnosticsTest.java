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
        transport.outboundSelfVelocity(new MovementPacketBuffer.Velocity(0.5, 0, -0.5));

        var movement = MovementPacketDiagnostics.render("Tester", buffer.snapshot(id),
                transport.snapshot(), MovementPacketDiagnostics.View.MOVEMENT, clock.get());
        var network = MovementPacketDiagnostics.render("Tester", buffer.snapshot(id),
                transport.snapshot(), MovementPacketDiagnostics.View.NETWORK, clock.get());
        assertTrue(movement.size() <= 6);
        assertTrue(network.size() <= 6);
        assertTrue(movement.stream().anyMatch(s -> s.contains("Overwritten 1")));
        assertTrue(movement.stream().anyMatch(s -> s.contains("5s truncated")));
        assertTrue(network.stream().anyMatch(s -> s.contains("Last matched ID 42")));
        assertTrue(network.stream().anyMatch(s -> s.contains("Self velocity 1")));
        assertTrue(network.stream().anyMatch(s -> s.contains("TELEPORT (COMMAND)")));
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
        assertNull(MovementPacketDiagnostics.View.parse("everything"));
    }
}
