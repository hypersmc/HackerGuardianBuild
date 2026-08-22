package me.hackerguardian.main.detection.deterministic;

/** Pure geometry helpers kept independent of Bukkit for deterministic tests. */
public final class GeometryMath {

    private GeometryMath() {}

    public static double distanceToAabb(double px,
                                        double py,
                                        double pz,
                                        double minX,
                                        double minY,
                                        double minZ,
                                        double maxX,
                                        double maxY,
                                        double maxZ) {
        double dx = axisDistance(px, minX, maxX);
        double dy = axisDistance(py, minY, maxY);
        double dz = axisDistance(pz, minZ, maxZ);
        return Math.sqrt((dx * dx) + (dy * dy) + (dz * dz));
    }

    private static double axisDistance(double value, double min, double max) {
        if (value < min) return min - value;
        if (value > max) return value - max;
        return 0.0;
    }
}
