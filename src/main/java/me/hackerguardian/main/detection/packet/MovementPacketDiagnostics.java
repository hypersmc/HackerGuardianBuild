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
        SUMMARY, MOVEMENT, NETWORK, TICK, GUARDS, PROBE;

        public static View parse(String name) {
            if (name == null || name.isBlank()) return SUMMARY;
            return switch (name.toLowerCase(Locale.ROOT)) {
                case "summary" -> SUMMARY;
                case "movement", "move" -> MOVEMENT;
                case "network", "net" -> NETWORK;
                case "tick", "state" -> TICK;
                case "guards", "guard", "safety" -> GUARDS;
                case "probe", "plausibility" -> PROBE;
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
                lines.add("Details: /hg detection packets " + player + " move|net|tick|guards|probe");
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
                    lines.add("Last " + probe.lastResult() + " @tick " + probe.lastTick());
                    if (probe.horizontalDistance() == null || probe.envelope() == null) {
                        lines.add("Ground movement: no eligible consecutive pair");
                    } else {
                        lines.add(String.format(Locale.ROOT, "Horizontal %.3f | loose envelope %.3f blocks",
                                probe.horizontalDistance(), probe.envelope()));
                    }
                    lines.add("Research outliers " + probe.researchOutliers()
                            + " | consecutive " + probe.consecutiveOutlierSamples());
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

    private static String flag(Boolean value) {
        return value == null ? "UNKNOWN" : value.toString();
    }

    private static String rate(MovementPacketBuffer.WindowStats stats) {
        return (stats.truncated() ? ">= " : "")
                + String.format(Locale.ROOT, "%.1f/s", stats.ratePerSecond());
    }
}
