package me.hackerguardian.main.replay;

import me.hackerguardian.main.replay.events.NearbySnapshotEvent;
import me.hackerguardian.main.replay.events.PlayerSnapshotEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ReplayManager {

    private final JavaPlugin plugin;
    private final ReplayStorage storage;

    private final boolean enabled;
    private final long prebufferMs;
    private final long postbufferMs;
    private final long chunkMs;
    private final int snapshotIntervalTicks;
    private final int maxTriggersPerHour;
    private final int formatVersion;
    private final String codec;

    // context recording config
    private final boolean contextEnabled;
    private final boolean contextRecordInPrebuffer; // if false => only active sessions get context
    private final int contextRadius;
    private final int contextMaxPlayers;
    private final boolean contextIncludeItems;
    private final int contextIntervalTicks;

    private final ConcurrentHashMap<UUID, ReplayBuffer> buffers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ReplaySession> activeSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Deque<Long>> triggerTimes = new ConcurrentHashMap<>();

    private long tickCounter = 0;

    // Recording the world

    private final ReplayWorldSnapshotter snapshotter;

    public ReplayManager(JavaPlugin plugin, DataSource ds) {
        this.plugin = plugin;

        String serverName = plugin.getConfig().getString("Settings.server_name", "default");
        this.storage = new ReplayStorage(ds, serverName);

        this.enabled = plugin.getConfig().getBoolean("Replays.enabled", true);
        this.prebufferMs = plugin.getConfig().getLong("Replays.prebuffer_seconds", 30) * 1000L;
        this.postbufferMs = plugin.getConfig().getLong("Replays.postbuffer_seconds", 20) * 1000L;
        this.chunkMs = plugin.getConfig().getLong("Replays.chunk_ms", 2000L);
        this.snapshotIntervalTicks = plugin.getConfig().getInt("Replays.snapshot_interval_ticks", 2);
        this.maxTriggersPerHour = plugin.getConfig().getInt("Replays.max_triggers_per_player_per_hour", 6);
        this.codec = plugin.getConfig().getString("Replays.codec", "gzip");
        this.formatVersion = plugin.getConfig().getInt("Replays.format_version", 1);

        // context config
        this.contextEnabled = plugin.getConfig().getBoolean("Replays.context.enabled", true);
        this.contextRecordInPrebuffer = plugin.getConfig().getBoolean("Replays.context.record_in_prebuffer", false);
        this.contextRadius = plugin.getConfig().getInt("Replays.context.radius", 32);
        this.contextMaxPlayers = plugin.getConfig().getInt("Replays.context.max_players", 6);
        this.contextIncludeItems = plugin.getConfig().getBoolean("Replays.context.include_items", true);
        this.contextIntervalTicks = Math.max(1, plugin.getConfig().getInt("Replays.context.interval_ticks", this.snapshotIntervalTicks));

        // World
        this.snapshotter = new ReplayWorldSnapshotter(plugin, storage);

        // ensure tables async
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try { storage.ensureTables(); } catch (Exception ignored) {}
        });

        // snapshot task
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!enabled) return;

            tickCounter++;
            long now = System.currentTimeMillis();

            boolean doContextThisTick = contextEnabled && (tickCounter % Math.max(1, (contextIntervalTicks / snapshotIntervalTicks)) == 0);

            for (Player p : Bukkit.getOnlinePlayers()) {
                // always record self snapshots (existing)
                record(p, now, PlayerSnapshotEvent.from(p));
                ReplaySession session = activeSessions.get(p.getUniqueId());
                if (session != null && snapshotter.isEnabled()) {
                    snapshotter.tickCapture(session.getReplayId(), p);
                }
                // optionally record nearby context snapshots
                if (doContextThisTick && shouldRecordContextFor(p)) {
                    NearbySnapshotEvent ctx = (NearbySnapshotEvent) buildNearbySnapshot(p);
                    record(p, now, ctx);
                }
            }
        }, snapshotIntervalTicks, snapshotIntervalTicks);
    }

    private boolean shouldRecordContextFor(Player p) {
        if (!contextEnabled) return false;

        // If record_in_prebuffer=false, only record context while the player is actively being recorded
        if (!contextRecordInPrebuffer) {
            return activeSessions.containsKey(p.getUniqueId());
        }
        return true;
    }

    private ReplayEvent buildNearbySnapshot(Player target) {
        List<NearbySnapshotEvent.Entry> entries =
                collectNearby(target, contextRadius, contextMaxPlayers, contextIncludeItems);

        if (entries.isEmpty()) return null; // <—
        return new NearbySnapshotEvent(target.getWorld().getName(), entries);
    }

    // This is the helper you referenced
    private List<NearbySnapshotEvent.Entry> collectNearby(Player target, int radius, int maxPlayers, boolean includeItems) {
        World w = target.getWorld();
        Location tLoc = target.getLocation();
        double r2 = radius * radius;

        return w.getPlayers().stream()
                .filter(p -> !p.getUniqueId().equals(target.getUniqueId()))
                .filter(p -> p.getLocation().distanceSquared(tLoc) <= r2)
                .sorted(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(tLoc)))
                .limit(maxPlayers)
                .map(p -> {
                    String hand = "AIR";
                    if (includeItems) {
                        ItemStack is = p.getInventory().getItemInMainHand();
                        if (is != null && is.getType() != Material.AIR) {
                            hand = is.getType().name();
                        }
                    }
                    Location l = p.getLocation();
                    return new NearbySnapshotEvent.Entry(
                            p.getUniqueId(),
                            p.getName(),
                            l.getX(), l.getY(), l.getZ(),
                            l.getYaw(), l.getPitch(),
                            hand
                    );
                })
                .toList();
    }

    public boolean isEnabled() { return enabled; }

    public void record(Player p, long nowMs, ReplayEvent ev) {
        if (!enabled || p == null || !p.isOnline()) return;
        byte[] bytes = ReplayCodec.encodeEvent(ev);
        if (bytes == null) return;
        buffers.computeIfAbsent(p.getUniqueId(), id -> new ReplayBuffer(prebufferMs))
                .add(nowMs, bytes);

        ReplaySession session = activeSessions.get(p.getUniqueId());
        if (session != null) {
            try { session.append(nowMs, bytes); } catch (Exception ignored) {}
        }
    }

    public void triggerManual(Player target, Player staff, String reason) {
        String meta = "staff=" + (staff != null ? staff.getName() : "console") + ";reason=" + safe(reason);
        trigger(target, ReplayTriggerType.MANUAL, meta, null);
    }

    public void triggerFromModeration(Player target, String action, String reason) {
        String meta = "action=" + safe(action) + ";reason=" + safe(reason);
        trigger(target, ReplayTriggerType.MODERATION, meta, null);
    }

    public void triggerFromAi(Player target, double score, String modelName) {
        String meta = "model=" + safe(modelName);
        trigger(target, ReplayTriggerType.AI, meta, score);
    }

    public void trigger(Player target, ReplayTriggerType type, String meta, Double aiScore) {
        if (!enabled || target == null || !target.isOnline()) return;

        UUID id = target.getUniqueId();
        if (activeSessions.containsKey(id)) return; // already recording

        if (!rateLimitOk(id)) return;

        long now = System.currentTimeMillis();
        ReplayBuffer buffer = buffers.computeIfAbsent(id, k -> new ReplayBuffer(prebufferMs));
        List<ReplayBuffer.Entry> pre = buffer.snapshot(now);

        // Create replay header async, then start session
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                long replayId = storage.createReplay(
                        id.toString(),
                        target.getName(),
                        now,
                        type,
                        meta,
                        aiScore,
                        formatVersion,
                        codec
                );

                ReplaySession session = new ReplaySession(plugin, replayId, storage, chunkMs, now);
                snapshotter.onReplayCreated(replayId);

                // flush prebuffer into the session
                for (ReplayBuffer.Entry e : pre) {
                    session.append(e.tsMs, e.eventBytes);
                }
                activeSessions.put(id, session);

                // stop later
                Bukkit.getScheduler().runTaskLater(plugin, () -> stop(target), msToTicks(postbufferMs));

            } catch (Exception ignored) {}
        });
    }

    public void stop(Player target) {
        if (target == null) return;
        UUID id = target.getUniqueId();
        ReplaySession session = activeSessions.remove(id);
        if (session == null) return;
        snapshotter.onReplayFinished(session.getReplayId());
        long end = System.currentTimeMillis();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> session.close(end));
    }

    public void cleanupPlayer(UUID id) {
        activeSessions.remove(id);
        buffers.remove(id);
        triggerTimes.remove(id);
    }

    private boolean rateLimitOk(UUID id) {
        long now = System.currentTimeMillis();
        long cutoff = now - 3600_000L;

        Deque<Long> dq = triggerTimes.computeIfAbsent(id, k -> new ArrayDeque<>());
        synchronized (dq) {
            while (!dq.isEmpty() && dq.peekFirst() < cutoff) dq.removeFirst();
            if (dq.size() >= maxTriggersPerHour) return false;
            dq.addLast(now);
            return true;
        }
    }

    private long msToTicks(long ms) {
        return Math.max(1L, (ms + 49L) / 50L);
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace("\n", " ").replace("\r", " ");
    }

    public ReplayStorage getStorage() {
        return storage;
    }


}
