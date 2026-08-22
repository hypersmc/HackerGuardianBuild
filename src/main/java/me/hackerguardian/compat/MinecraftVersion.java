package me.hackerguardian.compat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsed Minecraft server version plus HackerGuardian's verified support state.
 *
 * This deliberately understands both Mojang's historical 1.x numbering and
 * the year/drop numbering introduced with 26.1. Version checks belong here so
 * packet and Bukkit compatibility code never grows ad-hoc string comparisons.
 */
public final class MinecraftVersion {

    private static final Pattern VERSION_TOKEN = Pattern.compile("(?<!\\d)(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?");

    public enum Family {
        MC_1_20("1.20"),
        MC_1_21("1.21"),
        MC_26("26.x"),
        FUTURE("future"),
        LEGACY("legacy"),
        UNKNOWN("unknown");

        private final String id;

        Family(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    public enum SupportLevel {
        VERIFIED_SUPPORTED,
        UNVERIFIED_FUTURE,
        UNSUPPORTED
    }

    private final String raw;
    private final int major;
    private final int minor;
    private final int patch;
    private final Family family;
    private final SupportLevel supportLevel;

    private MinecraftVersion(String raw,
                             int major,
                             int minor,
                             int patch,
                             Family family,
                             SupportLevel supportLevel) {
        this.raw = raw == null ? "" : raw;
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.family = family;
        this.supportLevel = supportLevel;
    }

    public static MinecraftVersion parse(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return unknown(value);

        // If a Bukkit implementation string is ever passed instead of
        // Bukkit#getMinecraftVersion(), prefer the explicit "MC:" token.
        int mcMarker = value.indexOf("MC:");
        String search = mcMarker >= 0 ? value.substring(mcMarker + 3) : value;
        Matcher matcher = VERSION_TOKEN.matcher(search);
        if (!matcher.find()) return unknown(value);

        int major = parsePart(matcher.group(1));
        int minor = parsePart(matcher.group(2));
        int patch = parsePart(matcher.group(3));

        if (major == 1) {
            if (minor < 20) {
                return new MinecraftVersion(value, major, minor, patch,
                        Family.LEGACY, SupportLevel.UNSUPPORTED);
            }
            if (minor == 20) {
                return new MinecraftVersion(value, major, minor, patch,
                        Family.MC_1_20, SupportLevel.VERIFIED_SUPPORTED);
            }
            if (minor == 21 && patch <= 11) {
                return new MinecraftVersion(value, major, minor, patch,
                        Family.MC_1_21, SupportLevel.VERIFIED_SUPPORTED);
            }
            return new MinecraftVersion(value, major, minor, patch,
                    Family.FUTURE, SupportLevel.UNVERIFIED_FUTURE);
        }

        if (major == 26) {
            if (minor >= 1 && minor <= 2) {
                return new MinecraftVersion(value, major, minor, patch,
                        Family.MC_26, SupportLevel.VERIFIED_SUPPORTED);
            }
            if (minor > 2) {
                return new MinecraftVersion(value, major, minor, patch,
                        Family.FUTURE, SupportLevel.UNVERIFIED_FUTURE);
            }
            return new MinecraftVersion(value, major, minor, patch,
                    Family.UNKNOWN, SupportLevel.UNSUPPORTED);
        }

        if (major > 26) {
            return new MinecraftVersion(value, major, minor, patch,
                    Family.FUTURE, SupportLevel.UNVERIFIED_FUTURE);
        }

        return new MinecraftVersion(value, major, minor, patch,
                Family.UNKNOWN, SupportLevel.UNSUPPORTED);
    }

    private static MinecraftVersion unknown(String raw) {
        return new MinecraftVersion(raw, -1, -1, -1,
                Family.UNKNOWN, SupportLevel.UNSUPPORTED);
    }

    private static int parsePart(String value) {
        if (value == null || value.isEmpty()) return 0;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    public String raw() { return raw; }
    public int major() { return major; }
    public int minor() { return minor; }
    public int patch() { return patch; }
    public Family family() { return family; }
    public SupportLevel supportLevel() { return supportLevel; }

    public boolean isVerifiedSupported() {
        return supportLevel == SupportLevel.VERIFIED_SUPPORTED;
    }

    public boolean isFutureUnverified() {
        return supportLevel == SupportLevel.UNVERIFIED_FUTURE;
    }

    /** Core Bukkit behavior can continue on future versions, but packet-heavy features are gated. */
    public boolean allowsCoreRuntime() {
        return supportLevel != SupportLevel.UNSUPPORTED;
    }

    public String normalized() {
        if (major < 0) return "unknown";
        if (patch > 0) return major + "." + minor + "." + patch;
        return major + "." + minor;
    }

    @Override
    public String toString() {
        return normalized() + " [" + family.id() + ", " + supportLevel + "]";
    }
}
