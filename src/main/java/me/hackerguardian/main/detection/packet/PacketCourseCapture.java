package me.hackerguardian.main.detection.packet;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-course packet-thread research. Staff-selected phase boundaries are set
 * on the main thread. Captures only bounded aggregate counters and headings;
 * never stores a packet trace or feeds a detector/enforcement decision.
 *
 * SERVER-OBSERVED sprint state and scheduler tick hints are not authenticated
 * client input or simulation ticks. The local heading distribution intentionally
 * excludes the first two modeled positions of each uninterrupted local chain.
 */
public final class PacketCourseCapture {
    public enum Phase {
        WALK_STRAIGHT(false, "Walk straight"),
        WALK_TURN_1(false, "Walk 90 turn 1"),
        WALK_TURN_2(false, "Walk 90 turn 2"),
        SPRINT_STRAIGHT(true, "Sprint straight"),
        SPRINT_TURN_1(true, "Sprint 90 turn 1"),
        SPRINT_TURN_2(true, "Sprint 90 turn 2");

        private final boolean sprint;
        private final String label;
        Phase(boolean sprint, String label) {
            this.sprint = sprint;
            this.label = label;
        }
        public boolean expectsSprint() { return sprint; }
        public String label() { return label; }
    }

    public record PhaseResult(Phase phase, long packets, long modeled,
                              long outsideEnvelope, long timingRejects,
                              long gapRejects, long ambiguousRejects,
                              long staleRejects, long warmups,
                              long contextRejects, long correctionRejects,
                              long otherRejects, long matchedSprint,
                              long mismatchedSprint, long unknownSprint,
                              long adjacentHints, long repeatedHints,
                              long twoTickGaps, long largerTickGaps,
                              PacketHeadingAngles.Snapshot headings,
                              PacketResearchRuns.Snapshot runs) {}

    private static final class Bucket {
        private final Phase phase;
        private final PacketHeadingAngles headingAngles = new PacketHeadingAngles();
        private final PacketResearchRuns runs = new PacketResearchRuns();
        private long packets, modeled, outside, timing, gaps, ambiguous, stale;
        private long warmups, context, correction, other, matching, mismatching, unknown;
        private long adjacent, repeated, gapTwo, gapLarger;
        private Long previousTick;
        private long previousEpoch;
        private MovementPacketBuffer.Position older, newest;

        Bucket(Phase phase) { this.phase = phase; }

        void add(PacketGroundReachability.Status status,
                 MovementPacketBuffer.PacketState packet,
                 PacketGroundReachability.Context context) {
            packets++;
            if (context == null || context.serverState() == null) {
                unknown++;
            } else if (context.serverState().probeContext().sprinting() == phase.expectsSprint()) {
                matching++;
            } else {
                mismatching++;
            }
            if (context != null && context.serverTick() > 0) {
                if (previousTick != null && previousEpoch == context.correctionEpoch()) {
                    long diff = context.serverTick() - previousTick;
                    if (diff == 1) adjacent++;
                    else if (diff == 0) repeated++;
                    else if (diff == 2) gapTwo++;
                    else if (diff > 2) gapLarger++;
                }
                previousTick = context.serverTick();
                previousEpoch = context.correctionEpoch();
            } else {
                previousTick = null;
            }

            runs.observe(status);
            if (status == PacketGroundReachability.Status.CONSERVATIVE_REACHABLE
                    || status == PacketGroundReachability.Status.OUTSIDE_RESEARCH_ENVELOPE) {
                modeled++;
                if (status == PacketGroundReachability.Status.OUTSIDE_RESEARCH_ENVELOPE) {
                    outside++;
                }
                if (packet != null && packet.decoded() && packet.position() != null) {
                    MovementPacketBuffer.Position current = packet.position();
                    if (older != null && newest != null) {
                        headingAngles.observe(newest.x() - older.x(), newest.z() - older.z(),
                                current.x() - newest.x(), current.z() - newest.z());
                    }
                    older = newest;
                    newest = current;
                } else {
                    older = newest = null;
                }
                return;
            }
            older = newest = null;
            switch (status) {
                case TICK_GAP -> { timing++; gaps++; }
                case AMBIGUOUS_TICK -> { timing++; ambiguous++; }
                case STALE_SERVER_SAMPLE -> { timing++; stale++; }
                case WARMUP -> warmups++;
                case CONTEXT_CHANGED, UNSUPPORTED_CONTEXT -> this.context++;
                case RECENT_CORRECTION -> correction++;
                default -> other++;
            }
        }

        PhaseResult snapshot() {
            return new PhaseResult(phase, packets, modeled, outside, timing,
                    gaps, ambiguous, stale, warmups, context, correction, other,
                    matching, mismatching, unknown, adjacent, repeated, gapTwo,
                    gapLarger, headingAngles.snapshot(), runs.snapshot());
        }
        void end() { runs.correction(); }
    }

    private final ArrayList<Bucket> buckets = new ArrayList<>(Phase.values().length);
    private Bucket active;
    private boolean finished;

    public synchronized void enter(Phase phase) {
        if (finished || phase == null || buckets.size() >= Phase.values().length
                || phase.ordinal() != buckets.size()) {
            throw new IllegalStateException("Invalid packet course phase");
        }
        if (active != null) active.end();
        active = new Bucket(phase);
        buckets.add(active);
    }

    /** Invoked once after the existing model evaluated an incoming POSITION packet. */
    public synchronized void observe(PacketGroundReachability.Status outcome,
                                     MovementPacketBuffer.PacketState packet,
                                     PacketGroundReachability.Context context) {
        if (!finished && active != null) active.add(outcome, packet, context);
    }

    public synchronized List<PhaseResult> finish() {
        if (!finished) {
            finished = true;
            if (active != null) active.end();
            active = null;
        }
        ArrayList<PhaseResult> result = new ArrayList<>(buckets.size());
        for (Bucket bucket : buckets) result.add(bucket.snapshot());
        return List.copyOf(result);
    }
}
