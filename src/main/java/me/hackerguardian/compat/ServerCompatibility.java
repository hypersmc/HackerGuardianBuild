package me.hackerguardian.compat;

import com.comphenix.protocol.ProtocolLibrary;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.logging.Logger;

/**
 * Runtime capability report for the Paper/Spigot side of HackerGuardian.
 *
 * Core detection intentionally relies on stable Bukkit APIs. Packet-heavy
 * features such as synthetic-player probes are only enabled on versions that
 * HackerGuardian has explicitly verified and when ProtocolLib is available.
 */
public final class ServerCompatibility {

    public static final String MIN_VERIFIED_VERSION = "1.20.x";
    public static final String MAX_VERIFIED_VERSION = "26.2";

    private final MinecraftVersion minecraftVersion;
    private final String bukkitVersion;
    private final boolean protocolLibEnabled;
    private final String protocolLibVersion;

    private ServerCompatibility(MinecraftVersion minecraftVersion,
                                String bukkitVersion,
                                boolean protocolLibEnabled,
                                String protocolLibVersion) {
        this.minecraftVersion = minecraftVersion;
        this.bukkitVersion = bukkitVersion == null ? "" : bukkitVersion;
        this.protocolLibEnabled = protocolLibEnabled;
        this.protocolLibVersion = protocolLibVersion == null ? "" : protocolLibVersion;
    }

    public static ServerCompatibility detect() {
        // Bukkit#getMinecraftVersion() is not part of the Spigot API used by
        // our 1.20.x lower-bound compile. Bukkit#getBukkitVersion() is stable
        // across the supported range and includes the Minecraft version, e.g.
        // "1.20.2-R0.1-SNAPSHOT", which MinecraftVersion parses deliberately.
        MinecraftVersion minecraft = MinecraftVersion.parse(Bukkit.getBukkitVersion());
        Plugin protocolLib = Bukkit.getPluginManager().getPlugin("ProtocolLib");
        boolean enabled = protocolLib != null && protocolLib.isEnabled();
        String version = protocolLib == null ? "" : protocolLib.getDescription().getVersion();
        return new ServerCompatibility(minecraft, Bukkit.getVersion(), enabled, version);
    }

    public MinecraftVersion minecraftVersion() {
        return minecraftVersion;
    }

    public String minecraftVersionString() {
        return minecraftVersion.normalized();
    }

    public String familyId() {
        return minecraftVersion.family().id();
    }

    public String bukkitVersion() {
        return bukkitVersion;
    }

    public boolean isProtocolLibEnabled() {
        return protocolLibEnabled;
    }

    public String protocolLibVersion() {
        return protocolLibVersion;
    }

    public boolean supportsCoreRuntime() {
        return minecraftVersion.allowsCoreRuntime();
    }

    /**
     * Synthetic player packets are deliberately fail-closed on unverified
     * future Minecraft versions. Learning Mode can still collect Bukkit-level
     * candidate behavior when this returns false.
     */
    public boolean supportsSyntheticPlayerPackets() {
        return minecraftVersion.isVerifiedSupported() && protocolLibEnabled;
    }

    public String syntheticPlayerUnavailableReason() {
        if (!protocolLibEnabled) return "ProtocolLib is not enabled";
        if (minecraftVersion.isFutureUnverified()) {
            return "Minecraft " + minecraftVersion.normalized()
                    + " is newer than the verified packet range ending at " + MAX_VERIFIED_VERSION;
        }
        if (!minecraftVersion.isVerifiedSupported()) {
            return "Minecraft " + minecraftVersion.normalized()
                    + " is outside the verified range " + MIN_VERIFIED_VERSION + " - " + MAX_VERIFIED_VERSION;
        }
        return "available";
    }

    /** Returns -1 if ProtocolLib cannot resolve the connected client's protocol. */
    public int clientProtocolVersion(Player player) {
        if (!protocolLibEnabled || player == null) return -1;
        try {
            return ProtocolLibrary.getProtocolManager().getProtocolVersion(player);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    public void log(Logger logger) {
        if (logger == null) return;

        String base = "Minecraft compatibility: server=" + minecraftVersion.normalized()
                + ", family=" + familyId()
                + ", support=" + minecraftVersion.supportLevel()
                + ", ProtocolLib=" + (protocolLibEnabled
                    ? (protocolLibVersion.isBlank() ? "enabled" : protocolLibVersion)
                    : "unavailable") + ".";

        if (minecraftVersion.isVerifiedSupported()) {
            logger.info(base);
        } else if (minecraftVersion.isFutureUnverified()) {
            logger.warning(base + " Core Bukkit features remain available, but packet-heavy features are disabled until verified.");
        } else {
            logger.warning(base + " HackerGuardian's verified Paper/Spigot support starts at " + MIN_VERIFIED_VERSION + ".");
        }
    }
}
