package me.hackerguardian.velocity.utils;

import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import me.hackerguardian.Util.HmacSigner;
import me.hackerguardian.Util.TicketPayload;
import me.hackerguardian.velocity.HackerGuardianV;
import me.hackerguardian.velocity.VDatabase;
import net.kyori.adventure.text.Component;

import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class VelocityTicketIssuer {

    private final HackerGuardianV plugin;
    private final ProxyServer proxy;
    private final VDatabase database;
    private final Logger logger;
    private final String secret;
    private final long ttlMs;

    public VelocityTicketIssuer(HackerGuardianV plugin,
                                ProxyServer proxy,
                                VDatabase database,
                                String secret,
                                long ttlMs) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.database = database;
        this.secret = secret == null ? "" : secret;
        this.ttlMs = ttlMs;
        this.logger = plugin.getLogger();
    }

    public void handleServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        RegisteredServer server = event.getServer();
        if (player == null || server == null) return;

        if (secret.isBlank() || secret.startsWith("CHANGE_ME")) {
            logger.severe("HG Secure Link is enabled but Settings.shared_secret is not configured.");
            player.disconnect(Component.text("Proxy link system is not configured."));
            return;
        }

        String ticketId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        long expires = now + ttlMs;

        String targetServer = server.getServerInfo().getName();
        String playerUuid = player.getUniqueId().toString();
        String playerName = player.getUsername();

        TicketPayload unsigned = new TicketPayload(
                ticketId, playerUuid, playerName, now, expires, targetServer, ""
        );
        String sigHex = HmacSigner.signHex(secret, unsigned.signingString());
        TicketPayload payload = new TicketPayload(
                ticketId, playerUuid, playerName, now, expires, targetServer, sigHex
        );

        proxy.getScheduler().buildTask(plugin, () -> {
            try {
                database.insertTicket(payload);
                server.sendPluginMessage(HackerGuardianV.CH, payload.encode());
            } catch (Exception ex) {
                logger.log(Level.SEVERE, "Secure-link ticket issue failed for " + playerName, ex);
                player.disconnect(Component.text("Proxy link system error."));
            }
        }).schedule();
    }
}
