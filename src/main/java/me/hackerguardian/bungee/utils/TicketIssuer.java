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
import net.md_5.bungee.api.plugin.Plugin;

import java.sql.*;
import java.util.Map;
import java.util.UUID;
public final class TicketIssuer implements Listener {
    private LinkErrorHandler errorHandler = new LinkErrorHandler();

    private final Plugin plugin;
    HackerGuardianB main = HackerGuardianB.getInstance();

    public TicketIssuer(Plugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onServerConnected(ServerConnectedEvent e) {
        ProxiedPlayer p = e.getPlayer();
        if (p == null || p.getServer() == null) return;

        Configuration cfg = HackerGuardianB.getConfiguration();

        String secret = cfg.getString("Settings.shared_secret");
        long ttlMs = cfg.getLong("Settings.ticket_ttl_ms", 15000);

        String ticketId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        long expires = now + ttlMs;

        String targetServer = e.getServer().getInfo().getName();
        String playerUuid = p.getUniqueId().toString();
        String playerName = p.getName();

        TicketPayload unsigned = new TicketPayload(ticketId, playerUuid, playerName, now, expires, targetServer, ""); // temp
        String sigHex = HmacSigner.signHex(secret, unsigned.signingString());

        TicketPayload payload = new TicketPayload(ticketId, playerUuid, playerName, now, expires, targetServer, sigHex);

        // Insert + send
        try {
            BMySQL db = new BMySQL();
            db.insertTicket(payload);
            e.getServer().sendData("hg:playerchannel", payload.encode());
        } catch (Exception ex) {
            errorHandler.kickpro(p, LinkErrorCode.HG_E_101_DB_FAILURE, Map.of("ticket", payload.ticketId), null);
        }
    }
}
