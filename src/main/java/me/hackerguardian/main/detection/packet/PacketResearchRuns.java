package me.hackerguardian.main.detection.packet;

import java.util.Arrays;

/**
 * Observe-only sequence lengths from existing packet reachability outcomes.
 *
 * A "valid run" is consecutive POSITION/POSITION_LOOK packet observations
 * whose ORIGINAL reachability result was modeled. A timing "gap cluster"
 * is one or more timing-rejected packet observations with no intervening
 * modeled triple, optionally separated by WARMUP. Non-timing exclusions
 * and explicit server correction events close a cluster.
 *
 * These are NETWORK-ARRIVAL / SERVER-SCHEDULER-HINT research sequences,
 * NOT consecutive authenticated client simulation ticks, network loss
 * estimates, or evidence of cheating. This class never affects eligibility.
 *
 * Retains at most 128 completed lengths per group; lifetime counts and
 * live (still-open) lengths are constant-size primitive counters.
 * Percentiles sort only on staff snapshot requests, not packet callbacks.
 */
public final class PacketResearchRuns {
    private static final int CAPACITY = 128;

    public record Distribution(long completed, int retained,
                               Long p50, Long p95, Long max) {}

    public record Snapshot(Distribution acceptedRuns,
                           Distribution timingClusters,
                           long activeAcceptedRun, long activeTimingCluster,
                           long totalModeled, long totalTimingRejects,
                           long warmupsInsideTimingClusters,
                           long singleRuns, long runs2to4, long runs5to19,
                           long runs20Plus,
                           long isolatedTimingClusters, long multiTimingClusters) {}

    private final Ring completedRuns = new Ring();
    private final Ring completedTimingClusters = new Ring();

    private long activeRun, activeCluster;
    private long modeled, timingRejected, warmupsWithinCluster;
    private long singleRuns, runs2to4, runs5to19, runs20Plus;
    private long singleClusters, multiClusters;

    /** Record exactly one original reachability result per incoming position packet. */
    public synchronized void observe(PacketGroundReachability.Status status) {
        if (status == null) throw new IllegalArgumentException("status required");

        if (status == PacketGroundReachability.Status.CONSERVATIVE_REACHABLE
                || status == PacketGroundReachability.Status.OUTSIDE_RESEARCH_ENVELOPE) {
            closeCluster();
            modeled++;
            activeRun++;
            return;
        }

        // Every non-modeled position observation ends an accepted-model run.
        closeRun();
        if (status == PacketGroundReachability.Status.TICK_GAP
                || status == PacketGroundReachability.Status.AMBIGUOUS_TICK
                || status == PacketGroundReachability.Status.STALE_SERVER_SAMPLE) {
            timingRejected++;
            activeCluster++;
        } else if (status == PacketGroundReachability.Status.WARMUP
                && activeCluster != 0) {
            // A WARMUP can occur between timing failures without an
            // intervening modeled triple. Do not split the gap cluster.
            warmupsWithinCluster++;
        } else {
            // Context, invalid claims, unavailable version or pending
            // correction are distinct causes, not part of a timing burst.
            closeCluster();
        }
    }

    /** A server correction is a boundary even without a position packet. */
    public synchronized void correction() {
        closeRun();
        closeCluster();
    }

    private void closeRun() {
        if (activeRun == 0) return;
        completedRuns.add(activeRun);
        if (activeRun == 1) singleRuns++;
        else if (activeRun <= 4) runs2to4++;
        else if (activeRun <= 19) runs5to19++;
        else runs20Plus++;
        activeRun = 0;
    }

    private void closeCluster() {
        if (activeCluster == 0) return;
        completedTimingClusters.add(activeCluster);
        if (activeCluster == 1) singleClusters++;
        else multiClusters++;
        activeCluster = 0;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(completedRuns.snapshot(), completedTimingClusters.snapshot(),
                activeRun, activeCluster, modeled, timingRejected,
                warmupsWithinCluster, singleRuns, runs2to4,
                runs5to19, runs20Plus, singleClusters, multiClusters);
    }

    private static final class Ring {
        private final long[] values = new long[CAPACITY];
        private int size, next;
        private long total;

        void add(long length) {
            if (length <= 0) throw new IllegalArgumentException("Completed run must be positive");
            values[next] = length;
            next = (next + 1) % CAPACITY;
            size = Math.min(CAPACITY, size + 1);
            total++;
        }

        Distribution snapshot() {
            if (size == 0) return new Distribution(total, 0, null, null, null);
            long[] ordered = Arrays.copyOf(values, size);
            Arrays.sort(ordered);
            return new Distribution(total, size, nearest(ordered, .50),
                    nearest(ordered, .95), ordered[size - 1]);
        }

        private static long nearest(long[] sorted, double fraction) {
            return sorted[Math.max(0, (int) Math.ceil(sorted.length * fraction) - 1)];
        }
    }
}
