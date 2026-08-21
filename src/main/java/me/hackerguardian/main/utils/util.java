package me.hackerguardian.main.utils;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.plugin.Plugin;

public class util {

    public String detectPluginProtocollib() {
        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") != null) {
            return ChatColor.DARK_GRAY + "|      " + ChatColor.GREEN + "ProtocolLib" + ChatColor.RESET + "\n";
        }
        return ChatColor.DARK_GRAY + "|      " + ChatColor.RED + "ProtocolLib" + ChatColor.RESET + "\n";
    }

    public String detectPluginSkulls() {
        if (Bukkit.getPluginManager().getPlugin("Skulls") != null) {
            return ChatColor.DARK_GRAY + "|      " + ChatColor.GREEN + "Skulls" + ChatColor.RESET + "\n";
        }
        return ChatColor.DARK_GRAY + "|      " + ChatColor.RED + "Skulls" + ChatColor.RESET + "\n";
    }

    public String detectPluginViaversion() {
        if (Bukkit.getPluginManager().getPlugin("ViaVersion") != null) {
            return ChatColor.DARK_GRAY + "|      " + ChatColor.GREEN + "ViaVersion" + ChatColor.RESET + "\n";
        }
        return ChatColor.DARK_GRAY + "|      " + ChatColor.RED + "ViaVersion" + ChatColor.RESET + "\n";
    }

    public String detectPluginProtocolsupport() {
        if (Bukkit.getPluginManager().getPlugin("ProtocolSupport") != null) {
            return ChatColor.DARK_GRAY + "|      " + ChatColor.GREEN + "ProtocolSupport" + ChatColor.RESET + "\n";
        }
        return ChatColor.DARK_GRAY + "|      " + ChatColor.RED + "ProtocolSupport" + ChatColor.RESET + "\n";
    }

    public String detectSettingWebsite(Plugin pl) {
        if (pl.getConfig().getBoolean("Settings.UseWebsiteFunction")) {
            return ChatColor.DARK_GRAY + "|      " + ChatColor.GREEN + "Website Addon" + ChatColor.RESET + "\n";
        }
        return ChatColor.DARK_GRAY + "|      " + ChatColor.RED + "Website Addon" + ChatColor.RESET + "\n";
    }

    public String detectSettingsSecureLink(Plugin pl) {
        if (pl.getConfig().getBoolean("Settings.hg_secure_link")) {
            return ChatColor.DARK_GRAY + "|      " + ChatColor.GREEN + "Secure Link" + ChatColor.RESET + "\n";
        }
        return ChatColor.DARK_GRAY + "|      " + ChatColor.RED + "Secure Link" + ChatColor.RESET + "\n";
    }
}
