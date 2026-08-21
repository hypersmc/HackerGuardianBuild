package me.hackerguardian.main.hglink;
import me.hackerguardian.Util.HmacSigner;
import me.hackerguardian.Util.LinkErrorCode;
import me.hackerguardian.Util.LinkErrorHandler;
import me.hackerguardian.Util.TicketPayload;
import me.hackerguardian.main.DatabaseManager;
import me.hackerguardian.main.HackerGuardian;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class LinkVerifier implements Listener, PluginMessageListener {

    private enum State { PENDING, VERIFYING }

    private static final class PendingEntry {
        volatile State state = State.PENDING;
        volatile long deadlineMs;
        BukkitTask kickTask;
        PendingEntry(long deadlineMs) { this.deadlineMs = deadlineMs; }
    }

    private final JavaPlugin plugin;
    private final Logger logger = Logger.getLogger("HGLinkVerifier");
    private final LinkErrorHandler errorHandler;

    private final ConcurrentHashMap<UUID, Long> recentlyVerified = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> preVerifying = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, PendingEntry> pending = new ConcurrentHashMap<>();

    public LinkVerifier(JavaPlugin plugin, LinkErrorHandler errorHandler) {
        this.plugin = plugin;
        this.errorHandler = errorHandler;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();

        Long okUntil = recentlyVerified.get(id);
        if (okUntil != null) {
            if (now <= okUntil) {
                recentlyVerified.remove(id);
                logger.info("JOIN: " + p.getName() + " verified pre-join; skipping pending.");
                return;
            }
            recentlyVerified.remove(id);
        }

        boolean shouldStartAsVerifying = false;
        Long verifyUntil = preVerifying.get(id);
        if (verifyUntil != null) {
            if (now <= verifyUntil) shouldStartAsVerifying = true;
            else preVerifying.remove(id);
        }

        long verifyWindowMs = plugin.getConfig().getLong("Settings.verify_window_ms", 2000L);
        long graceMs = plugin.getConfig().getLong("Settings.db_grace_ms", 3000L);

        long deadline = now + (shouldStartAsVerifying ? graceMs : verifyWindowMs);
        PendingEntry entry = new PendingEntry(deadline);
        entry.state = shouldStartAsVerifying ? State.VERIFYING : State.PENDING;
        pending.put(id, entry);

        logger.info("JOIN pending set for " + p.getName() + " uuid=" + id + " state=" + entry.state);
        scheduleKick(p, entry, shouldStartAsVerifying ? graceMs : verifyWindowMs);
    }

    private void scheduleKick(Player p, PendingEntry entry, long delayMs) {
        long ticks = msToTicks(delayMs);
        if (entry.kickTask != null) entry.kickTask.cancel();

        entry.kickTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            UUID id = p.getUniqueId();
            PendingEntry current = pending.get(id);
            if (current == null) return;
            if (!p.isOnline()) { cleanup(id); return; }

            long now = System.currentTimeMillis();
            if (now <= current.deadlineMs) {
                scheduleKick(p, current, current.deadlineMs - now);
                return;
            }

            pending.remove(id);
            preVerifying.remove(id);
            recentlyVerified.remove(id);

            if (current.state == State.VERIFYING) {
                errorHandler.kickpap(p, LinkErrorCode.HG_E_203_TIMEOUT, Map.of("phase", "db_grace_timeout"), null);
            } else {
                errorHandler.kickpap(p, LinkErrorCode.HG_E_201_NO_TICKET, Map.of("phase", "join_timeout"), null);
            }
        }, ticks);
    }

    private long msToTicks(long ms) {
        return Math.max(1L, (ms + 49L) / 50L);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!"hg:playerchannel".equalsIgnoreCase(channel)) return;

        logger.info("PM received for " + player.getName() + " uuid=" + player.getUniqueId() + " bytes=" + message.length);

        final TicketPayload payload;
        try {
            payload = TicketPayload.decode(message);
        } catch (IOException ex) {
            failAndKick(player, LinkErrorCode.HG_E_401_PAYLOAD_DECODE_FAIL, Map.of("bytes", message.length), ex);
            return;
        }

        markVerifying(player.getUniqueId());

        String expectedUuid = player.getUniqueId().toString();
        if (!expectedUuid.equalsIgnoreCase(payload.playerUuid)) {
            failAndKick(player, LinkErrorCode.HG_E_302_UUID_MISMATCH, Map.of("payload_uuid", payload.playerUuid), null);
            return;
        }

        long now = System.currentTimeMillis();
        if (now > payload.expiresAt) {
            failAndKick(player, LinkErrorCode.HG_E_202_TICKET_EXPIRED, Map.of("ticket", payload.ticketId), null);
            return;
        }

        String secret = plugin.getConfig().getString("Settings.shared_secret", "");
        if (secret.isEmpty()) {
            failAndKick(player, LinkErrorCode.HG_E_102_MISSING_SECRET, Map.of("target", payload.targetServer), null);
            return;
        }

        if (!HmacSigner.verifyHex(secret, payload.signingString(), payload.sigHex)) {
            failAndKick(player, LinkErrorCode.HG_E_301_SIGNATURE_INVALID, Map.of("target", payload.targetServer), null);
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok;
            try {
                DatabaseManager database = HackerGuardian.getInstance().getDatabase();
                ok = database != null && database.consumeTicket(payload, now);
            } catch (Exception ex) {
                ok = false;
            }

            boolean finalOk = ok;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) { cleanup(player.getUniqueId()); return; }

                if (finalOk) {
                    long okWindowMs = plugin.getConfig().getLong("Settings.verified_cache_ms", 30000L);
                    recentlyVerified.put(player.getUniqueId(), System.currentTimeMillis() + okWindowMs);
                    markVerified(player.getUniqueId());
                    logger.info("Link verified for " + player.getName());
                } else {
                    failAndKick(player, LinkErrorCode.HG_E_303_REPLAY_OR_USED, Map.of("ticket", payload.ticketId), null);
                }
            });
        });
    }

    private void markVerifying(UUID playerId) {
        long now = System.currentTimeMillis();
        long graceMs = plugin.getConfig().getLong("Settings.db_grace_ms", 3000L);
        preVerifying.put(playerId, now + graceMs);

        PendingEntry entry = pending.get(playerId);
        if (entry == null) return;

        entry.state = State.VERIFYING;
        entry.deadlineMs = now + graceMs;

        Player p = Bukkit.getPlayer(playerId);
        if (p != null && p.isOnline()) scheduleKick(p, entry, graceMs);
    }

    private void markVerified(UUID playerId) {
        preVerifying.remove(playerId);
        PendingEntry entry = pending.remove(playerId);
        if (entry != null && entry.kickTask != null) entry.kickTask.cancel();
    }

    private void failAndKick(Player player, LinkErrorCode code, Map<String, Object> ctx, Throwable t) {
        UUID id = player.getUniqueId();
        markVerified(id);
        recentlyVerified.remove(id);
        preVerifying.remove(id);
        errorHandler.kickpap(player, code, ctx, t);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) { cleanup(e.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKick(PlayerKickEvent e) { cleanup(e.getPlayer().getUniqueId()); }

    private void cleanup(UUID playerId) {
        PendingEntry entry = pending.remove(playerId);
        if (entry != null && entry.kickTask != null) entry.kickTask.cancel();
        recentlyVerified.remove(playerId);
        preVerifying.remove(playerId);
        logger.info("Cleaned link state for " + playerId);
    }
}
