package me.hackerguardian.main.detection.deterministic;

/** Pure timing helpers used by deterministic mining checks and unit tests. */
public final class MiningTiming {

    private static final long TICK_MS = 50L;

    private MiningTiming() {}

    /**
     * Conservative lower bound between the first damage observation and a
     * legitimate break. Bukkit's Block#getBreakSpeed returns progress/tick.
     *
     * One tick is deliberately removed from the mathematical total because the
     * first mining-progress tick can overlap the initial server observation.
     * This biases the check toward false negatives rather than false positives.
     */
    public static long conservativeMinimumBreakMs(double breakSpeed) {
        if (!Double.isFinite(breakSpeed) || breakSpeed <= 0.0 || breakSpeed >= 1.0) {
            return 0L;
        }
        long ticks = (long) Math.ceil(1.0 / breakSpeed);
        return Math.max(0L, (ticks - 1L) * TICK_MS);
    }

    public static boolean isTooFast(long observedMs,
                                    long vanillaMinimumMs,
                                    double minimumRatio,
                                    long toleranceMs) {
        if (observedMs < 0L || vanillaMinimumMs <= 0L) return false;
        double ratio = clamp(minimumRatio, 0.05, 1.0);
        long tolerance = Math.max(0L, toleranceMs);
        double threshold = (vanillaMinimumMs * ratio) - tolerance;
        return threshold > 0.0 && observedMs < threshold;
    }

    public static double severity(long observedMs,
                                  long vanillaMinimumMs,
                                  double minimumRatio,
                                  long toleranceMs) {
        if (!isTooFast(observedMs, vanillaMinimumMs, minimumRatio, toleranceMs)) return 0.0;
        double threshold = Math.max(1.0, (vanillaMinimumMs * clamp(minimumRatio, 0.05, 1.0))
                - Math.max(0L, toleranceMs));
        double deficit = 1.0 - (Math.max(0L, observedMs) / threshold);
        return clamp(0.65 + (deficit * 0.35), 0.0, 1.0);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
