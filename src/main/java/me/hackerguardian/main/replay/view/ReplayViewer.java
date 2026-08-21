package me.hackerguardian.main.replay.view;

import me.hackerguardian.main.replay.*;
import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;

public final class ReplayViewer implements Listener {

    private final JavaPlugin plugin;
    private final ReplayStorage storage;
    private final Map<UUID, ViewingSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> loadRequests = new ConcurrentHashMap<>();
    private final AtomicLong loadSequence = new AtomicLong();

    public ReplayViewer(JavaPlugin plugin, ReplayStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    public void view(Player staff, long replayId) {
        if (staff == null) return;

        stopViewing(staff);
        UUID viewerId = staff.getUniqueId();
        long requestId = loadSequence.incrementAndGet();
        loadRequests.put(viewerId, requestId);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                ReplayStorage.ReplayMeta meta = storage.getReplayMeta(replayId);
                if (meta == null) {
                    messageIfCurrent(staff, requestId, ChatColor.RED + "Replay not found: " + replayId);
                    return;
                }

                List<ReplayStorage.ReplayChunk> chunks = storage.getChunks(replayId);
                if (chunks.isEmpty()) {
                    messageIfCurrent(staff, requestId, ChatColor.RED + "Replay has no chunks: " + replayId);
                    return;
                }

                FirstSnapshot first = findFirstSnapshot(chunks);
                if (first == null) {
                    messageIfCurrent(staff, requestId, ChatColor.RED + "Replay has no PLAYER_SNAPSHOT frames.");
                    return;
                }

                List<ReplayStorage.WorldChunkSnapshot> snaps = storage.getWorldChunks(replayId);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!isCurrentLoad(staff, requestId)) return;
                    loadRequests.remove(viewerId, requestId);
                    startPlayback(staff, meta, chunks, first, snaps);
                });
            } catch (Exception ex) {
                messageIfCurrent(staff, requestId,
                        ChatColor.RED + "Failed to load replay " + replayId + " (DB/decode error).");
                plugin.getLogger().warning("[Replay] Failed to load replay " + replayId + ": " + ex.getMessage());
                if (plugin.getConfig().getBoolean("debug")) ex.printStackTrace();
            }
        });
    }

    public void stopViewing(Player staff) {
        if (staff == null) return;
        stopViewing(staff.getUniqueId(), staff);
    }

    private void stopViewing(UUID viewerId, Player staff) {
        loadRequests.remove(viewerId);
        ViewingSession session = sessions.remove(viewerId);
        if (session == null) return;

        try {
            if (session.playback != null) session.playback.stop();
        } catch (Exception e) {
            plugin.getLogger().warning("[Replay] Failed to stop playback " + session.replayId + ": " + e.getMessage());
        }

        if (staff != null && staff.isOnline()) {
            try {
                staff.teleport(session.returnLocation);
                staff.setGameMode(session.returnGamemode);
                staff.setFlying(session.returnFlying);
            } catch (Exception e) {
                plugin.getLogger().warning("[Replay] Failed to restore viewer " + viewerId + ": " + e.getMessage());
            }
        }

        if (session.sandboxWorldName != null
                && plugin.getConfig().getBoolean("Replays.sandbox.delete_world_on_exit", true)) {
            new ReplaySandboxWorld(plugin).deleteWorld(session.sandboxWorldName);
        }
    }

    public void shutdown() {
        loadRequests.clear();
        for (UUID viewerId : new ArrayList<>(sessions.keySet())) {
            stopViewing(viewerId, Bukkit.getPlayer(viewerId));
        }
    }

    private void startPlayback(Player staff, ReplayStorage.ReplayMeta meta,
                               List<ReplayStorage.ReplayChunk> chunks,
                               FirstSnapshot first,
                               List<ReplayStorage.WorldChunkSnapshot> snaps) {
        if (!staff.isOnline()) return;

        Location returnLoc = staff.getLocation().clone();
        GameMode gm = staff.getGameMode();
        boolean flying = staff.isFlying();

        boolean sandboxEnabled = plugin.getConfig().getBoolean("Replays.sandbox.enabled", false);
        World world;
        Location startLoc;
        String sandboxWorldName = null;

        if (sandboxEnabled && snaps != null && !snaps.isEmpty()) {
            ReplaySandboxWorld sandbox = new ReplaySandboxWorld(plugin);
            world = sandbox.loadOrCreate(meta.id, staff.getUniqueId());
            if (world == null) {
                staff.sendMessage(ChatColor.RED + "Could not create replay sandbox world.");
                return;
            }
            sandboxWorldName = world.getName();

            int originChunkX = ((int) Math.floor(first.x)) >> 4;
            int originChunkZ = ((int) Math.floor(first.z)) >> 4;
            pasteSnapshotsInto(world, snaps, originChunkX, originChunkZ);

            startLoc = new Location(
                    world,
                    first.x - (originChunkX << 4),
                    first.y,
                    first.z - (originChunkZ << 4),
                    first.yaw,
                    first.pitch
            );
        } else {
            world = Bukkit.getWorld(first.world);
            if (world == null) {
                staff.sendMessage(ChatColor.RED + "World not loaded: " + first.world);
                return;
            }
            startLoc = new Location(world, first.x, first.y, first.z, first.yaw, first.pitch);
        }

        ViewingSession session = new ViewingSession(
                meta.id, returnLoc, gm, flying, sandboxWorldName
        );
        sessions.put(staff.getUniqueId(), session);

        staff.teleport(startLoc.clone().add(2, 0, 2));
        staff.sendMessage(ChatColor.GRAY + "Replaying #" + meta.id + " (" + meta.playerName + ")");

        long streamStart = chunks.get(0).startMs;
        long streamEnd = chunks.get(chunks.size() - 1).endMs;
        long seekToMs = meta.startedAt;

        if (seekToMs < streamStart || seekToMs > streamEnd || (streamEnd - streamStart) < 1500) {
            seekToMs = streamStart;
        }

        ReplayPlayback playback = new ReplayPlayback(plugin, staff, chunks, seekToMs, this);
        session.playback = playback;
        playback.start(startLoc, meta.playerName, null);
    }

    private boolean isCurrentLoad(Player staff, long requestId) {
        return staff != null
                && staff.isOnline()
                && Objects.equals(loadRequests.get(staff.getUniqueId()), requestId);
    }

    private void messageIfCurrent(Player staff, long requestId, String message) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!isCurrentLoad(staff, requestId)) return;
            loadRequests.remove(staff.getUniqueId(), requestId);
            staff.sendMessage(message);
        });
    }

    private static final class FirstSnapshot {
        final String world;
        final double x, y, z;
        final float yaw, pitch;

        FirstSnapshot(String world, double x, double y, double z, float yaw, float pitch) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    private FirstSnapshot findFirstSnapshot(List<ReplayStorage.ReplayChunk> chunks) throws Exception {
        for (ReplayStorage.ReplayChunk ch : chunks) {
            byte[] raw = gunzip(ch.data);
            ReplayCodec.In in = new ReplayCodec.In(new ByteArrayInputStream(raw));

            while (in.hasMore()) {
                in.readVarInt();
                int len = in.readVarInt();
                byte[] evBytes = in.readBytes(len);

                FirstSnapshot fs = tryDecodeFirstSnapshot(evBytes);
                if (fs != null) return fs;
            }
        }
        return null;
    }

    private FirstSnapshot tryDecodeFirstSnapshot(byte[] evBytes) {
        try {
            ReplayCodec.In evIn = new ReplayCodec.In(new ByteArrayInputStream(evBytes));
            int typeOrdinal = evIn.readVarInt();
            ReplayEventType[] types = ReplayEventType.values();
            if (typeOrdinal < 0 || typeOrdinal >= types.length) return null;

            ReplayEventType type = types[typeOrdinal];
            if (type != ReplayEventType.PLAYER_SNAPSHOT) return null;

            String world = evIn.readString(128);
            double x = evIn.readDouble();
            double y = evIn.readDouble();
            double z = evIn.readDouble();
            float yaw = evIn.readFloat();
            float pitch = evIn.readFloat();

            return new FirstSnapshot(world, x, y, z, yaw, pitch);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static byte[] gunzip(byte[] gz) throws IOException {
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(gz));
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = gis.read(buf)) != -1) baos.write(buf, 0, r);
            return baos.toByteArray();
        }
    }

    private void pasteSnapshotsInto(World sandbox, List<ReplayStorage.WorldChunkSnapshot> snaps,
                                    int originChunkX, int originChunkZ) {
        for (ReplayStorage.WorldChunkSnapshot s : snaps) {
            byte[] raw = ReplayStorage.gunzip(s.data);
            ReplayChunkSnapshotCodec.DecodedChunk decoded;
            try {
                decoded = ReplayChunkSnapshotCodec.decodeChunk(raw);
            } catch (Exception ex) {
                if (plugin.getConfig().getBoolean("debug")) ex.printStackTrace();
                continue;
            }

            int targetChunkX = s.chunkX - originChunkX;
            int targetChunkZ = s.chunkZ - originChunkZ;
            Chunk chunk = sandbox.getChunkAt(targetChunkX, targetChunkZ);

            int idx = 0;
            for (int y = decoded.minY; y <= decoded.maxY; y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        int pal = decoded.indices[idx++];
                        if (pal < 0 || pal >= decoded.palette.length) continue;
                        try {
                            BlockData data = Bukkit.createBlockData(decoded.palette[pal]);
                            chunk.getBlock(x, y, z).setBlockData(data, false);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }
    }

    private static final class ViewingSession {
        final long replayId;
        final Location returnLocation;
        final GameMode returnGamemode;
        final boolean returnFlying;
        final String sandboxWorldName;
        ReplayPlayback playback;

        ViewingSession(long replayId, Location returnLocation, GameMode returnGamemode,
                       boolean returnFlying, String sandboxWorldName) {
            this.replayId = replayId;
            this.returnLocation = returnLocation;
            this.returnGamemode = returnGamemode;
            this.returnFlying = returnFlying;
            this.sandboxWorldName = sandboxWorldName;
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent e) {
        stopViewing(e.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onKick(PlayerKickEvent e) {
        stopViewing(e.getPlayer());
    }
}
