package me.hackerguardian.bungee.utils;

import me.hackerguardian.Util.HmacSigner;
import me.hackerguardian.Util.LinkErrorCode;
import me.hackerguardian.Util.LinkErrorHandler;
import me.hackerguardian.Util.TicketPayload;
import me.hackerguardian.bungee.HackerGuardianB;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.event.EventHandler;

import java.util.Map;
import java.util.UUID;

public final class TicketIssuer implements Listener {
    private final LinkErrorHandler errorHandler = new LinkErrorHandler();
    private final HackerGuardianB plugin;
    private final BDatabase database;

    public TicketIssuer(HackerGuardianB plugin, BDatabase database) {
        this.plugin = plugin;
        this.database = database;
    }

    @EventHandler
    public void onServerConnected(ServerConnectedEvent e) {
        ProxiedPlayer player = e.getPlayer();
        if (player == null || e.getServer() == null) return;

        Configuration cfg = HackerGuardianB.getConfiguration();
        String secret = cfg.getString("Settings.shared_secret", "");
        long ttlMs = cfg.getLong("Settings.ticket_ttl_ms", 15000L);

        if (secret == null || secret.isBlank() || secret.startsWith("CHANGE_ME")) {
            errorHandler.kickpro(player, LinkErrorCode.HG_E_102_MISSING_SECRET,
                    Map.of("target", e.getServer().getInfo().getName()), null);
            return;
        }

        String ticketId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        long expires = now + ttlMs;

        String targetServer = e.getServer().getInfo().getName();
        String playerUuid = player.getUniqueId().toString();
        String playerName = player.getName();

        TicketPayload unsigned = new TicketPayload(
                ticketId, playerUuid, playerName, now, expires, targetServer, ""
        );
        String sigHex = HmacSigner.signHex(secret, unsigned.signingString());
        TicketPayload payload = new TicketPayload(
                ticketId, playerUuid, playerName, now, expires, targetServer, sigHex
        );

        // Do not block the proxy event loop on SQL I/O.
        plugin.getProxy().getScheduler().runAsync(plugin, () -> {
            try {
                database.insertTicket(payload);

                if (player.getServer() != null
                        && targetServer.equalsIgnoreCase(player.getServer().getInfo().getName())) {
                    player.getServer().sendData("hg:playerchannel", payload.encode());
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to issue secure-link ticket for " + playerName + ": " + ex.getMessage());
                errorHandler.kickpro(player, LinkErrorCode.HG_E_101_DB_FAILURE,
                        Map.of("ticket", payload.ticketId), ex);
            }
        });
    }
}
