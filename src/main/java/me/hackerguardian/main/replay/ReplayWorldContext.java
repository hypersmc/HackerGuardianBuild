package me.hackerguardian.main.replay;

/** Immutable environment metadata captured with a replay world snapshot. */
public record ReplayWorldContext(
        String world,
        String minecraftVersion,
        String environment,
        long gameTime,
        long fullTime,
        boolean storm,
        boolean thundering,
        String resourcePackId
) {}
