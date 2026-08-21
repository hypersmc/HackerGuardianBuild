package me.hackerguardian.main.moderation.punish;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class PunishAnnouncer {

    private final JavaPlugin plugin;

    public PunishAnnouncer(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void broadcast(PunishFlags flags, String staffPermNotify, String publicPermUse, String messageStaff, String messagePublic) {
        if (flags.publicBroadcast) {
            Bukkit.broadcastMessage(color(messagePublic));
            // Also send staff-format copy to staff notify group (optional, but useful)
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission(staffPermNotify)) {
                    p.sendMessage(color(messageStaff));
                }
            }
            return;
        }

        // silent
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission(staffPermNotify)) {
                p.sendMessage(color(messageStaff));
            }
        }
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
