package me.hackerguardian.main.detection;

/**
 * High-level category for a detection finding.
 *
 * Categories are intentionally independent from any specific AI/model
 * implementation so heuristic detectors and future ML detectors can share the
 * same evidence pipeline.
 */
public enum DetectionCategory {
    MOVEMENT,
    COMBAT,
    INTERACTION,
    BLOCK,
    NETWORK,
    BEHAVIOR,
    OTHER
}
