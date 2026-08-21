package me.hackerguardian.main.moderation.punish;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class TimeFormat {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
                    .withZone(ZoneId.systemDefault());

    private TimeFormat() {}

    /** e.g. 28d 23h 44m 45s */
    public static String remaining(long nowMs, long expiresAtMs) {
        long diff = expiresAtMs - nowMs;
        if (diff <= 0) return "0s";

        long seconds = diff / 1000L;

        long days = seconds / 86400; seconds %= 86400;
        long hours = seconds / 3600; seconds %= 3600;
        long mins = seconds / 60;    seconds %= 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (days > 0 || hours > 0) sb.append(hours).append("h ");
        if (days > 0 || hours > 0 || mins > 0) sb.append(mins).append("m ");
        sb.append(seconds).append("s");

        return sb.toString().trim();
    }

    /** e.g. 2026-01-02 18:30:00 CET */
    public static String dateTime(long epochMs) {
        return DATE_FMT.format(Instant.ofEpochMilli(epochMs));
    }
}
