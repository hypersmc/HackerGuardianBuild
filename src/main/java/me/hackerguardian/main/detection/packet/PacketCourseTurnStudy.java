package me.hackerguardian.main.detection.packet;

import java.util.List;

/**
 * Descriptive corner study from real decoded client POSITION claims, including
 * claims immediately BEFORE and AFTER a Bukkit-main-thread checkpoint.
 * Never interprets camera yaw or a server scheduler tick as client input.
 * No enforcement or detector coupling.
 */
public final class PacketCourseTurnStudy {
    private static final double NEAR_CORNER = 5.0;
    private static final double MIN_VECTOR = 1.0;
    private final double x, z;
    private final double inX, inZ, outX, outZ;
    private final double expected;
    private MovementPacketBuffer.Position beforeFirst, beforeLast;
    private MovementPacketBuffer.Position afterFirst, afterLast;
    private MovementPacketBuffer.Position lastNear;
    private long oppositeSteps;
    private double oppositeDistance;

    public record Result(String expectedDirection, String observedDirection,
                         Double signedAngle, String quality,
                         long oppositeSteps, double oppositeDistance) {
        public boolean hasTurn() { return !expectedDirection.equals("STRAIGHT"); }
    }

    public PacketCourseTurnStudy(PacketCourseGeometry.Leg incoming,
                                 PacketCourseGeometry.Leg outgoing,
                                 List<MovementPacketBuffer.Position> previousPackets) {
        if (incoming == null || outgoing == null || previousPackets == null) {
            throw new IllegalArgumentException("Both course legs and recent positions required");
        }
        x = outgoing.from().x() + .5;
        z = outgoing.from().z() + .5;
        inX = (incoming.to().x() - incoming.from().x()) / (double) incoming.length();
        inZ = (incoming.to().z() - incoming.from().z()) / (double) incoming.length();
        outX = (outgoing.to().x() - outgoing.from().x()) / (double) outgoing.length();
        outZ = (outgoing.to().z() - outgoing.from().z()) / (double) outgoing.length();
        expected = Math.toDegrees(Math.atan2(inX * outZ - inZ * outX,
                inX * outX + inZ * outZ));
        for (var position : previousPackets) {
            observeBefore(position);
        }
    }

    private void observeBefore(MovementPacketBuffer.Position position) {
        if (!near(position)) {
            lastNear = null;
            return;
        }
        countOpposite(position);
        double along = (position.x() - x) * inX + (position.z() - z) * inZ;
        double sideways = Math.abs((position.x() - x) * inZ
                - (position.z() - z) * inX);
        if (along < -NEAR_CORNER || along > .9 || sideways > 1.6) return;
        if (beforeFirst == null) beforeFirst = position;
        beforeLast = position;
    }

    /** Packet-thread post-checkpoint position; stop at the first leg's far end. */
    public void observeAfter(MovementPacketBuffer.Position position) {
        if (!near(position)) {
            lastNear = null;
            return;
        }
        countOpposite(position);
        double along = (position.x() - x) * outX + (position.z() - z) * outZ;
        double sideways = Math.abs((position.x() - x) * outZ
                - (position.z() - z) * outX);
        if (along < -.9 || along > NEAR_CORNER || sideways > 1.7) return;
        if (afterFirst == null) afterFirst = position;
        afterLast = position;
    }

    private void countOpposite(MovementPacketBuffer.Position position) {
        if (lastNear != null && Math.abs(expected) > 45) {
            double dx = position.x() - lastNear.x();
            double dz = position.z() - lastNear.z();
            double length = Math.hypot(dx, dz);
            double alongIncoming = dx * inX + dz * inZ;
            double alongOutgoing = dx * outX + dz * outZ;
            // Reject jitter, jumps and normal travel along the incoming leg.
            if (length >= .075 && length <= .9
                    && alongOutgoing < -.07 && Math.abs(alongIncoming) < .09) {
                oppositeSteps++;
                oppositeDistance += -alongOutgoing;
            }
        }
        lastNear = position;
    }

    /** Corrections invalidate the cross-boundary measurement, not its counters. */
    public void correction() {
        beforeFirst = beforeLast = afterFirst = afterLast = lastNear = null;
    }

    private boolean near(MovementPacketBuffer.Position p) {
        return p != null && Math.hypot(p.x() - x, p.z() - z) <= NEAR_CORNER;
    }

    private static double displacement(MovementPacketBuffer.Position a,
                                       MovementPacketBuffer.Position b) {
        return a == null || b == null ? 0 : Math.hypot(b.x() - a.x(), b.z() - a.z());
    }

    public Result snapshot() {
        String intended = direction(expected);
        if (intended.equals("STRAIGHT")) {
            return new Result(intended, "STRAIGHT", null, "TRANSITION", 0, 0);
        }
        if (displacement(beforeFirst, beforeLast) < MIN_VECTOR
                || displacement(afterFirst, afterLast) < MIN_VECTOR) {
            return new Result(intended, "UNKNOWN", null,
                    oppositeSteps >= 2 && oppositeDistance >= .20 ? "DETOUR" : "UNMEASURED",
                    oppositeSteps, oppositeDistance);
        }
        double bx = beforeLast.x() - beforeFirst.x();
        double bz = beforeLast.z() - beforeFirst.z();
        double ax = afterLast.x() - afterFirst.x();
        double az = afterLast.z() - afterFirst.z();
        double measured = Math.toDegrees(Math.atan2(bx * az - bz * ax, bx * ax + bz * az));
        String observed = direction(measured);
        String quality;
        if (oppositeSteps >= 2 && oppositeDistance >= .20) {
            quality = "DETOUR";
        } else if (!observed.equals(intended)) {
            quality = "WRONG_DIRECTION";
        } else if (Math.abs(measured - expected) > 40) {
            quality = "SHALLOW_OR_WIDE";
        } else {
            quality = "MATCH";
        }
        return new Result(intended, observed, measured, quality,
                oppositeSteps, oppositeDistance);
    }

    private static String direction(double angle) {
        if (Math.abs(angle) <= 30) return "STRAIGHT";
        if (Math.abs(angle) >= 150) return "REVERSE";
        // Minecraft X/Z cross-product: negative means left when facing forward.
        return angle < 0 ? "LEFT" : "RIGHT";
    }
}
