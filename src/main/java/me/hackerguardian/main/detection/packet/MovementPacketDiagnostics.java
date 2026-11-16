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
        SUMMARY, MOVEMENT, NETWORK;

        public static View parse(String name) {
            if (name == null || name.isBlank()) return SUMMARY;
            return switch (name.toLowerCase(Locale.ROOT)) {
                case "summary" -> SUMMARY;
                case "movement", "move" -> MOVEMENT;
                case "network", "net" -> NETWORK;
                default -> null;
            };
        }
    }

    private MovementPacketDiagnostics() {}

    public static List<String> render(String name, MovementPacketBuffer.Snapshot packet,
                                      MovementPacketTransport.Snapshot transport,
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
                lines.add("Details: /hg detection packets " + player + " move|net");
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
            case NETWORK -> {
                if (transport == null) {
                    lines.add("Transport tracking unavailable");
                } else {
                    lines.add("TP sent " + transport.sentTeleports() + " | confirms "
                            + transport.receivedConfirms() + " | matched " + transport.matchedConfirms());
                    lines.add("Pending " + transport.pendingTeleports() + " | unmatched "
                            + transport.unmatchedConfirms() + " | resends " + transport.resends()
                            + " | expired " + transport.expiredOrEvicted());
                    lines.add("Last matched ID " + (transport.lastMatchedId() == null
                            ? "none" : transport.lastMatchedId())
                            + " | moves before ACK " + (transport.lastMatchedId() == null
                            ? "n/a" : transport.lastMatchMovementCount()));
                    lines.add("Self velocity " + transport.outboundSelfVelocities()
                            + " | unknown payload " + transport.unknownVelocityPayloads()
                            + " | ID decode errors " + transport.unreadableIds());
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
