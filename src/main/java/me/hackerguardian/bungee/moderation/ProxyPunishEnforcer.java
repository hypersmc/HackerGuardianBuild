package me.hackerguardian.bungee.moderation;

import me.hackerguardian.bungee.HackerGuardianB;
import me.hackerguardian.bungee.utils.BMySQL;
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
    private final BMySQL sql;

    // prevents recursive re-connect when we call player.connect() ourselves
    private final Set<UUID> bypassNextConnect = ConcurrentHashMap.newKeySet();

    public ProxyPunishEnforcer(HackerGuardianB plugin, BMySQL sql) {
        this.plugin = plugin;
        this.sql = sql;
    }

    // 1) Earliest: block WIDE IP bans
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
                BanRow ipBan = sql.getActivePlayerBanWide(ip, now);
                if (ipBan != null) {
                    e.setCancelled(true);
                    e.setCancelReason(new TextComponent(color(buildIpBanKick(ipBan, now))));
                }
            } catch (Exception ignored) {
            } finally {
                e.completeIntent(plugin);
            }
        });
    }

    // 2) Next: block WIDE UUID bans
    @EventHandler
    public void onLogin(LoginEvent e) {
        PendingConnection c = e.getConnection();
        UUID uuid = c.getUniqueId(); // reliable here
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
            } catch (Exception ignored) {
            } finally {
                e.completeIntent(plugin);
            }
        });
    }

    // 3) Server-specific scope check at connect time
    @EventHandler
    public void onServerConnect(ServerConnectEvent e) {
        ProxiedPlayer player = e.getPlayer();
        UUID uuid = player.getUniqueId();
        if (uuid == null) return;

        // If we are doing our "manual connect" after async check, skip once.
        if (bypassNextConnect.remove(uuid)) return;

        String targetServer = e.getTarget().getName(); // e.g. "pvp"
        String ip = (player.getSocketAddress() != null)
                ? player.getSocketAddress().toString()
                : null;

        // Cancel now, decide async.
        // e.setCancelled(true);

        ProxyServer.getInstance().getScheduler().runAsync(plugin, () -> {
            long now = System.currentTimeMillis();

            // IMPORTANT:
            // - WIDE already blocked in PreLogin/Login
            // - here we only need SERVER scoped bans for this target
            BanRow serverBan = null;
            try {
                serverBan = sql.getActivePlayerBanServer(uuid.toString(), now, targetServer);
            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }

            String ipPlain = sql.extractIp(player); // implement from socket address safely (shown below)
            BanRow serverIpBan = null;
            try {
                serverIpBan = (ipPlain != null)
                        ? sql.getActiveIpBanServer(ipPlain, now, targetServer)
                        : null;
            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }

            if (serverBan != null || serverIpBan != null) {
                // You can either disconnect or redirect to fallback server.
                // Minimal: disconnect
                BanRow row = (serverBan != null) ? serverBan : serverIpBan;
                String msg = (serverBan != null)
                        ? buildPlayerBanKick(row, now)
                        : buildIpBanKick(row, now);

                ProxyServer.getInstance().getScheduler().runAsync(plugin, () ->
                        player.disconnect(new TextComponent(color(msg)))
                );
                return;
            }

            // Allowed -> connect manually (and bypass this event once)
            bypassNextConnect.add(uuid);
            ProxyServer.getInstance().getScheduler().runAsync(plugin, () ->
                    player.connect(e.getTarget())
            );
        });
    }

    private String buildPlayerBanKick(BanRow row, long nowMs) {
        String expires = (row.expiresAt == null) ? "Never" : TimeFormat.remaining(nowMs, row.expiresAt);
        boolean temp = (row.expiresAt == null);
        String key = temp ? "Punishments.messages.ban_join_perm" : "Punishments.messages.ban_join_temp";
        long now = System.currentTimeMillis();
        String expiresLeft = "never";
        String expiresDate = "never";
        if (row.expiresAt != null) {
            expiresLeft = temp ? TimeFormat.remaining(now, row.expiresAt) : "Never";
            expiresDate = temp ? TimeFormat.dateTime(row.expiresAt) : "Never";
        }
        String msg = HackerGuardianB.configuration.getString(key,
                "&cYou are banned.\n&7Reason: &f%reason%\n&7Expires: &f%expires%");
        msg = msg.replace("%reason%", row.reason)
                .replace("%expires%", expiresLeft)
                .replace("%expires_date%", expiresDate)
                .replace("%id%", String.valueOf(row.id));
        return msg;
    }

    private String buildIpBanKick(BanRow row, long nowMs) {
        String expires = (row.expiresAt == null) ? "Never" : TimeFormat.remaining(nowMs, row.expiresAt);
        return "&cYou are IP-banned.\n\n&7Reason: &f" + row.reason + "\n&7Expires: &f" + expires + "\n\n&7IP Ban ID: &f#" + row.id;
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}