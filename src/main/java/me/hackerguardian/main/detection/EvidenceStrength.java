package me.hackerguardian.main.detection;

/**
 * Semantic strength of one piece of detection evidence.
 *
 * This is deliberately separate from the numeric reliability value. Strength
 * describes what kind of claim a check is making, while reliability can still
 * be reduced for a specific observation because of latency, TPS, geometry, or
 * other uncertainty.
 */
public enum EvidenceStrength {
    /** A protocol/physics invariant was violated with essentially no legitimate path. */
    HARD,

    /** Very strong deterministic evidence, but still allows for server/plugin edge cases. */
    STRONG,

    /** Useful supporting evidence that should normally be corroborated. */
    SOFT,

    /** Statistical/model/behavioral evidence; unusual does not itself mean cheating. */
    HEURISTIC
}
