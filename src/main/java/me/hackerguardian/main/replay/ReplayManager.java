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
import org.bukkit.scheduler.BukkitTask;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ReplayManager {

    private final JavaPlugin plugin;
    private final ReplayStorage storage;
    private final ExecutorService ioExecutor;
    private final BukkitTask snapshotTask;

    private final boolean enabled;
    private final long prebufferMs;
    private final long postbufferMs;
    private final long chunkMs;
    private final int snapshotIntervalTicks;
    private final int maxTriggersPerHour;
    private final int formatVersion;
    private final String codec;

    private final boolean contextEnabled;
    private final boolean contextRecordInPrebuffer;
    private final int contextRadius;
    private final int contextMaxPlayers;
    private final boolean contextIncludeItems;
    private final int contextEverySnapshots;

    private final ConcurrentHashMap<UUID, ReplayBuffer> buffers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ReplaySession> activeSessions = new ConcurrentHashMap<>();
    private final Set<UUID> pendingSessions = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, Deque<Long>> triggerTimes = new ConcurrentHashMap<>();

    private long tickCounter = 0;
    private volatile boolean shuttingDown = false;
    private final ReplayWorldSnapshotter snapshotter;

    public ReplayManager(JavaPlugin plugin, DataSource ds) {
        this.plugin = plugin;

        AtomicInteger threadNumber = new AtomicInteger();
        int ioThreads = Math.max(1, plugin.getConfig().getInt("Replays.io_threads", 2));
        this.ioExecutor = Executors.newFixedThreadPool(ioThreads, runnable -> {
            Thread thread = new Thread(runnable, "HackerGuardian-ReplayIO-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });

        String serverName = plugin.getConfig().getString("Settings.server_name", "default");
        this.storage = new ReplayStorage(ds, serverName);

        this.enabled = plugin.getConfig().getBoolean("Replays.enabled", true);
        this.prebufferMs = plugin.getConfig().getLong("Replays.prebuffer_seconds", 30) * 1000L;
        this.postbufferMs = plugin.getConfig().getLong("Replays.postbuffer_seconds", 20) * 1000L;
        this.chunkMs = plugin.getConfig().getLong("Replays.chunk_ms", 2000L);
        this.snapshotIntervalTicks = Math.max(1, plugin.getConfig().getInt("Replays.snapshot_interval_ticks", 2));
        this.maxTriggersPerHour = plugin.getConfig().getInt("Replays.max_triggers_per_player_per_hour", 6);
        this.codec = plugin.getConfig().getString("Replays.codec", "gzip");
        this.formatVersion = plugin.getConfig().getInt("Replays.format_version", 1);

        this.contextEnabled = plugin.getConfig().getBoolean("Replays.context.enabled", true);
        this.contextRecordInPrebuffer = plugin.getConfig().getBoolean("Replays.context.record_in_prebuffer", false);
        this.contextRadius = plugin.getConfig().getInt("Replays.context.radius", 32);
        this.contextMaxPlayers = plugin.getConfig().getInt("Replays.context.max_players", 6);
        this.contextIncludeItems = plugin.getConfig().getBoolean("Replays.context.include_items", true);
        int contextIntervalTicks = Math.max(1,
                plugin.getConfig().getInt("Replays.context.interval_ticks", this.snapshotIntervalTicks));
        this.contextEverySnapshots = Math.max(1,
                (int) Math.ceil(contextIntervalTicks / (double) this.snapshotIntervalTicks));

        this.snapshotter = new ReplayWorldSnapshotter(plugin, storage, ioExecutor);

        try {
            storage.ensureTables();
        } catch (Exception e) {
            ioExecutor.shutdownNow();
            throw new IllegalStateException("Failed to initialize replay database tables", e);
        }

        this.snapshotTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!enabled) return;

            tickCounter++;
            long now = System.currentTimeMillis();
            boolean doContextThisTick = contextEnabled && (tickCounter % contextEverySnapshots == 0);

            for (Player p : Bukkit.getOnlinePlayers()) {
                record(p, now, PlayerSnapshotEvent.from(p));

                ReplaySession session = activeSessions.get(p.getUniqueId());
                if (session != null && snapshotter.isEnabled()) {
                    snapshotter.tickCapture(session.getReplayId(), p);
                }

                if (doContextThisTick && shouldRecordContextFor(p)) {
                    record(p, now, buildNearbySnapshot(p));
                }
            }
        }, snapshotIntervalTicks, snapshotIntervalTicks);
    }

    private boolean shouldRecordContextFor(Player p) {
        if (!contextEnabled) return false;
        return contextRecordInPrebuffer || activeSessions.containsKey(p.getUniqueId());
    }

    private ReplayEvent buildNearbySnapshot(Player target) {
        List<NearbySnapshotEvent.Entry> entries =
                collectNearby(target, contextRadius, contextMaxPlayers, contextIncludeItems);
        return entries.isEmpty() ? null : new NearbySnapshotEvent(target.getWorld().getName(), entries);
    }

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
                        if (is != null && is.getType() != Material.AIR) hand = is.getType().name();
                    }
                    Location l = p.getLocation();
                    return new NearbySnapshotEvent.Entry(
                            p.getUniqueId(), p.getName(),
                            l.getX(), l.getY(), l.getZ(),
                            l.getYaw(), l.getPitch(), hand
                    );
                })
                .toList();
    }

    public boolean isEnabled() { return enabled; }

    public void record(Player p, long nowMs, ReplayEvent ev) {
        if (!enabled || p == null || !p.isOnline() || ev == null) return;

        byte[] bytes = ReplayCodec.encodeEvent(ev);
        if (bytes == null) return;

        buffers.computeIfAbsent(p.getUniqueId(), id -> new ReplayBuffer(prebufferMs)).add(nowMs, bytes);

        ReplaySession session = activeSessions.get(p.getUniqueId());
        if (session != null) {
            try {
                session.append(nowMs, bytes);
            } catch (Exception e) {
                plugin.getLogger().warning("[Replay] Failed to append event for replay " +
                        session.getReplayId() + ": " + e.getMessage());
            }
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
        if (!enabled || shuttingDown || target == null || !target.isOnline()) return;

        UUID id = target.getUniqueId();
        if (activeSessions.containsKey(id) || !pendingSessions.add(id)) return;
        if (!rateLimitOk(id)) {
            pendingSessions.remove(id);
            return;
        }

        long now = System.currentTimeMillis();
        String playerName = target.getName();
        ReplayBuffer buffer = buffers.computeIfAbsent(id, k -> new ReplayBuffer(prebufferMs));
        List<ReplayBuffer.Entry> pre = buffer.snapshot(now);
        long streamStartMs = pre.isEmpty() ? now : pre.get(0).tsMs;

        ioExecutor.execute(() -> {
            try {
                long replayId = storage.createReplay(
                        id.toString(), playerName, now, type, meta, aiScore, formatVersion, codec
                );

                // started_at on the replay row is the trigger time. The stream itself
                // begins at the first prebuffer event so event deltas remain accurate.
                ReplaySession session = new ReplaySession(plugin, replayId, storage, chunkMs, streamStartMs, ioExecutor);
                for (ReplayBuffer.Entry entry : pre) session.append(entry.tsMs, entry.eventBytes);

                // DB creation happens off-thread. Preserve events that arrived after the
                // trigger but before the session became active so the recording has no gap.
                for (ReplayBuffer.Entry entry : buffer.snapshot(System.currentTimeMillis())) {
                    if (entry.tsMs > now) session.append(entry.tsMs, entry.eventBytes);
                }

                if (shuttingDown) {
                    session.close(System.currentTimeMillis());
                    return;
                }

                ReplaySession existing = activeSessions.putIfAbsent(id, session);
                if (existing != null) {
                    session.close(System.currentTimeMillis());
                    return;
                }

                if (shuttingDown && activeSessions.remove(id, session)) {
                    session.close(System.currentTimeMillis());
                    return;
                }

                snapshotter.onReplayCreated(replayId);
                Bukkit.getScheduler().runTaskLater(plugin, () -> stop(id), msToTicks(postbufferMs));
            } catch (Exception e) {
                plugin.getLogger().warning("[Replay] Failed to create replay for " + playerName + ": " + e.getMessage());
                if (plugin.getConfig().getBoolean("debug")) e.printStackTrace();
            } finally {
                pendingSessions.remove(id);
            }
        });
    }

    public void stop(Player target) {
        if (target != null) stop(target.getUniqueId());
    }

    public void stop(UUID id) {
        closeSession(id);
    }

    private CompletableFuture<Void> closeSession(UUID id) {
        if (id == null) return CompletableFuture.completedFuture(null);
        ReplaySession session = activeSessions.remove(id);
        if (session == null) return CompletableFuture.completedFuture(null);

        snapshotter.onReplayFinished(session.getReplayId());
        return session.close(System.currentTimeMillis());
    }

    public void cleanupPlayer(UUID id) {
        closeSession(id);
        buffers.remove(id);
        triggerTimes.remove(id);
    }

    /** Flush active evidence and stop replay-owned workers before the datasource is closed. */
    public void shutdown() {
        shuttingDown = true;
        if (snapshotTask != null) snapshotTask.cancel();

        List<CompletableFuture<Void>> closing = new ArrayList<>();
        for (UUID id : new ArrayList<>(activeSessions.keySet())) closing.add(closeSession(id));
        buffers.clear();
        pendingSessions.clear();
        triggerTimes.clear();

        try {
            CompletableFuture.allOf(closing.toArray(new CompletableFuture[0])).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().warning("[Replay] Timed out or failed while finalizing active replays: " + e.getMessage());
        }

        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("[Replay] Timed out waiting for replay I/O to finish; forcing shutdown.");
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ioExecutor.shutdownNow();
        }
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
