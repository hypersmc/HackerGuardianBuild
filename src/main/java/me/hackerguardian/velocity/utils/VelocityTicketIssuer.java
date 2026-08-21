package me.hackerguardian.velocity.utils;

import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.Player;
import me.hackerguardian.Util.HmacSigner;
import me.hackerguardian.Util.TicketPayload;
import me.hackerguardian.velocity.HackerGuardianV;
import net.kyori.adventure.text.Component;

import java.sql.*;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class VelocityTicketIssuer {

    private final ProxyServer proxy;
    private final Logger logger = Logger.getLogger("HGVelocity_Link");;

    private final String secret = (String) HackerGuardianV.settings.getOrDefault("shared_secret", "");
    private final long ttlMs = (long) HackerGuardianV.settings.getOrDefault("ttlms", 15000);


    private final String mysqlUrl = (String) HackerGuardianV.sql.get("");
    private final String mysqlUser = (String) HackerGuardianV.sql.get("");
    private final String mysqlPass = (String) HackerGuardianV.sql.get("");

    public VelocityTicketIssuer(ProxyServer proxy) {
        this.proxy = proxy;
    }

    public void handleServerConnected(ServerConnectedEvent e) {
        Player p = e.getPlayer();
        ServerConnection sc = (ServerConnection) e.getServer();
        if (p == null || sc == null) return;

        String ticketId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        long expires = now + ttlMs;

        String targetServer = sc.getServerInfo().getName();
        String playerUuid = p.getUniqueId().toString();
        String playerName = p.getUsername();

        TicketPayload unsigned = new TicketPayload(ticketId, playerUuid, playerName, now, expires, targetServer, "");
        String sigHex = HmacSigner.signHex(secret, unsigned.signingString());

        TicketPayload payload = new TicketPayload(ticketId, playerUuid, playerName, now, expires, targetServer, sigHex);

        proxy.getScheduler().buildTask(this, () -> {
            try {
                insertTicket(payload);
                sc.sendPluginMessage(HackerGuardianV.CH, payload.encode());
            } catch (Exception ex) {
                logger.log(Level.SEVERE, "Ticket issue failed: {}", ex.getMessage());
                p.disconnect(Component.text("Proxy link system error."));
            }
        }).schedule();
    }

    private void insertTicket(TicketPayload payload) throws SQLException {
        String sql = "INSERT INTO hg_player_tickets(ticket_id, player_uuid, player_name, issued_at, expires_at, used_at, target_server) " +
                "VALUES(?,?,?,?,?,NULL,?)";

        try (Connection c = DriverManager.getConnection(mysqlUrl, mysqlUser, mysqlPass);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, payload.ticketId);
            ps.setString(2, payload.playerUuid);
            ps.setString(3, payload.playerName);
            ps.setLong(4, payload.issuedAt);
            ps.setLong(5, payload.expiresAt);
            ps.setString(6, payload.targetServer);
            ps.executeUpdate();
        }
    }
}
