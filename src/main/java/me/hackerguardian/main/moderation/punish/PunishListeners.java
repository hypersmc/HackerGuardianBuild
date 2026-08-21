package me.hackerguardian.main.moderation.punish;

import me.hackerguardian.main.HackerGuardian;
import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.net.InetAddress;
import java.util.Optional;

public final class PunishListeners implements Listener {

    private final HackerGuardian plugin;
    private final PunishmentRepository repo;

    public PunishListeners(HackerGuardian plugin, PunishmentRepository repo) {
        this.plugin = plugin;
        this.repo = repo;
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        long now = System.currentTimeMillis();
        String uuid = e.getUniqueId().toString();
        boolean behindProxy = plugin.getConfig().getBoolean("Settings.behind_proxy", false);
        String serverName = plugin.getConfig().getString("Settings.server_name", "default");

        // IP ban check
        InetAddress addr = e.getAddress();
        if (addr != null) {
            String ip = addr.getHostAddress();
            try {
                Optional<PunishmentRepository.IpBanRow> ipBan = repo.getActiveIpBan(ip, now, behindProxy, serverName);
                if (ipBan.isPresent()) {
                    PunishmentRepository.IpBanRow b = ipBan.get();

                    boolean temp = (b.expiresAt() != null);
                    String key = temp ? "Punishments.messages.ipban_join_temp" : "Punishments.messages.ipban_join_perm";

                    String expiresLeft = temp ? TimeFormat.remaining(now, b.expiresAt()) : "Never";
                    String expiresDate = temp ? TimeFormat.dateTime(b.expiresAt()) : "Never";

                    String msg = plugin.getConfig().getString(key,
                            "&cYou are IP-banned.\n&7Reason: &f%reason%");
                    msg = msg.replace("%reason%", b.reason())
                            .replace("%expires%", expiresLeft)
                            .replace("%expires_date%", expiresDate)
                            .replace("%id%", String.valueOf(b.id()));

                    e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, color(msg));
                    return;
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to check IP ban for " + e.getName() + ": " + ex.getMessage());
            }
        }

        // Ban check
        try {
            Optional<PunishmentRow> ban = repo.getActivePunishment(PunishmentType.BAN, uuid, now, behindProxy, serverName);
            if (ban.isPresent()) {
                PunishmentRow b = ban.get();

                boolean temp = (b.expiresAt() != null);
                String key = temp ? "Punishments.messages.ban_join_temp" : "Punishments.messages.ban_join_perm";

                String expiresLeft = temp ? TimeFormat.remaining(now, b.expiresAt()) : "Never";
                String expiresDate = temp ? TimeFormat.dateTime(b.expiresAt()) : "Never";

                String msg = plugin.getConfig().getString(key,
                        "&cYou are banned.\n&7Reason: &f%reason%\n&7Expires: &f%expires%");
                msg = msg.replace("%reason%", b.reason())
                        .replace("%expires%", expiresLeft)
                        .replace("%expires_date%", expiresDate)
                        .replace("%id%", String.valueOf(b.id()));

                e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, color(msg));
                return;
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to check player ban for " + e.getName() + ": " + ex.getMessage());
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        long now = System.currentTimeMillis();
        String uuid = e.getPlayer().getUniqueId().toString();
        boolean behindProxy = plugin.getConfig().getBoolean("Settings.behind_proxy", false);
        String serverName = plugin.getConfig().getString("Settings.server_name", "default");

        try {
            Optional<PunishmentRow> mute = repo.getActivePunishment(PunishmentType.MUTE, uuid, now, behindProxy, serverName);
            if (mute.isEmpty()) return;

            e.setCancelled(true);
            PunishmentRow m = mute.get();

            boolean temp = (m.expiresAt() != null);
            String expiresLeft = temp ? TimeFormat.remaining(now, m.expiresAt()) : "Never";
            String expiresDate = temp ? TimeFormat.dateTime(m.expiresAt()) : "Never";

            String msg = plugin.getConfig().getString("Punishments.messages.muted_chat",
                    "&cYou are muted.\n&7Reason: &f%reason%\n&7Expires: &f%expires%");
            msg = msg.replace("%reason%", m.reason())
                    .replace("%expires%", expiresLeft)
                    .replace("%expires_date%", expiresDate)
                    .replace("%id%", String.valueOf(m.id()));

            e.getPlayer().sendMessage(color(msg));
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to check mute for " + e.getPlayer().getName() + ": " + ex.getMessage());
        }
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
