package me.hackerguardian.main.detection.packet;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Small, read-only in-game diagnostics. Default output is intentionally short
 * enough for Minecraft chat; optional views expose movement/network detail.
 *
 * These are packet observations and diagnostic counters, NEVER cheat verdicts.
 */
public final class MovementPacketDiagnostics {

    public enum View {
        SUMMARY, MOVEMENT, NETWORK, TICK, GUARDS, PROBE, PROFILE, DYNAMICS, IDLE,
        PREDICT, RESIDUALS, SWITCHES, HEADING, TIMING, RUNS, ACTIVITY;

        public static View parse(String name) {
            if (name == null || name.isBlank()) return SUMMARY;
            return switch (name.toLowerCase(Locale.ROOT)) {
                case "summary" -> SUMMARY;
                case "movement", "move" -> MOVEMENT;
                case "network", "net" -> NETWORK;
                case "tick", "state" -> TICK;
                case "guards", "guard", "safety" -> GUARDS;
                case "probe", "plausibility" -> PROBE;
                case "profile", "dist", "distribution" -> PROFILE;
                case "dynamics", "dyn", "motion" -> DYNAMICS;
                case "idle", "startstop", "resume" -> IDLE;
                case "predict", "reachability", "pktphysics" -> PREDICT;
                case "residuals", "res", "predstats", "predictstats" -> RESIDUALS;
                case "switch", "switches", "trans", "transitions" -> SWITCHES;
                case "heading", "headings", "angles", "turns" -> HEADING;
                case "timing", "tickhints", "sync" -> TIMING;
                case "runs", "clusters", "runlengths" -> RUNS;
                case "activity", "passive", "context" -> ACTIVITY;
                default -> null;
            };
        }
    }

    private MovementPacketDiagnostics() {}

    public static List<String> render(String name, MovementPacketBuffer.Snapshot packet,
                                      MovementPacketTransport.Snapshot transport,
                                      View view, long nowNanos) {
        return render(name, packet, transport, null, view, nowNanos);
    }

    public static List<String> render(String name, MovementPacketBuffer.Snapshot packet,
                                      MovementPacketTransport.Snapshot transport,
                                      MovementTickTimeline.Snapshot ticks,
                                      View view, long nowNanos) {
        return render(name, packet, transport, ticks, null, view, nowNanos);
    }

    public static List<String> render(String name, MovementPacketBuffer.Snapshot packet,
                                      MovementPacketTransport.Snapshot transport,
                                      MovementTickTimeline.Snapshot ticks,
                                      MovementGroundProbe.Snapshot probe,
                                      View view, long nowNanos) {
        return render(name, packet, transport, ticks, probe, null, view, nowNanos);
    }

    public static List<String> render(String name, MovementPacketBuffer.Snapshot packet,
                                      MovementPacketTransport.Snapshot transport,
                                      MovementTickTimeline.Snapshot ticks,
                                      MovementGroundProbe.Snapshot probe,
                                      PacketGroundReachability.Snapshot prediction,
                                      View view, long nowNanos) {
        if (packet == null || view == null) throw new IllegalArgumentException("snapshot and view required");
        List<String> lines = new ArrayList<>();
        String player = name == null ? "unknown" : name;
        lines.add("Packets: " + player + " [" + view.name().toLowerCase(Locale.ROOT) + "]");

        MovementPacketBuffer.WindowStats recent =
                packet.recentWindow(nowNanos, TimeUnit.SECONDS.toNanos(5));
        MovementPacketBuffer.LastKnown fields = packet.lastKnownClientFields();

        switch (view) {
            case SUMMARY -> {
                lines.add("Received " + packet.totalReceived() + " | Decode errors " + packet.decodeFailures()
                        + " | 5s rate " + rate(recent));
                lines.add("On-ground " + flag(fields.onGround())
                        + " | Collision " + flag(fields.horizontalCollision()));
                if (transport == null) {
                    lines.add("Teleport tracking unavailable");
                } else {
                    lines.add("TP sent " + transport.sentTeleports() + " | matched "
                            + transport.matchedConfirms() + " | pending " + transport.pendingTeleports()
                            + " | unmatched " + transport.unmatchedConfirms());
                }
                // Print the command suffixes rather than the long full
                // command: avoid wrapping the five-line default in chat.
                lines.add("Views: move|net|tick|guards|probe|profile|dyn|idle|predict|res|trans|heading|timing|runs");
            }
            case MOVEMENT -> {
                lines.add("Received " + packet.totalReceived() + " | Retained " + packet.retained()
                        + " | Overwritten " + packet.dropped() + " | Decode errors " + packet.decodeFailures());
                lines.add("5s " + recent.count() + " packets | " + rate(recent)
                        + " | P:" + recent.position() + " PL:" + recent.positionLook()
                        + " L:" + recent.look() + " G:" + recent.ground());
                if (fields.position() != null) {
                    var p = fields.position();
                    lines.add(String.format(Locale.ROOT, "XYZ (#%d): %.2f %.2f %.2f",
                            fields.positionSequence(), p.x(), p.y(), p.z()));
                } else {
                    lines.add("XYZ: unavailable");
                }
                if (fields.rotation() != null) {
                    var r = fields.rotation();
                    lines.add(String.format(Locale.ROOT, "Yaw/Pitch (#%d): %.1f / %.1f",
                            fields.rotationSequence(), r.yaw(), r.pitch()));
                } else {
                    lines.add("Yaw/Pitch: unavailable");
                }
                lines.add("On-ground " + flag(fields.onGround())
                        + " | Collision " + flag(fields.horizontalCollision())
                        + (recent.truncated() ? " | 5s truncated" : ""));
            }
            case TICK -> {
                if (ticks == null || ticks.latestTick() == null) {
                    lines.add("No server tick samples available");
                } else {
                    var latest = ticks.latestTick();
                    var state = latest.state();
                    var pos = state.position();
                    var vel = state.velocity();
                    var flags = state.flags();
                    var env = state.environment();
                    lines.add("Server tick " + latest.tick() + " | history " + ticks.ticks().size()
                            + " | overwritten " + ticks.droppedTicks());
                    lines.add(String.format(Locale.ROOT, "Server XYZ: %.2f %.2f %.2f",
                            pos.x(), pos.y(), pos.z()));
                    lines.add(String.format(Locale.ROOT,
                            "Vel %.2f %.2f %.2f | ground %s | flying %s",
                            vel.x(), vel.y(), vel.z(),
                            flags.serverOnGround(), flags.flying()));
                    lines.add("Feet passable " + env.feetPassable()
                            + " | below passable " + env.belowPassable()
                            + " | liquid " + (env.feetLiquid() || env.belowLiquid()));
                    var comparison = ticks.comparison();
                    if (comparison.status() == MovementTickTimeline.Status.READY_DIAGNOSTIC
                            && comparison.positionDifference() != null) {
                        lines.add(String.format(Locale.ROOT,
                                "Last pair delta %.3f | gap %.1fms (not verdict)",
                                comparison.positionDifference(),
                                comparison.tickLagNanos() / 1_000_000.0));
                    } else if (comparison.status() == MovementTickTimeline.Status.CORRECTION_WINDOW
                            || comparison.status() == MovementTickTimeline.Status.DIFFERENT_CONTEXT
                            || comparison.status() == MovementTickTimeline.Status.PENDING_TELEPORT) {
                        lines.add("Comparison " + comparison.status()
                                + " | boundary " + (ticks.lastCorrection() == null
                                ? "none" : ticks.lastCorrection()));
                    } else {
                        // Last correction could be hours old; do not present
                        // it as the cause of waiting for the next tick.
                        lines.add("Comparison " + comparison.status()
                                + " | no completed recent pair");
                    }
                }
            }
            case GUARDS -> {
                if (ticks == null) {
                    lines.add("Correction diagnostics unavailable");
                } else {
                    var g = ticks.guards();
                    lines.add("Boundaries TP " + g.teleportBoundaries()
                            + " | Velocity " + g.velocityBoundaries()
                            + " | Other " + g.otherBoundaries());
                    lines.add("Invalidated pending " + g.pendingClaimsInvalidated()
                            + " | completed " + g.completedPairsInvalidated());
                    lines.add("Suppressed recovery " + g.recoverySuppressions()
                            + " | pending TP " + g.pendingTeleportSuppressions());
                    lines.add("Suppressed context " + g.contextSuppressions()
                            + " | paired " + g.completedPairs());
                    lines.add("Last guard " + (g.lastSuppression() == null
                            ? "none" : g.lastSuppression())
                            + " @tick " + (g.lastSuppressionTick() < 0
                            ? "n/a" : g.lastSuppressionTick()));
                }
            }
            case PROBE -> {
                if (probe == null) {
                    lines.add("Ground research probe OFF (opt-in in detection.yml)");
                } else {
                    lines.add("Observe-only ground research, NOT a cheat check");
                    lines.add("Tick samples " + probe.tickSamples() + " | eligible "
                            + probe.evaluated() + " | no-decision " + probe.noDecision());
                    lines.add("Last " + probe.lastResult() + " | terrain skips "
                            + probe.unsafeTerrainSamples());
                    if (probe.horizontalDistance() == null || probe.envelope() == null) {
                        lines.add("Surface clue: " + probe.lastSurfaceReason());
                    } else {
                        lines.add(String.format(Locale.ROOT, "Horizontal %.3f | loose bound %.3f",
                                probe.horizontalDistance(), probe.envelope()));
                    }
                    lines.add("No-client " + probe.noClientDataSamples()
                            + " | special " + probe.specialMovementSamples()
                            + " | research outliers " + probe.researchOutliers());
                }
            }
            case PROFILE -> {
                if (probe == null) {
                    lines.add("Ground-motion profile OFF (opt-in in detection.yml)");
                } else {
                    lines.add("Observed server displacement, NOT a cheat check");
                    var dist = probe.distributions();
                    lines.add(profileLine("Walk", dist.walking()));
                    lines.add(profileLine("Sprint", dist.sprinting()));
                    lines.add("Units: blocks / 50ms | rolling last 128 per mode");
                    lines.add("Eligible " + probe.evaluated() + " | transition skips "
                            + probe.sprintTransitions() + " | no-decision " + probe.noDecision());
                }
            }
            case DYNAMICS -> {
                if (probe == null) {
                    lines.add("Ground dynamics OFF (enable ground probe first)");
                } else {
                    var d = probe.dynamics();
                    var gate = probe.dynamicsGate();
                    lines.add("Research only | eligible " + probe.evaluated()
                            + " | vectors " + d.eligibleVectors()
                            + " | paired " + d.comparedTransitions());
                    lines.add("Last probe " + probe.lastResult()
                            + " | block " + gate.lastMaterial());
                    lines.add("Gate " + gate.lastReason() + " | unknown "
                            + gate.unknownMaterialPairs()
                            + " | changed " + gate.changedMaterialPairs());
                    var idle = probe.idleTransitions();
                    lines.add("Server starts " + idle.serverStarts() + " stops " + idle.serverStops()
                            + " | Accel " + d.accelerations()
                            + " Slow " + d.decelerations());
                    lines.add("Turn " + d.turns() + " Steady " + d.steady()
                            + " | breaks " + d.discontinuities()
                            + " | slowdown " + value(d.slowingRetention().p50()));
                }
            }
            case IDLE -> {
                if (probe == null) {
                    lines.add("Ground start/stop research OFF (enable probe)");
                } else {
                    var id = probe.idleTransitions();
                    lines.add("Bukkit SERVER samples, NOT client key presses");
                    lines.add("Ticks " + id.capturedTicks() + " | intervals "
                            + id.eligibleIntervals() + " | no-decision " + id.noDecisionTicks());
                    lines.add("Observed starts " + id.serverStarts()
                            + " | stops " + id.serverStops()
                            + " | breaks " + id.interruptedChains());
                    lines.add("Starts w/recent packet " + id.startsWithRecentClient()
                            + " | stops w/o recent packet " + id.stopsWithoutRecentClient());
                    lines.add("State " + id.state() + " | " + id.lastStatus()
                            + " | " + id.lastBlock()
                            + " | motion " + value(id.lastBlocksPer50ms()));
                }
            }
            case PREDICT -> {
                if (prediction == null) {
                    lines.add("Packet reachability research OFF (opt-in)");
                } else {
                    lines.add("Real client POSITION packets | research ONLY");
                    lines.add("Profile " + prediction.model() + " | pos packets "
                            + prediction.positionPackets() + " | modeled "
                            + prediction.modeledTriples());
                    lines.add("No-decision " + prediction.noDecision()
                            + " | ambiguous " + prediction.ambiguousTicks()
                            + " | corrections " + prediction.corrections());
                    lines.add("Last " + prediction.lastStatus() + " | outside research "
                            + prediction.researchedOutside());
                    lines.add("Observed " + value(prediction.lastActual())
                            + " | radius+drift " + value(prediction.lastReachable())
                            + " | excess " + value(prediction.lastExcess()));
                }
            }
            case RESIDUALS -> {
                if (prediction == null) {
                    lines.add("Packet residual research OFF (opt-in)");
                } else {
                    var stats = prediction.residuals();
                    lines.add("STEADY packet input residual | research ONLY");
                    lines.add(packetResidualLine("Walk", stats.walking()));
                    lines.add(packetResidualLine("Sprint", stats.sprinting()));
                    lines.add("Outside W " + stats.walking().outside()
                            + " S " + stats.sprinting().outside()
                            + " | peak excess W " + value(stats.walking().maxExcess())
                            + " S " + value(stats.sprinting().maxExcess()));
                    lines.add("Switch tails excluded W "
                            + prediction.transitions().taggedWalk()
                            + " S " + prediction.transitions().taggedSprint()
                            + " | no-decision " + prediction.noDecision());
                }
            }
            case SWITCHES -> {
                if (prediction == null) {
                    lines.add("Packet transition research OFF (opt-in)");
                } else {
                    var trans = prediction.transitions();
                    var tail = trans.switchResiduals();
                    var move = trans.switchDisplacements();
                    lines.add("Real packet deltas | Bukkit sprint label | RESEARCH");
                    lines.add("Walk>sprint " + trans.walkToSprint()
                            + " | Sprint>walk " + trans.sprintToWalk()
                            + " | tagged " + (trans.taggedWalk() + trans.taggedSprint()));
                    lines.add("Accel " + trans.accelerating()
                            + " Slow " + trans.decelerating()
                            + " Turn " + trans.turning()
                            + " Steady " + trans.steady());
                    lines.add(transitionTailLine("S>W", tail.walking(), move.enteringWalk()));
                    lines.add(transitionTailLine("W>S", tail.sprinting(), move.enteringSprint()));
                }
            }
            case HEADING -> {
                if (prediction == null) {
                    lines.add("Packet heading research OFF (opt-in)");
                } else if (!prediction.enabled()) {
                    lines.add("Packet heading model unavailable for this version");
                } else {
                    var angles = prediction.transitions().headings();
                    var skips = prediction.headingSkips();
                    lines.add("Real client packet XZ headings | NOT camera yaw");
                    lines.add("Angles " + angles.retained() + "/" + angles.measured()
                            + " | p50 " + value(angles.p50())
                            + " p95 " + value(angles.p95())
                            + " max " + value(angles.max()) + " deg");
                    lines.add(">=5deg " + angles.over5()
                            + " >=15deg " + angles.over15()
                            + " >=35deg " + angles.over35()
                            + " | too-slow " + angles.lowMotion());
                    lines.add("Modeled " + prediction.modeledTriples()
                            + " | no-decision " + prediction.noDecision()
                            + " | timing " + skips.timing()
                            + " | context " + skips.context());
                    lines.add("Invalid " + skips.invalidPacket()
                            + " | warmup " + skips.warmup()
                            + " | correction " + skips.correction()
                            + " | unavailable " + skips.unavailable());
                }
            }
            case TIMING -> {
                if (prediction == null) {
                    lines.add("Packet timing research OFF (opt-in)");
                } else if (!prediction.enabled()) {
                    lines.add("Packet timing profile unavailable for this version");
                } else {
                    var t = prediction.tickHints();
                    lines.add("SERVER tick hints | NOT client simulation ticks");
                    lines.add("Hint pairs +1 " + t.adjacentHints()
                            + " same " + t.repeatedHints()
                            + " gaps " + (t.twoTickGaps() + t.largerGaps())
                            + " reverse " + t.reverseHints());
                    lines.add("Gap +2 " + t.twoTickGaps()
                            + " >=3 " + t.largerGaps()
                            + " | model gaps " + t.actualTickGapRejects());
                    lines.add("Model same-tick " + t.actualAmbiguousRejects()
                            + " | stale " + t.staleServerSamples()
                            + " | unstable " + t.unstableTickSpacing());
                    lines.add("Warmup init " + t.warmupInitial()
                            + " timing " + t.warmupAfterTiming()
                            + " context " + t.warmupAfterContext()
                            + " correction " + t.warmupAfterCorrection()
                            + " other " + t.warmupAfterOther());
                }
            }
            case ACTIVITY -> {
                if (prediction == null) {
                    lines.add("Passive movement context OFF (opt-in)");
                } else if (!prediction.enabled()) {
                    lines.add("Passive movement research unavailable for this version");
                } else {
                    var a = prediction.activity();
                    lines.add("SERVER intervals w/o POSITION (not client ticks or loss)");
                    lines.add("No-pos " + a.noPositionIntervals() + "/" + a.serverIntervals()
                            + " quiet " + a.noPositionQuiet()
                            + " move " + a.noPositionMoving()
                            + " ? " + a.noPositionUnknown()
                            + " | streak max " + a.maxNoPositionRun()
                            + " live " + a.liveNoPositionRun());
                    lines.add("Client XZ pairs " + a.packetPairs()
                            + " quiet " + a.quietPacketPairs()
                            + " walk " + a.walkingPacketPairs()
                            + " sprint " + a.sprintingPacketPairs()
                            + " switch " + a.sprintChangePacketPairs());
                    lines.add("Hint gaps Q/M/? " + a.quietHintGaps() + "/"
                            + a.movingHintGaps() + "/" + a.unknownHintGaps()
                            + " | model gaps Q/M/? " + a.quietModelGaps() + "/"
                            + a.movingModelGaps() + "/" + a.unknownModelGaps());
                }
            }
            case RUNS -> {
                if (prediction == null) {
                    lines.add("Packet run research OFF (opt-in)");
                } else if (!prediction.enabled()) {
                    lines.add("Packet run model unavailable for this version");
                } else {
                    var r = prediction.runs();
                    var accepted = r.acceptedRuns();
                    var clusters = r.timingClusters();
                    lines.add("Model runs | SERVER tick hints, NOT client ticks");
                    lines.add("Accepted runs n " + accepted.completed()
                            + " p50 " + count(accepted.p50())
                            + " p95 " + count(accepted.p95())
                            + " max " + count(accepted.max())
                            + " live " + r.activeAcceptedRun());
                    lines.add("Runs len1 " + r.singleRuns()
                            + " len2-4 " + r.runs2to4()
                            + " len5-19 " + r.runs5to19()
                            + " len20+ " + r.runs20Plus());
                    lines.add("Gap clusters n " + clusters.completed()
                            + " p50 " + count(clusters.p50())
                            + " p95 " + count(clusters.p95())
                            + " max " + count(clusters.max())
                            + " live " + r.activeTimingCluster());
                    lines.add("Gap singles " + r.isolatedTimingClusters()
                            + " multi " + r.multiTimingClusters()
                            + " | timing reject " + r.totalTimingRejects()
                            + " | warmups in " + r.warmupsInsideTimingClusters());
                }
            }
            case NETWORK -> {
                if (transport == null) {
                    lines.add("Transport tracking unavailable");
                } else {
                    lines.add("TP sent " + transport.sentTeleports() + " | confirms "
                            + transport.receivedConfirms() + " | matched " + transport.matchedConfirms());
                    lines.add("Pending " + transport.pendingTeleports() + " | unmatched "
                            + transport.unmatchedConfirms() + " | resends " + transport.resends()
                            + " | expired " + transport.expiredOrEvicted());
                    lines.add("Velocity wire " + transport.velocityCallbacks()
                            + " | self " + transport.outboundSelfVelocities()
                            + " | other " + transport.velocityOtherEntity()
                            + " | ID? " + transport.velocityEntityIdUnreadable());
                    lines.add("Self entity ID " + transport.expectedSelfEntityId()
                            + " | last wire ID " + (transport.lastVelocityPacketEntityId() == null
                            ? "none" : transport.lastVelocityPacketEntityId())
                            + " | payload? " + transport.unknownVelocityPayloads());
                }
                String intent = "none";
                if (!packet.contexts().isEmpty()) {
                    var last = packet.contexts().get(packet.contexts().size() - 1);
                    intent = last.kind() == MovementPacketBuffer.ContextKind.SERVER_TELEPORT
                            ? "TELEPORT (" + last.cause() + ")"
                            : "VELOCITY";
                }
                lines.add("Bukkit intents " + packet.totalContexts() + " | last " + intent);
            }
        }
        return List.copyOf(lines);
    }

    private static String count(Long value) {
        return value == null ? "n/a" : value.toString();
    }

    private static String value(Double number) {
        return number == null ? "n/a" : String.format(Locale.ROOT, "%.3f", number);
    }

    private static String transitionTailLine(String mode,
                                             PacketReachabilityResiduals.ModeStats residual,
                                             PacketSwitchDisplacements.Stats movement) {
        // r95 = required unknown input; d95 = observed packet displacement.
        // near0 and drag~ are descriptive labels, NOT key-state estimates.
        return mode + " n" + movement.total()
                + " r95 " + value(residual.p95())
                + " d95 " + value(movement.p95())
                + " near0 " + movement.nearZero()
                + " drag~ " + movement.dragLike();
    }

    private static String packetResidualLine(String mode,
                                             PacketReachabilityResiduals.ModeStats stats) {
        if (!stats.available()) return mode + " n=0 | no modeled triples";
        return String.format(Locale.ROOT,
                "%s n=%d/%d | r50 %.3f r95 %.3f max %.3f",
                mode, stats.retained(), stats.total(),
                stats.p50(), stats.p95(), stats.max());
    }

    private static String profileLine(String mode, GroundMotionDistributions.Stats stats) {
        if (!stats.available()) return mode + " n=0 | insufficient data";
        return String.format(Locale.ROOT,
                "%s n=%d/%d | p50 %.3f p95 %.3f max %.3f",
                mode, stats.retained(), stats.totalSamples(),
                stats.p50(), stats.p95(), stats.max());
    }

    private static String flag(Boolean value) {
        return value == null ? "UNKNOWN" : value.toString();
    }

    private static String rate(MovementPacketBuffer.WindowStats stats) {
        return (stats.truncated() ? ">= " : "")
                + String.format(Locale.ROOT, "%.1f/s", stats.ratePerSecond());
    }
}
