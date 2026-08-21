package me.hackerguardian.main.replay;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ReplayWorldSnapshotter {

    private final JavaPlugin plugin;
    private final ReplayStorage storage;

    private final boolean enabled;
    private final int chunkRadius;
    private final int maxChunks;
    private final boolean includePrebuffer;
    private final int intervalTicks;

    // replayId -> set of captured chunk keys
    private final ConcurrentHashMap<Long, Set<Long>> captured = new ConcurrentHashMap<>();

    public ReplayWorldSnapshotter(JavaPlugin plugin, ReplayStorage storage) {
        this.plugin = plugin;
        this.storage = storage;

        this.enabled = plugin.getConfig().getBoolean("Replays.sandbox.enabled", false);
        this.chunkRadius = Math.max(0, plugin.getConfig().getInt("Replays.sandbox.chunk_radius", 2));
        this.maxChunks = Math.max(1, plugin.getConfig().getInt("Replays.sandbox.max_chunks", 200));
        this.includePrebuffer = plugin.getConfig().getBoolean("Replays.sandbox.include_prebuffer", false);
        this.intervalTicks = Math.max(1, plugin.getConfig().getInt("Replays.sandbox.snapshot_interval_ticks", 20));
    }

    public boolean isEnabled() { return enabled; }

    // Call from ReplayManager when a replay is created
    public void onReplayCreated(long replayId) {
        captured.putIfAbsent(replayId, ConcurrentHashMap.newKeySet());
    }

    // Call from ReplayManager when replay ends
    public void onReplayFinished(long replayId) {
        captured.remove(replayId);
    }

    public void tickCapture(long replayId, Player target) {
        if (!enabled || target == null || !target.isOnline()) return;

        World w = target.getWorld();
        Location loc = target.getLocation();
        int pcx = loc.getBlockX() >> 4;
        int pcz = loc.getBlockZ() >> 4;

        // Height range to snapshot (v1: full build height)
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

                // Capture chunk on main thread (block reads)
                Chunk chunk = w.getChunkAt(cx, cz);

                try {
                    byte[] raw = ReplayChunkSnapshotCodec.encodeChunk(chunk, minY, maxY);

                    // Write async to DB
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            storage.upsertWorldChunk(replayId, w.getName(), cx, cz, raw);
                        } catch (Exception ignored) {}
                    });

                } catch (Exception ex) {
                    if (plugin.getConfig().getBoolean("debug")) ex.printStackTrace();
                }
            }
        }
    }
}

