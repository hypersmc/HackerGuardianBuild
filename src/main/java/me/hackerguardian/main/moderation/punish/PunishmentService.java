package me.hackerguardian.main.moderation.punish;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class PunishmentService {

    private final JavaPlugin plugin;
    private final PunishmentRepository repo;

    public PunishmentService(JavaPlugin plugin, PunishmentRepository repo) {
        this.plugin = plugin;
        this.repo = repo;
    }

    // ---------- CREATE ----------

    public CompletableFuture<Long> ban(Player actor, String targetUuid, String targetName,
                                       String reason, Long durationMsOrNull,
                                       PunishScope scope, String serverName) {
        long now = System.currentTimeMillis();
        Long expiresAt = (durationMsOrNull == null) ? null : (now + durationMsOrNull);

        return supplyAsync(() -> {
            long punishId = repo.createPunishment(
                    PunishmentType.BAN,
                    targetUuid, targetName,
                    actor.getUniqueId().toString(), actor.getName(),
                    reason,
                    now, expiresAt,
                    scope, serverName
            );

            repo.logAction(
                    ModerationActionType.BAN,
                    targetUuid, targetName, null,
                    actor.getUniqueId().toString(), actor.getName(),
                    reason, now, expiresAt,
                    punishId,
                    scope, serverName
            );

            return punishId;
        });
    }

    public CompletableFuture<Long> mute(Player actor, String targetUuid,
                                        String targetName, String reason,
                                        Long durationMsOrNull,
                                        PunishScope scope, String serverName) {
        long now = System.currentTimeMillis();
        Long expiresAt = (durationMsOrNull == null) ? null : (now + durationMsOrNull);

        return supplyAsync(() -> {
            long punishId = repo.createPunishment(
                    PunishmentType.MUTE,
                    targetUuid, targetName,
                    actor.getUniqueId().toString(), actor.getName(),
                    reason,
                    now, expiresAt,
                    scope, serverName
            );

            repo.logAction(
                    ModerationActionType.MUTE,
                    targetUuid, targetName, null,
                    actor.getUniqueId().toString(), actor.getName(),
                    reason,
                    now, expiresAt,
                    punishId,
                    scope, serverName
            );

            return punishId;
        });
    }

    public CompletableFuture<Long> banIp(Player actor, String ip,
                                         String reason, Long durationMsOrNull,
                                         PunishScope scope, String serverName) {
        long now = System.currentTimeMillis();
        Long expiresAt = (durationMsOrNull == null) ? null : (now + durationMsOrNull);

        return supplyAsync(() -> {
            long ipBanId = repo.createIpBan(
                    ip,
                    actor.getUniqueId().toString(), actor.getName(),
                    reason, now, expiresAt, scope, serverName
            );

            repo.logAction(
                    ModerationActionType.IP_BAN,
                    null, null, ip,
                    actor.getUniqueId().toString(), actor.getName(),
                    reason,
                    now, expiresAt,
                    ipBanId,
                    scope, serverName
            );

            return ipBanId;
        });
    }

    // ---------- REMOVE ----------

    public CompletableFuture<Boolean> unban(Player actor, String targetUuid,
                                            String targetNameOrNull, String reasonOrNull) {
        long now = System.currentTimeMillis();
        return supplyAsync(() -> {
            boolean ok = repo.deactivateActivePunishments(PunishmentType.BAN, targetUuid, now);
            if (ok) {
                repo.logAction(
                        ModerationActionType.UNBAN,
                        targetUuid, targetNameOrNull, null,
                        actor.getUniqueId().toString(), actor.getName(),
                        reasonOrNull,
                        now, null,
                        null,
                        null, null
                );
            }
            return ok;
        });
    }

    public CompletableFuture<Boolean> unmute(Player actor, String targetUuid,
                                             String targetNameOrNull, String reasonOrNull) {
        long now = System.currentTimeMillis();
        return supplyAsync(() -> {
            boolean ok = repo.deactivateActivePunishments(PunishmentType.MUTE, targetUuid, now);
            if (ok) {
                repo.logAction(
                        ModerationActionType.UNMUTE,
                        targetUuid, targetNameOrNull, null,
                        actor.getUniqueId().toString(), actor.getName(),
                        reasonOrNull,
                        now, null,
                        null,
                        null, null
                );
            }
            return ok;
        });
    }

    public CompletableFuture<Boolean> unbanIp(Player actor, String ip,
                                              String reasonOrNull) {
        long now = System.currentTimeMillis();
        return supplyAsync(() -> {
            boolean ok = repo.deactivateIpBan(ip);
            if (ok) {
                repo.logAction(
                        ModerationActionType.IP_UNBAN,
                        null, null, ip,
                        actor.getUniqueId().toString(), actor.getName(),
                        reasonOrNull,
                        now, null,
                        null,
                        null, null
                );
            }
            return ok;
        });
    }

    // ---------- KICK (log only) ----------

    public CompletableFuture<Long> logKick(Player actor, String targetUuid,
                                           String targetName, String reason,
                                           PunishScope scope, String serverName) {
        long now = System.currentTimeMillis();
        return supplyAsync(() -> repo.logAction(
                ModerationActionType.KICK,
                targetUuid, targetName, null,
                actor.getUniqueId().toString(), actor.getName(),
                reason,
                now, null,
                null
                , scope, serverName
        ));
    }

    // ---------- CHECK ----------

    public CompletableFuture<Optional<PunishmentRow>> getActiveBan(String targetUuid) {
        long now = System.currentTimeMillis();
        boolean behindProxy = plugin.getConfig().getBoolean("Settings.behind_proxy", false);
        String serverName = plugin.getConfig().getString("Settings.server_name", "default");

        return supplyAsync(() -> repo.getActivePunishment(
                PunishmentType.BAN, targetUuid, now, behindProxy, serverName
        ));
    }

    public CompletableFuture<Optional<PunishmentRow>> getActiveMute(String targetUuid) {
        long now = System.currentTimeMillis();
        boolean behindProxy = plugin.getConfig().getBoolean("Settings.behind_proxy", false);
        String serverName = plugin.getConfig().getString("Settings.server_name", "default");

        return supplyAsync(() -> repo.getActivePunishment(
                PunishmentType.MUTE, targetUuid, now, behindProxy, serverName
        ));
    }

    public CompletableFuture<Optional<PunishmentRepository.IpBanRow>> getActiveIpBan(String ip) {
        long now = System.currentTimeMillis();
        boolean behindProxy = plugin.getConfig().getBoolean("Settings.behind_proxy", false);
        String serverName = plugin.getConfig().getString("Settings.server_name", "default");

        return supplyAsync(() -> repo.getActiveIpBan(
                ip, now, behindProxy, serverName
        ));
    }

    /**
     * History listing: usually we want ALL scopes for staff/history/panel usage.
     */
    public CompletableFuture<List<PunishmentRow>> listPunishments(String targetUuid, int limit) {
        return supplyAsync(() -> repo.listPunishmentsForTarget(targetUuid, limit));
    }

    public CompletableFuture<Integer> cleanupExpired() {
        long now = System.currentTimeMillis();
        return supplyAsync(() -> repo.cleanupExpired(now));
    }

    // ---------- async helper ----------

    private <T> CompletableFuture<T> supplyAsync(SqlSupplier<T> s) {
        CompletableFuture<T> f = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try { f.complete(s.get()); }
            catch (Exception ex) { f.completeExceptionally(ex); }
        });
        return f;
    }

    @FunctionalInterface private interface SqlSupplier<T> { T get() throws Exception; }
}