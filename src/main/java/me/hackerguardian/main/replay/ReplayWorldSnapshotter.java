package me.hackerguardian.main.replay;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

public final class ReplayWorldSnapshotter {

    private final JavaPlugin plugin;
    private final ReplayStorage storage;
    private final Executor ioExecutor;

    private final boolean enabled;
    private final int chunkRadius;
    private final int maxChunks;
    private final int intervalTicks;

    // replayId -> set of captured chunk keys
    private final ConcurrentHashMap<Long, Set<Long>> captured = new ConcurrentHashMap<>();

    public ReplayWorldSnapshotter(JavaPlugin plugin, ReplayStorage storage, Executor ioExecutor) {
        this.plugin = plugin;
        this.storage = storage;
        this.ioExecutor = ioExecutor;

        this.enabled = plugin.getConfig().getBoolean("Replays.sandbox.enabled", false);
        this.chunkRadius = Math.max(0, plugin.getConfig().getInt("Replays.sandbox.chunk_radius", 2));
        this.maxChunks = Math.max(1, plugin.getConfig().getInt("Replays.sandbox.max_chunks", 200));
        this.intervalTicks = Math.max(1, plugin.getConfig().getInt("Replays.sandbox.snapshot_interval_ticks", 20));
    }

    public boolean isEnabled() { return enabled; }

    public void onReplayCreated(long replayId) {
        captured.putIfAbsent(replayId, ConcurrentHashMap.newKeySet());
    }

    public void onReplayFinished(long replayId) {
        captured.remove(replayId);
    }

    public void tickCapture(long replayId, Player target) {
        if (!enabled || target == null || !target.isOnline()) return;

        World w = target.getWorld();
        Location loc = target.getLocation();
        int pcx = loc.getBlockX() >> 4;
        int pcz = loc.getBlockZ() >> 4;
        int minY = w.getMinHeight();
        int maxY = w.getMaxHeight() - 1;

        Set<Long> set = captured.computeIfAbsent(replayId, k -> ConcurrentHashMap.newKeySet());

        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                int cx = pcx + dx;
                int cz = pcz + dz;

                long key = (((long) cx) << 32) ^ (cz & 0xffffffffL);
                if (set.contains(key)) continue;
                if (set.size() >= maxChunks) return;

                set.add(key);
                Chunk chunk = w.getChunkAt(cx, cz);

                try {
                    byte[] raw = ReplayChunkSnapshotCodec.encodeChunk(chunk, minY, maxY);
                    String worldName = w.getName();
                    ioExecutor.execute(() -> {
                        try {
                            storage.upsertWorldChunk(replayId, worldName, cx, cz, raw);
                        } catch (Exception e) {
                            plugin.getLogger().warning("[Replay] Failed to store world snapshot for replay "
                                    + replayId + " chunk " + cx + "," + cz + ": " + e.getMessage());
                        }
                    });
                } catch (Exception ex) {
                    plugin.getLogger().warning("[Replay] Failed to capture chunk " + cx + "," + cz
                            + " for replay " + replayId + ": " + ex.getMessage());
                    if (plugin.getConfig().getBoolean("debug")) ex.printStackTrace();
                }
            }
        }
    }
}
