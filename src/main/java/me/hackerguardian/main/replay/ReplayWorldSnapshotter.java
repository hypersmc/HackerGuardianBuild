package me.hackerguardian.main.replay;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Captures immutable full-chunk world keyframes for replay reconstruction.
 *
 * Only ChunkSnapshot acquisition and Bukkit world metadata reads happen on the
 * server thread. Palette encoding, compression and JDBC writes happen on the
 * replay I/O workers.
 */
public final class ReplayWorldSnapshotter {

    private final JavaPlugin plugin;
    private final ReplayStorage storage;
    private final Executor ioExecutor;

    private final boolean enabled;
    private final int chunkRadius;
    private final int maxChunks;
    private final int chunksPerTick;
    private final int maxInflight;
    private final String resourcePackId;

    // replayId -> set of captured or currently queued chunk keys
    private final ConcurrentHashMap<Long, Set<Long>> captured = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicInteger> inflight = new ConcurrentHashMap<>();
    private final Set<Long> contextCaptured = ConcurrentHashMap.newKeySet();

    public ReplayWorldSnapshotter(JavaPlugin plugin, ReplayStorage storage, Executor ioExecutor) {
        this.plugin = plugin;
        this.storage = storage;
        this.ioExecutor = ioExecutor;

        boolean legacyEnabled = plugin.getConfig().getBoolean("Replays.sandbox.enabled", false);
        int legacyRadius = plugin.getConfig().getInt("Replays.sandbox.chunk_radius", 2);
        int legacyMaxChunks = plugin.getConfig().getInt("Replays.sandbox.max_chunks", 200);

        this.enabled = plugin.getConfig().getBoolean("Replays.world_capture.enabled", legacyEnabled);
        this.chunkRadius = Math.max(0, plugin.getConfig().getInt("Replays.world_capture.chunk_radius", legacyRadius));
        this.maxChunks = Math.max(1, plugin.getConfig().getInt("Replays.world_capture.max_chunks", legacyMaxChunks));
        this.chunksPerTick = Math.max(1, plugin.getConfig().getInt("Replays.world_capture.chunks_per_tick", 2));
        this.maxInflight = Math.max(1, plugin.getConfig().getInt("Replays.world_capture.max_inflight", 4));
        String configuredPack = plugin.getConfig().getString("Replays.world_capture.resource_pack_id", "");
        this.resourcePackId = configuredPack == null || configuredPack.isBlank() ? null : configuredPack.trim();
    }

    public boolean isEnabled() { return enabled; }

    public void onReplayCreated(long replayId) {
        captured.putIfAbsent(replayId, ConcurrentHashMap.newKeySet());
        inflight.putIfAbsent(replayId, new AtomicInteger());
    }

    public void onReplayFinished(long replayId) {
        captured.remove(replayId);
        inflight.remove(replayId);
        contextCaptured.remove(replayId);
    }

    /**
     * Runs on the server thread. A stored chunk is a complete block-state image of
     * that chunk (all Y levels), and captured_at records the exact instant at which
     * Bukkit produced the immutable snapshot. Different chunks can therefore have
     * slightly different anchors and the web viewer can reconstruct around each one
     * without pretending every chunk was captured in the same millisecond.
     */
    public void tickCapture(long replayId, Player target) {
        if (!enabled || target == null || !target.isOnline()) return;

        World world = target.getWorld();
        Location loc = target.getLocation();
        int playerChunkX = loc.getBlockX() >> 4;
        int playerChunkZ = loc.getBlockZ() >> 4;
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;
        String worldName = world.getName();

        captureContextOnce(replayId, world);

        Set<Long> set = captured.computeIfAbsent(replayId, key -> ConcurrentHashMap.newKeySet());
        AtomicInteger active = inflight.computeIfAbsent(replayId, key -> new AtomicInteger());
        int scheduled = 0;

        // Capture nearest chunks first so a short replay still gets useful visual context.
        for (int radius = 0; radius <= chunkRadius && scheduled < chunksPerTick; radius++) {
            for (int dx = -radius; dx <= radius && scheduled < chunksPerTick; dx++) {
                for (int dz = -radius; dz <= radius && scheduled < chunksPerTick; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    if (set.size() >= maxChunks || active.get() >= maxInflight) return;

                    int chunkX = playerChunkX + dx;
                    int chunkZ = playerChunkZ + dz;
                    long key = (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
                    if (!set.add(key)) continue;

                    final ChunkSnapshot snapshot;
                    final long capturedAtMs;
                    try {
                        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
                        snapshot = chunk.getChunkSnapshot(false, false, false);
                        capturedAtMs = System.currentTimeMillis();
                    } catch (Exception exception) {
                        set.remove(key);
                        plugin.getLogger().warning("[Replay] Failed to snapshot chunk " + chunkX + "," + chunkZ
                                + " for replay " + replayId + ": " + exception.getMessage());
                        continue;
                    }

                    active.incrementAndGet();
                    scheduled++;
                    ioExecutor.execute(() -> {
                        try {
                            byte[] raw = ReplayChunkSnapshotCodec.encodeChunk(snapshot, minY, maxY);
                            storage.upsertWorldChunk(replayId, worldName, chunkX, chunkZ, capturedAtMs, raw);
                        } catch (Exception exception) {
                            set.remove(key);
                            plugin.getLogger().warning("[Replay] Failed to store world snapshot for replay "
                                    + replayId + " chunk " + chunkX + "," + chunkZ + ": " + exception.getMessage());
                            if (plugin.getConfig().getBoolean("debug")) exception.printStackTrace();
                        } finally {
                            active.decrementAndGet();
                        }
                    });
                }
            }
        }
    }

    private void captureContextOnce(long replayId, World world) {
        if (!contextCaptured.add(replayId)) return;

        // Copy Bukkit-owned state to plain immutable values before leaving the main thread.
        ReplayWorldContext context = new ReplayWorldContext(
                world.getName(),
                Bukkit.getMinecraftVersion(),
                world.getEnvironment().name(),
                world.getTime(),
                world.getFullTime(),
                world.hasStorm(),
                world.isThundering(),
                resourcePackId
        );

        ioExecutor.execute(() -> {
            try {
                storage.upsertWorldContext(replayId, context);
            } catch (Exception exception) {
                contextCaptured.remove(replayId);
                plugin.getLogger().warning("[Replay] Failed to store world context for replay "
                        + replayId + ": " + exception.getMessage());
                if (plugin.getConfig().getBoolean("debug")) exception.printStackTrace();
            }
        });
    }
}
