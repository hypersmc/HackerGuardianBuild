package me.hackerguardian.main.detection.packet;

import me.hackerguardian.compat.MinecraftVersion;

/**
 * Experimental client-PACKET-driven horizontal ground reachability study.
 *
 * Unlike the Bukkit server-position profile, the measured motion is taken
 * ONLY from decoded incoming POSITION / POSITION_LOOK packet coordinates.
 * Bukkit samples are eligibility hints, not the measured displacement.
 *
 * This is NOT a validated Minecraft simulator: the model uses a conservative
 * unknown-input disk and a research-only uncertainty margin. A scheduler tick
 * hint is not a client simulation tick; packets arriving more than once per
 * server tick, or skipping server ticks, make the model undecidable.
 *
 * No findings, reports, scoring, training labels, alerts or punishments.
 * Fixed memory, constant work per packet, no Bukkit/NMS access.
 */
public final class PacketGroundReachability {
    private static final double MAX_PACKET_VERTICAL_CHANGE = 0.03;
    private static final double MAX_PREVIOUS_HORIZONTAL = 0.80;

    /** Minimum margin is deliberately wider than rounding error and is not
     *  a detection threshold. Real latency/collision uncertainty is unmodeled.
     */
    private static final double RESEARCH_UNCERTAINTY = 0.10;
    private static final long MAX_SERVER_SAMPLE_AGE_NS = 125_000_000L;

    public enum Status {
        DISABLED_VERSION, NO_SERVER_SAMPLE, UNSUPPORTED_CONTEXT, STALE_SERVER_SAMPLE,
        RECENT_CORRECTION, INVALID_GROUND_CLAIM, AMBIGUOUS_TICK,
        TICK_GAP, CONTEXT_CHANGED, WARMUP, CONSERVATIVE_REACHABLE,
        OUTSIDE_RESEARCH_ENVELOPE
    }

    /**
     * Families are separately registered, even where their initial research
     * constants agree. Never assume a newly released protocol is identical.
     */
    public record Profile(String family, double ordinaryDrag,
                          double maxUnknownInputAcceleration,
                          double uncertainty) {
        public static Profile forMinecraft(MinecraftVersion version) {
            if (version == null || !version.isVerifiedSupported()) return null;
            return switch (version.family()) {
                case MC_1_20 -> new Profile("1.20.x", .546, .1274, RESEARCH_UNCERTAINTY);
                case MC_1_21 -> new Profile("1.21.x", .546, .1274, RESEARCH_UNCERTAINTY);
                // 26.x and future versions remain unavailable until the
                // physics and packet timing are separately characterized.
                default -> null;
            };
        }
    }

    /**
     * Main-thread server context must be copied into this immutable record
     * before any asynchronous packet callback. No Bukkit Player references.
     */
    public record Context(long serverTick, long correctionEpoch,
                          boolean settled, boolean pendingTeleport,
                          MovementTickTimeline.TickInput serverState,
                          boolean serverTickStable, long serverSampleAgeNanos) {
        /** Pure unit-test constructor with stable, fresh context. */
        public Context(long serverTick, long correctionEpoch,
                       boolean settled, boolean pendingTeleport,
                       MovementTickTimeline.TickInput serverState) {
            this(serverTick, correctionEpoch, settled, pendingTeleport,
                    serverState, true, 0);
        }
    }

    /**
     * Mutually exclusive no-decision categories. Every incoming position
     * packet not modeled contributes to exactly one of these counters.
     * Correction events with no incoming position packet are separately
     * represented by the existing corrections total.
     */
    public record HeadingSkipReasons(long warmup, long timing, long context,
                                     long invalidPacket, long correction,
                                     long unavailable) {}

    public record Snapshot(String model, boolean enabled, long positionPackets,
                           long modeledTriples, long noDecision, long researchedOutside,
                           long ambiguousTicks, long corrections,
                           Status lastStatus, Long lastTick, Double lastActual,
                           Double lastReachable, Double lastExcess,
                           PacketReachabilityResiduals.Snapshot residuals,
                           PacketMotionTransitions.Snapshot transitions,
                           HeadingSkipReasons headingSkips,
                           PacketTickHintDiagnostics.Snapshot tickHints) {}

    private record Claim(long tick, long epoch, String world,
                         MovementPacketBuffer.Position position, String material,
                         boolean sprint) {}

    private final Profile profile;
    // Steady-only residuals; sprint-change tails go to separate bounded rings.
    private final PacketReachabilityResiduals residuals = new PacketReachabilityResiduals();
    private final PacketMotionTransitions transitions = new PacketMotionTransitions();
    // Pure diagnostic observer: it never controls or changes eligibility.
    private final PacketTickHintDiagnostics tickHints = new PacketTickHintDiagnostics();
    private Claim older, newest;
    private long positionPackets, modeled, noDecision, outside, ambiguous, corrections;
    private long headingWarmupSkips, headingTimingSkips, headingContextSkips;
    private long headingInvalidPacketSkips, headingCorrectionSkips, headingUnavailableSkips;
    private Status lastStatus = Status.WARMUP;
    private Long lastTick;
    private Double lastActual, lastReachable, lastExcess;

    public PacketGroundReachability(Profile profile) {
        this.profile = profile;
    }

    /** Call exclusively from the already-serialized player packet callback. */
    public synchronized void observe(MovementPacketBuffer.PacketState packet, Context context) {
        positionPackets++;
        tickHints.observeContext(context);
        lastActual = lastReachable = lastExcess = null;
        Status status;
        boolean sprintSwitchObserved = false;
        if (profile == null) {
            status = Status.DISABLED_VERSION;
        } else if (context == null || context.serverState() == null || context.serverTick() <= 0) {
            status = Status.NO_SERVER_SAMPLE;
        } else if (!context.serverTickStable()
                || context.serverSampleAgeNanos() < 0
                || context.serverSampleAgeNanos() > MAX_SERVER_SAMPLE_AGE_NS) {
            status = Status.STALE_SERVER_SAMPLE;
        } else if (context.pendingTeleport() || !context.settled()) {
            status = Status.RECENT_CORRECTION;
            corrections++;
        } else if (!eligible(context.serverState())) {
            status = Status.UNSUPPORTED_CONTEXT;
        } else if (packet == null || !packet.decoded() || packet.position() == null
                || !Boolean.TRUE.equals(packet.onGround())) {
            // Ground packets are client CLAIMS, never sufficient on their own.
            status = Status.INVALID_GROUND_CLAIM;
        } else {
            var state = context.serverState();
            var position = packet.position();
            var material = state.probeContext().groundMaterial();
            long tick = context.serverTick();
            long epoch = context.correctionEpoch();
            lastTick = tick;
            var current = new Claim(tick, epoch, state.worldKey(), position,
                    material, state.probeContext().sprinting());
            if (newest == null) {
                older = null;
                newest = current;
                status = Status.WARMUP;
            } else if (newest.tick() == tick) {
                // Two client position claims during one server scheduler tick
                // cannot be mapped to two distinct client physics ticks.
                older = null;
                newest = null;
                ambiguous++;
                status = Status.AMBIGUOUS_TICK;
            } else if (tick != newest.tick() + 1) {
                older = null;
                newest = current;
                status = Status.TICK_GAP;
            } else if (!sameContext(newest, current)) {
                // This is a SERVER sprint-state change seen between two
                // real client packet observations, not a client sprint
                // intent packet. Never compare a triple across the flip.
                if (newest.epoch() == current.epoch()
                        && newest.world().equals(current.world())
                        && newest.material().equals(current.material())
                        && newest.sprint() != current.sprint()) {
                    transitions.sprintChange(tick, epoch, newest.sprint(), current.sprint());
                    sprintSwitchObserved = true;
                }
                older = null;
                newest = current;
                status = Status.CONTEXT_CHANGED;
            } else if (older == null) {
                older = newest;
                newest = current;
                status = Status.WARMUP;
            } else if (older.tick() + 1 != newest.tick()
                    || !sameContext(older, current)) {
                older = newest;
                newest = current;
                status = Status.CONTEXT_CHANGED;
            } else {
                // v_prev is the *previous observed packet-to-packet*
                // displacement, not a trusted physical velocity.
                double prevDx = newest.position().x() - older.position().x();
                double prevDz = newest.position().z() - older.position().z();
                double dx = current.position().x() - newest.position().x();
                double dz = current.position().z() - newest.position().z();
                double prev = Math.hypot(prevDx, prevDz);
                double actual = Math.hypot(dx, dz);
                double dyPrev = Math.abs(newest.position().y() - older.position().y());
                double dy = Math.abs(current.position().y() - newest.position().y());

                if (!Double.isFinite(prev) || !Double.isFinite(actual)
                        || prev > MAX_PREVIOUS_HORIZONTAL
                        || dy > MAX_PACKET_VERTICAL_CHANGE
                        || dyPrev > MAX_PACKET_VERTICAL_CHANGE) {
                    status = Status.UNSUPPORTED_CONTEXT;
                } else {
                    // The unknown player input direction may point anywhere in
                    // a radius <= max input acceleration. The previous observed
                    // horizontal vector is decayed by ordinary-block drag.
                    // Thus the reachable displacement disk is centered at
                    // drag*v_prev and has radius maxAcceleration + uncertainty.
                    // This is a conservative research envelope, not exact vanilla
                    // input integration or an evidence-grade reachability proof.
                    double cx = prevDx * profile.ordinaryDrag();
                    double cz = prevDz * profile.ordinaryDrag();
                    double residual = Math.hypot(dx - cx, dz - cz);
                    double radius = profile.maxUnknownInputAcceleration()
                            + profile.uncertainty();
                    modeled++;
                    lastActual = actual;
                    lastReachable = Math.hypot(cx, cz) + radius;
                    lastExcess = Math.max(0, residual - radius);
                    // Only completed, eligible CLIENT PACKET triples enter
                    // the bounded distributions; even envelope-exceeding
                    // research points remain descriptive and non-actionable.
                    boolean postSwitch = transitions.observe(tick, epoch,
                            current.sprint(), prevDx, prevDz, dx, dz,
                            residual, lastExcess);
                    // The first handful of eligible packet triples after
                    // switching mode can retain legitimate prior momentum.
                    // Research them separately, not under steady walking.
                    if (!postSwitch) {
                        residuals.observe(current.sprint(), residual, lastExcess);
                    }
                    if (residual > radius) {
                        outside++;
                        status = Status.OUTSIDE_RESEARCH_ENVELOPE;
                    } else {
                        status = Status.CONSERVATIVE_REACHABLE;
                    }
                }
                older = newest;
                newest = current;
            }
        }

        if (status != Status.CONSERVATIVE_REACHABLE
                && status != Status.OUTSIDE_RESEARCH_ENVELOPE) {
            noDecision++;
            // Breakdown describes upstream packet reachability gates; it does
            // not imply there was enough motion to define a heading angle.
            switch (status) {
                case WARMUP -> headingWarmupSkips++;
                case TICK_GAP, AMBIGUOUS_TICK, STALE_SERVER_SAMPLE -> headingTimingSkips++;
                case CONTEXT_CHANGED, UNSUPPORTED_CONTEXT -> headingContextSkips++;
                case INVALID_GROUND_CLAIM -> headingInvalidPacketSkips++;
                case RECENT_CORRECTION -> headingCorrectionSkips++;
                case DISABLED_VERSION, NO_SERVER_SAMPLE -> headingUnavailableSkips++;
                default -> throw new IllegalStateException("Unexpected no-decision status: " + status);
            }
            if (status != Status.WARMUP && status != Status.TICK_GAP
                    && status != Status.CONTEXT_CHANGED
                    && status != Status.AMBIGUOUS_TICK) {
                older = null;
                newest = null;
            }
            // A timing gap, packet ambiguity, changed material/world, or
            // unknown game state makes post-switch attribution unreliable.
            // Preserve tagging only through the matching sprint-flip
            // context change and normal two-claim warmup.
            if (status != Status.WARMUP && !sprintSwitchObserved) {
                transitions.interruption();
            }
        }
        tickHints.observeOutcome(status);
        lastStatus = status;
    }

    /** Called after teleport/velocity/respawn/world epoch changes. */
    public synchronized void correction() {
        older = null;
        newest = null;
        corrections++;
        lastActual = lastReachable = lastExcess = null;
        lastStatus = Status.RECENT_CORRECTION;
        transitions.interruption();
        tickHints.correction();
    }

    private static boolean sameContext(Claim a, Claim b) {
        return a.epoch() == b.epoch()
                && a.world().equals(b.world())
                && a.material().equals(b.material())
                && a.sprint() == b.sprint();
    }

    private static boolean eligible(MovementTickTimeline.TickInput input) {
        var f = input.flags();
        var e = input.environment();
        var p = input.probeContext();
        return f.serverOnGround() && !f.flying() && !f.gliding()
                && !f.swimming() && !f.inVehicle() && !f.creativeOrSpectator()
                && p.verified() && p.ordinaryFlatSurface()
                && p.noActiveEffects() && p.defaultWalkSpeed()
                && !p.groundMaterial().equals("UNKNOWN")
                && e.feetPassable() && e.headPassable()
                && !e.belowPassable() && !e.feetLiquid() && !e.belowLiquid();
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(profile == null ? "unsupported" : profile.family(),
                profile != null, positionPackets, modeled, noDecision, outside,
                ambiguous, corrections, lastStatus, lastTick,
                lastActual, lastReachable, lastExcess,
                residuals.snapshot(), transitions.snapshot(),
                new HeadingSkipReasons(headingWarmupSkips, headingTimingSkips,
                        headingContextSkips, headingInvalidPacketSkips,
                        headingCorrectionSkips, headingUnavailableSkips),
                tickHints.snapshot());
    }
}
