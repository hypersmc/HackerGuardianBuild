package me.hackerguardian.main.moderation.punish;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DurationParser {

    // 10m, 2h, 7d, 1w
    private static final Pattern P = Pattern.compile("^(\\d{1,9})([smhdw])$");

    private DurationParser() {}

    public static Long parseToMsOrNull(String token) {
        if (token == null) return null;
        String t = token.trim().toLowerCase(Locale.ROOT);
        Matcher m = P.matcher(t);
        if (!m.matches()) return null;

        long n;
        try {
            n = Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }

        String unit = m.group(2);
        return switch (unit) {
            case "s" -> n * 1000L;
            case "m" -> n * 60_000L;
            case "h" -> n * 3_600_000L;
            case "d" -> n * 86_400_000L;
            case "w" -> n * 604_800_000L;
            default -> null;
        };
    }
}