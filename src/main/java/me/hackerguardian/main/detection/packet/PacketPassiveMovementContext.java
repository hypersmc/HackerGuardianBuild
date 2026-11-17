package me.hackerguardian.main.detection.packet;

/**
 * Observe-only passive context for client POSITION/POSITION_LOOK packet research.
 *
 * Uses decoded incoming client positions to describe packet-pair displacement,
 * plus immutable Bukkit-main-thread server position samples to describe
 * intervals without a received position packet. Does NOT infer client ticks,
 * client input keys, dropped network packets, or proof that a player was idle.
 *
 * All calls are serialized by the existing player Session monitor. Memory and
 * per-callback work are constant; no Bukkit APIs and no enforcement coupling.
 */
public final class PacketPassiveMovementContext {
    private static final double SERVER_XZ_QUIET = 0.01;
    private static final double CLIENT_XZ_QUIET = 0.015;

    public record Snapshot(
            long serverIntervals, long noPositionIntervals,
            long noPositionQuiet, long noPositionMoving, long noPositionUnknown,
            long maxNoPositionRun, long liveNoPositionRun,
            long packetPairs, long quietPacketPairs,
            long walkingPacketPairs, long sprintingPacketPairs,
            long sprintChangePacketPairs, long unknownPacketPairs,
            long hintGapPairs, long quietHintGaps, long movingHintGaps,
            long unknownHintGaps, long actualModelGaps,
            long quietModelGaps, long movingModelGaps, long unknownModelGaps) {}

    private enum SampleMotion { QUIET, MOVING, UNKNOWN }
    private enum IntervalContext { QUIET, MOVING, UNKNOWN }

    private long serverIntervals, noPositionIntervals, noPositionQuiet;
    private long noPositionMoving, noPositionUnknown, maxNoPositionRun, liveNoPositionRun;
    private long packetPairs, quietPacketPairs, walkingPacketPairs;
    private long sprintingPacketPairs, sprintChangePacketPairs, unknownPacketPairs;
    private long hintGapPairs, quietHintGaps, movingHintGaps, unknownHintGaps;
    private long modelGaps, quietModelGaps, movingModelGaps, unknownModelGaps;

    private Long sampledTick;
    private long sampledEpoch;
    private String sampledWorld;
    private MovementPacketBuffer.Position sampledServerPosition;
    private boolean receivedPositionSinceSample;

    private MovementPacketBuffer.Position previousPacketPosition;
    private Long previousPacketTick;
    private long previousPacketEpoch;
    private String previousPacketWorld;
    private boolean previousSprint;

    // Server-sample movement accumulated since the last observed client position.
    // This is provenance about Bukkit-sampled coordinates, not client velocity.
    private boolean sawContinuousSampleSincePacket;
    private boolean sawServerMovementSincePacket;
    private boolean unavailableSincePacket;

    /** Called on the Bukkit thread, after its immutable state has been sampled. */
    public void sampleServerTick(long tick, long epoch, MovementTickTimeline.TickInput input) {
        if (input == null || tick <= 0) return;
        if (sampledTick != null) {
            boolean contiguous = tick == sampledTick + 1 && epoch == sampledEpoch
                    && input.worldKey().equals(sampledWorld);
            SampleMotion motion = contiguous
                    ? motion(sampledServerPosition, input.position()) : SampleMotion.UNKNOWN;
            serverIntervals++;
            if (!receivedPositionSinceSample) {
                noPositionIntervals++;
                liveNoPositionRun++;
                maxNoPositionRun = Math.max(maxNoPositionRun, liveNoPositionRun);
                switch (motion) {
                    case QUIET -> noPositionQuiet++;
                    case MOVING -> noPositionMoving++;
                    case UNKNOWN -> noPositionUnknown++;
                }
            } else {
                liveNoPositionRun = 0;
            }
            if (previousPacketTick != null) {
                if (!contiguous) {
                    unavailableSincePacket = true;
                } else {
                    sawContinuousSampleSincePacket = true;
                    if (motion == SampleMotion.MOVING) sawServerMovementSincePacket = true;
                    if (motion == SampleMotion.UNKNOWN) unavailableSincePacket = true;
                }
            }
        }
        sampledTick = tick;
        sampledEpoch = epoch;
        sampledWorld = input.worldKey();
        sampledServerPosition = input.position();
        receivedPositionSinceSample = false;
    }

    /**
     * Called once after the original packet model outcome has been computed.
     * Classification is deliberately independent of model eligibility.
     */
    public void observePacket(MovementPacketBuffer.PacketState packet,
                              PacketGroundReachability.Context context,
                              PacketGroundReachability.Status outcome) {
        boolean validPosition = packet != null && packet.decoded() && packet.position() != null;
        if (validPosition) receivedPositionSinceSample = true;

        boolean validContext = context != null && context.serverState() != null
                && context.serverTick() > 0 && context.serverState().probeContext() != null;
        String world = validContext ? context.serverState().worldKey() : null;
        boolean comparable = validPosition && validContext
                && previousPacketPosition != null && previousPacketTick != null
                && context.correctionEpoch() == previousPacketEpoch
                && world != null && world.equals(previousPacketWorld)
                && context.serverTick() >= previousPacketTick;

        IntervalContext gapContext = IntervalContext.UNKNOWN;
        if (comparable) {
            packetPairs++;
            var current = packet.position();
            double distance = Math.hypot(current.x() - previousPacketPosition.x(),
                    current.z() - previousPacketPosition.z());
            boolean sprint = context.serverState().probeContext().sprinting();
            if (!Double.isFinite(distance)) {
                unknownPacketPairs++;
            } else if (sprint != previousSprint) {
                sprintChangePacketPairs++;
            } else if (distance <= CLIENT_XZ_QUIET) {
                quietPacketPairs++;
            } else if (sprint) {
                sprintingPacketPairs++;
            } else {
                walkingPacketPairs++;
            }

            if (context.serverTick() > previousPacketTick + 1) {
                gapContext = intervalContext();
                hintGapPairs++;
                switch (gapContext) {
                    case QUIET -> quietHintGaps++;
                    case MOVING -> movingHintGaps++;
                    case UNKNOWN -> unknownHintGaps++;
                }
            }
        }

        if (outcome == PacketGroundReachability.Status.TICK_GAP) {
            modelGaps++;
            // A model TICK_GAP can follow a warmup/correction or an invalid
            // claim; direct hint-pair provenance might then be unavailable.
            if (!comparable || previousPacketTick == null
                    || context.serverTick() <= previousPacketTick + 1) {
                gapContext = IntervalContext.UNKNOWN;
            }
            switch (gapContext) {
                case QUIET -> quietModelGaps++;
                case MOVING -> movingModelGaps++;
                case UNKNOWN -> unknownModelGaps++;
            }
        }

        if (validPosition && validContext) {
            previousPacketPosition = packet.position();
            previousPacketTick = context.serverTick();
            previousPacketEpoch = context.correctionEpoch();
            previousPacketWorld = world;
            previousSprint = context.serverState().probeContext().sprinting();
        } else {
            clearPacketPair();
        }
        sawContinuousSampleSincePacket = false;
        sawServerMovementSincePacket = false;
        unavailableSincePacket = false;
    }

    private IntervalContext intervalContext() {
        if (!sawContinuousSampleSincePacket || unavailableSincePacket) {
            return IntervalContext.UNKNOWN;
        }
        return sawServerMovementSincePacket ? IntervalContext.MOVING : IntervalContext.QUIET;
    }

    private static SampleMotion motion(MovementPacketBuffer.Position before,
                                       MovementPacketBuffer.Position after) {
        if (before == null || after == null) return SampleMotion.UNKNOWN;
        double distance = Math.hypot(after.x() - before.x(), after.z() - before.z());
        if (!Double.isFinite(distance)) return SampleMotion.UNKNOWN;
        return distance <= SERVER_XZ_QUIET ? SampleMotion.QUIET : SampleMotion.MOVING;
    }

    /**
     * Corrections terminate provenance comparisons, never delete totals.
     * The following sample is unknown until a new contiguous sample pair.
     */
    public void correction() {
        clearPacketPair();
        sampledTick = null;
        sampledServerPosition = null;
        sampledWorld = null;
        receivedPositionSinceSample = false;
        liveNoPositionRun = 0;
        sawContinuousSampleSincePacket = false;
        sawServerMovementSincePacket = false;
        unavailableSincePacket = false;
    }

    private void clearPacketPair() {
        previousPacketPosition = null;
        previousPacketTick = null;
        previousPacketWorld = null;
    }

    public Snapshot snapshot() {
        return new Snapshot(serverIntervals, noPositionIntervals, noPositionQuiet,
                noPositionMoving, noPositionUnknown, maxNoPositionRun, liveNoPositionRun,
                packetPairs, quietPacketPairs, walkingPacketPairs, sprintingPacketPairs,
                sprintChangePacketPairs, unknownPacketPairs, hintGapPairs, quietHintGaps,
                movingHintGaps, unknownHintGaps, modelGaps, quietModelGaps,
                movingModelGaps, unknownModelGaps);
    }
}
