package me.hackerguardian.main.detection.packet;

import java.util.ArrayList;
import java.util.List;

/** A compact, reversible six-leg course; pure geometry, no world mutations. */
public final class PacketCourseGeometry {
    private PacketCourseGeometry() {}

    public record Point(int x, int z) {}
    public record Leg(PacketCourseCapture.Phase phase, Point from, Point to) {
        public int length() {
            return Math.abs(to.x() - from.x()) + Math.abs(to.z() - from.z());
        }
    }

    /**
     * First leg is forward, second lateral, third back, fourth continues back
     * (the sprint transition), fifth returns laterally and sixth goes forward.
     * Every corner except the intentional walk-to-sprint transition is 90 degrees.
     * Forward must be exactly one cardinal block vector.
     */
    public static List<Leg> build(int startX, int startZ, int forwardX,
                                  int forwardZ, boolean left) {
        if (Math.abs(forwardX) + Math.abs(forwardZ) != 1) {
            throw new IllegalArgumentException("Forward must be cardinal");
        }
        int sideX = left ? forwardZ : -forwardZ;
        int sideZ = left ? -forwardX : forwardX;
        int[][] directions = {
                {forwardX, forwardZ}, {sideX, sideZ},
                {-forwardX, -forwardZ}, {-forwardX, -forwardZ},
                {-sideX, -sideZ}, {forwardX, forwardZ}
        };
        int[] lengths = {12, 8, 8, 10, 8, 8};
        PacketCourseCapture.Phase[] phases = PacketCourseCapture.Phase.values();
        ArrayList<Leg> legs = new ArrayList<>(lengths.length);
        Point from = new Point(startX, startZ);
        for (int i = 0; i < lengths.length; i++) {
            Point to = new Point(from.x() + directions[i][0] * lengths[i],
                    from.z() + directions[i][1] * lengths[i]);
            legs.add(new Leg(phases[i], from, to));
            from = to;
        }
        return List.copyOf(legs);
    }
}
