package me.hackerguardian.main.detection.packet;

import me.hackerguardian.compat.MinecraftVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PacketPassiveMovementContextTest {
    private static MovementTickTimeline.TickInput server(String world, double x, boolean sprint) {
        return new MovementTickTimeline.TickInput(world,
                new MovementPacketBuffer.Position(x, 64, 0),
                new MovementPacketBuffer.Velocity(0, 0, 0),
                new MovementTickTimeline.PlayerFlags(true, false, false, false, false, false),
                new MovementTickTimeline.Environment(true, false, true, false, false),
                new MovementTickTimeline.ProbeContext(true, true, true, true,
                        "flat", sprint, "STONE"));
    }

    private static PacketGroundReachability.Context ctx(long tick, long epoch,
                                                         double x, boolean sprint) {
        return new PacketGroundReachability.Context(tick, epoch, true, false,
                server("world", x, sprint), true, 10_000_000L);
    }

    private static MovementPacketBuffer.PacketState packet(double x) {
        return new MovementPacketBuffer.PacketState(
                new MovementPacketBuffer.Position(x, 64, 0), null, true, false, true);
    }

    @Test
    void quietServerWhileNoPositionPacketsExplainsHintGapsWithoutInferringLoss() {
        var study = new PacketPassiveMovementContext();
        study.sampleServerTick(10, 0, server("world", 0, false));
        study.observePacket(packet(0), ctx(10, 0, 0, false),
                PacketGroundReachability.Status.WARMUP);
        for (int tick = 11; tick <= 16; tick++) {
            study.sampleServerTick(tick, 0, server("world", 0, false));
        }
        study.observePacket(packet(0), ctx(16, 0, 0, false),
                PacketGroundReachability.Status.TICK_GAP);
        var stats = study.snapshot();
        assertEquals(6, stats.serverIntervals());
        assertEquals(5, stats.noPositionIntervals());
        assertEquals(5, stats.noPositionQuiet());
        assertEquals(0, stats.noPositionMoving());
        assertEquals(5, stats.maxNoPositionRun());
        assertEquals(1, stats.hintGapPairs());
        assertEquals(1, stats.quietHintGaps());
        assertEquals(1, stats.quietModelGaps());
        assertEquals(1, stats.quietPacketPairs());
        assertEquals(stats, study.snapshot(), "Staff reads must never close live runs");
    }

    @Test
    void serverDisplacementClassifiesGapAsMovingEvenWithFewPositionPackets() {
        var study = new PacketPassiveMovementContext();
        study.sampleServerTick(1, 0, server("world", 0, false));
        study.observePacket(packet(0), ctx(1, 0, 0, false),
                PacketGroundReachability.Status.WARMUP);
        study.sampleServerTick(2, 0, server("world", .2, false));
        study.sampleServerTick(3, 0, server("world", .4, false));
        study.sampleServerTick(4, 0, server("world", .6, false));
        study.observePacket(packet(.6), ctx(4, 0, .6, false),
                PacketGroundReachability.Status.TICK_GAP);
        var stats = study.snapshot();
        assertEquals(2, stats.noPositionMoving());
        assertEquals(1, stats.walkingPacketPairs());
        assertEquals(1, stats.movingHintGaps());
        assertEquals(1, stats.movingModelGaps());
        assertEquals(0, stats.quietHintGaps());
    }

    @Test
    void packetPairsSeparateWalkSprintSwitchAndUnchangedPosition() {
        var study = new PacketPassiveMovementContext();
        study.sampleServerTick(1, 0, server("world", 0, false));
        study.observePacket(packet(0), ctx(1, 0, 0, false),
                PacketGroundReachability.Status.WARMUP);
        study.sampleServerTick(2, 0, server("world", .2, false));
        study.observePacket(packet(.2), ctx(2, 0, .2, false),
                PacketGroundReachability.Status.WARMUP);
        study.sampleServerTick(3, 0, server("world", .5, true));
        study.observePacket(packet(.5), ctx(3, 0, .5, true),
                PacketGroundReachability.Status.CONTEXT_CHANGED);
        study.sampleServerTick(4, 0, server("world", .8, true));
        study.observePacket(packet(.8), ctx(4, 0, .8, true),
                PacketGroundReachability.Status.WARMUP);
        study.sampleServerTick(5, 0, server("world", .8, true));
        study.observePacket(packet(.8), ctx(5, 0, .8, true),
                PacketGroundReachability.Status.CONSERVATIVE_REACHABLE);

        var s = study.snapshot();
        assertEquals(4, s.packetPairs());
        assertEquals(1, s.walkingPacketPairs());
        assertEquals(1, s.sprintingPacketPairs());
        assertEquals(1, s.sprintChangePacketPairs());
        assertEquals(1, s.quietPacketPairs());
        assertEquals(0, s.hintGapPairs());
    }

    @Test
    void skippedServerSamplesRemainUnknownRatherThanDeclaredQuietOrMoving() {
        var study = new PacketPassiveMovementContext();
        study.sampleServerTick(1, 0, server("world", 0, false));
        study.observePacket(packet(0), ctx(1, 0, 0, false),
                PacketGroundReachability.Status.WARMUP);
        study.sampleServerTick(9, 0, server("world", 0, false));
        study.observePacket(packet(0), ctx(9, 0, 0, false),
                PacketGroundReachability.Status.TICK_GAP);
        var s = study.snapshot();
        // A decoded position was seen during this sampled interval; it is
        // therefore NOT a no-position interval. The skipped samples still
        // prevent a quiet/moving attribution of the subsequent hint gap.
        assertEquals(0, s.noPositionIntervals());
        assertEquals(0, s.noPositionUnknown());
        assertEquals(1, s.unknownHintGaps());
        assertEquals(1, s.unknownModelGaps());
    }

    @Test
    void correctionsFenceBothClientPairsAndServerSampleComparisons() {
        var study = new PacketPassiveMovementContext();
        study.sampleServerTick(1, 0, server("world", 0, false));
        study.observePacket(packet(0), ctx(1, 0, 0, false),
                PacketGroundReachability.Status.WARMUP);
        study.sampleServerTick(2, 0, server("world", 0, false));
        study.sampleServerTick(3, 0, server("world", 0, false));
        assertEquals(1, study.snapshot().liveNoPositionRun());
        study.correction();
        study.sampleServerTick(200, 1, server("other", 1000, false));
        study.observePacket(packet(1000), new PacketGroundReachability.Context(
                200, 1, true, false, server("other", 1000, false)),
                PacketGroundReachability.Status.RECENT_CORRECTION);
        assertEquals(0, study.snapshot().hintGapPairs());
        assertEquals(0, study.snapshot().liveNoPositionRun());
        study.sampleServerTick(201, 1, server("other", 1000, false));
        assertEquals(0, study.snapshot().noPositionUnknown());
        assertEquals(1, study.snapshot().noPositionQuiet());
    }

    @Test
    void missingPositionOrModelGapWithoutComparableHintsIsUnknown() {
        var study = new PacketPassiveMovementContext();
        study.observePacket(MovementPacketBuffer.PacketState.missing(), null,
                PacketGroundReachability.Status.TICK_GAP);
        assertEquals(1, study.snapshot().actualModelGaps());
        assertEquals(1, study.snapshot().unknownModelGaps());
        assertEquals(0, study.snapshot().hintGapPairs());
    }

    @Test
    void passiveSamplesNeverAlterTheOriginalPacketModelOutcome() {
        var version = MinecraftVersion.parse("1.21.11");
        var model = new PacketGroundReachability(
                PacketGroundReachability.Profile.forMinecraft(version));
        for (int tick = 1; tick <= 3; tick++) {
            model.observeServerTick(tick, 0, server("world", tick * .2, false));
            model.observe(packet(tick * .2), ctx(tick, 0, tick * .2, false));
        }
        assertEquals(1, model.snapshot().modeledTriples());
        assertEquals(PacketGroundReachability.Status.CONSERVATIVE_REACHABLE,
                model.snapshot().lastStatus());
        assertEquals(2, model.snapshot().activity().packetPairs());
        assertEquals(0, model.snapshot().activity().actualModelGaps());
        assertEquals(MovementPacketDiagnostics.View.ACTIVITY,
                MovementPacketDiagnostics.View.parse("activity"));
    }
}
