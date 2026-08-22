package me.hackerguardian.main.replay;

public enum ReplayTriggerType {
    MANUAL,
    AI,
    MODERATION,
    REPORT,

    /** Detection v2 evidence trigger; distinct from the legacy AI trigger. */
    DETECTION
}
