package me.hackerguardian.bungee.moderation;

import me.hackerguardian.bungee.HackerGuardianB;
import me.hackerguardian.bungee.utils.BDatabase;
import me.hackerguardian.main.moderation.punish.TimeFormat;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.LoginEvent;
import net.md_5.bungee.api.event.PreLoginEvent;
import net.md_5.bungee.api.event.ServerConnectEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ProxyPunishEnforcer implements Listener {

    private final HackerGuardianB plugin;
    private final BDatabase sql;
    private final Set<UUID> bypassNextConnect = ConcurrentHashMap.newKeySet();

    public ProxyPunishEnforcer(HackerGuardianB plugin, BDatabase sql) {
        this.plugin = plugin;
        this.sql = sql;
    }

    @EventHandler
    public void onPreLogin(PreLoginEvent e) {
        PendingConnection c = e.getConnection();
        String ip = (c.getAddress() != null && c.getAddress().getAddress() != null)
                ? c.getAddress().getAddress().getHostAddress()
                : null;

        if (ip == null) return;

        e.registerIntent(plugin);
        ProxyServer.getInstance().getScheduler().runAsync(plugin, () -> {
            try {
                long now = System.currentTimeMillis();
                BanRow ipBan = sql.getActiveIpBanWide(ip, now);
                if (ipBan != null) {
                    e.setCancelled(true);
                    e.setCancelReason(new TextComponent(color(buildIpBanKick(ipBan, now))));
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to check wide IP ban: " + ex.getMessage());
            } finally {
                e.completeIntent(plugin);
            }
        });
    }

    @EventHandler
    public void onLogin(LoginEvent e) {
        PendingConnection c = e.getConnection();
        UUID uuid = c.getUniqueId();
        if (uuid == null) return;

        e.registerIntent(plugin);
        ProxyServer.getInstance().getScheduler().runAsync(plugin, () -> {
            try {
                long now = System.currentTimeMillis();
                BanRow wideBan = sql.getActivePlayerBanWide(uuid.toString(), now);
                if (wideBan != null) {
                    e.setCancelled(true);
                    e.setCancelReason(new TextComponent(color(buildPlayerBanKick(wideBan, now))));
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to check wide player ban: " + ex.getMessage());
            } finally {
                e.completeIntent(plugin);
            }
        });
    }

    @EventHandler
    public void onServerConnect(ServerConnectEvent e) {
        ProxiedPlayer player = e.getPlayer();
        UUID uuid = player.getUniqueId();
        if (uuid == null) return;

        if (bypassNextConnect.remove(uuid)) return;

        String targetServer = e.getTarget().getName();
        e.setCancelled(true);

        ProxyServer.getInstance().getScheduler().runAsync(plugin, () -> {
            long now = System.currentTimeMillis();

            BanRow serverBan;
            try {
                serverBan = sql.getActivePlayerBanServer(uuid.toString(), now, targetServer);
            } catch (SQLException ex) {
                plugin.getLogger().warning("Failed to check server-scoped player ban for "
                        + player.getName() + ": " + ex.getMessage());
                return;
            }

            String ipPlain = sql.extractIp(player);
            BanRow serverIpBan;
            try {
                serverIpBan = (ipPlain != null)
                        ? sql.getActiveIpBanServer(ipPlain, now, targetServer)
                        : null;
            } catch (SQLException ex) {
                plugin.getLogger().warning("Failed to check server-scoped IP ban for "
                        + player.getName() + ": " + ex.getMessage());
                return;
            }

            if (serverBan != null || serverIpBan != null) {
                BanRow row = serverBan != null ? serverBan : serverIpBan;
                String msg = serverBan != null
                        ? buildPlayerBanKick(row, now)
                        : buildIpBanKick(row, now);
                player.disconnect(new TextComponent(color(msg)));
                return;
            }

            bypassNextConnect.add(uuid);
            player.connect(e.getTarget());
        });
    }

    private String buildPlayerBanKick(BanRow row, long nowMs) {
        boolean temp = row.expiresAt != null;
        String key = temp ? "Punishments.messages.ban_join_temp" : "Punishments.messages.ban_join_perm";
        String expiresLeft = temp ? TimeFormat.remaining(nowMs, row.expiresAt) : "Never";
        String expiresDate = temp ? TimeFormat.dateTime(row.expiresAt) : "Never";

        String msg = HackerGuardianB.configuration.getString(key,
                "&cYou are banned.\n&7Reason: &f%reason%\n&7Expires: &f%expires%");
        return msg.replace("%reason%", row.reason)
                .replace("%expires%", expiresLeft)
                .replace("%expires_date%", expiresDate)
                .replace("%id%", String.valueOf(row.id));
    }

    private String buildIpBanKick(BanRow row, long nowMs) {
        String expires = row.expiresAt == null ? "Never" : TimeFormat.remaining(nowMs, row.expiresAt);
        return "&cYou are IP-banned.\n\n&7Reason: &f" + row.reason
                + "\n&7Expires: &f" + expires + "\n\n&7IP Ban ID: &f#" + row.id;
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
