package me.hackerguardian.main.replay.view;

import com.comphenix.protocol.wrappers.WrappedGameProfile;
import me.hackerguardian.main.replay.*;
import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

public final class ReplayViewer implements Listener {

    private final JavaPlugin plugin;
    private final ReplayStorage storage;
    private final java.util.Map<java.util.UUID, ReplayPlayback> active = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, ViewingSession> sessions = new ConcurrentHashMap<>();
    public ReplayViewer(JavaPlugin plugin, ReplayStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    public void view(Player staff, long replayId) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                ReplayStorage.ReplayMeta meta = storage.getReplayMeta(replayId);
                if (meta == null) {
                    Bukkit.getScheduler().runTask(plugin, () ->
                            staff.sendMessage(ChatColor.RED + "Replay not found: " + replayId)
                    );
                    return;
                }

                List<ReplayStorage.ReplayChunk> chunks = storage.getChunks(replayId);
                if (chunks.isEmpty()) {
                    Bukkit.getScheduler().runTask(plugin, () ->
                            staff.sendMessage(ChatColor.RED + "Replay has no chunks: " + replayId)
                    );
                    return;
                }

                // Decode until we find first snapshot to place ghost
                FirstSnapshot first = findFirstSnapshot(chunks);
                if (first == null) {
                    Bukkit.getScheduler().runTask(plugin, () ->
                            staff.sendMessage(ChatColor.RED + "Replay has no PLAYER_SNAPSHOT frames.")
                    );
                    return;
                }
                List<ReplayStorage.WorldChunkSnapshot> snaps = storage.getWorldChunks(replayId);

                Bukkit.getScheduler().runTask(plugin, () -> startPlayback(staff, meta, chunks, first, snaps));

            } catch (Exception ex) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        staff.sendMessage(ChatColor.RED + "Failed to load replay " + replayId + " (DB/decode error).")
                );
                if (plugin.getConfig().getBoolean("debug")) ex.printStackTrace();
            }
        });
    }
    public void stopViewing(Player staff) {
        ReplayPlayback pb = active.remove(staff.getUniqueId());
        if (pb != null) pb.stop();
        ViewingSession session = sessions.remove(staff.getUniqueId());
        if (session == null) return;

        // stop playback first (stops tasks + despawns ghosts)
        try {
            if (session.playback != null) session.playback.stop();
        } catch (Exception ignored) {}

        // restore staff state
        try {
            staff.teleport(session.returnLocation);
            staff.setGameMode(session.returnGamemode);
            staff.setFlying(session.returnFlying);
        } catch (Exception ignored) {}

        // unload + delete sandbox world if used
        if (session.sandboxWorldName != null) {
            ReplaySandboxWorld sandbox = new ReplaySandboxWorld(plugin);
            sandbox.deleteWorld(session.sandboxWorldName);
        }
    }
    private void startPlayback(Player staff, ReplayStorage.ReplayMeta meta,
                               List<ReplayStorage.ReplayChunk> chunks,
                               FirstSnapshot first,
                               List<ReplayStorage.WorldChunkSnapshot> snaps) {

        //stopViewing(staff); //ensuring we don't do something weird
        String prefix = plugin.getConfig().getString("Replays.sandbox.world_prefix", "hg_replay_");

        Location returnLoc = staff.getLocation().clone();
        GameMode gm = staff.getGameMode();
        boolean flying = staff.isFlying();

        boolean sandboxEnabled = plugin.getConfig().getBoolean("Replays.sandbox.enabled", false);
        World w;
        Location startLoc;

        if (sandboxEnabled && snaps != null && !snaps.isEmpty()) {
            ReplaySandboxWorld sandbox = new ReplaySandboxWorld(plugin);
            w = sandbox.loadOrCreate(meta.id);

            // pick origin chunk = first snapshot chunk in original world
            int originChunkX = ((int) Math.floor(first.x)) >> 4;
            int originChunkZ = ((int) Math.floor(first.z)) >> 4;

            // paste on main thread (this method runs on main already)
            pasteSnapshotsInto(w, snaps, originChunkX, originChunkZ);

            // start location near 0,0 equivalent
            startLoc = new Location(w, (first.x - (originChunkX << 4)), first.y, (first.z - (originChunkZ << 4)), first.yaw, first.pitch);
        } else {
            w = Bukkit.getWorld(first.world);
            if (w == null) {
                staff.sendMessage(ChatColor.RED + "World not loaded: " + first.world);
                return;
            }
            startLoc = new Location(w, first.x, first.y, first.z, first.yaw, first.pitch);
        }
        ViewingSession session = new ViewingSession(meta.id, returnLoc, gm, flying, prefix + meta.id);
        sessions.put(staff.getUniqueId(), session);
        staff.teleport(startLoc.clone().add(2, 0, 2));
        staff.sendMessage(ChatColor.GRAY + "Replaying #" + meta.id + " (" + meta.playerName + ")");



        // Seek to the “trigger start” timestamp so playback begins instantly where it matters
        long streamStart = chunks.get(0).startMs;
        long streamEnd   = chunks.get(chunks.size() - 1).endMs;

        // If startedAt is outside the stream, seek to the start instead
        long seekToMs = meta.startedAt;
        if (seekToMs < streamStart || seekToMs > streamEnd) {
            seekToMs = streamStart;
        }

        // OPTIONAL: if the replay is very short, start from beginning anyway
        if ((streamEnd - streamStart) < 1500) {
            seekToMs = streamStart;
        }
        ReplayPlayback pb = new ReplayPlayback(plugin, staff, chunks, seekToMs, this);



        active.put(staff.getUniqueId(), pb);

        // IMPORTANT: actually start it (spawns fake player + begins playback)
        pb.start(startLoc, meta.playerName, null);
    }

    private static final class FirstSnapshot {
        final String world;
        final double x, y, z;
        final float yaw, pitch;
        FirstSnapshot(String world, double x, double y, double z, float yaw, float pitch) {
            this.world = world; this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch;
        }
    }

    private FirstSnapshot findFirstSnapshot(List<ReplayStorage.ReplayChunk> chunks) throws Exception {
        for (ReplayStorage.ReplayChunk ch : chunks) {
            byte[] raw = gunzip(ch.data);
            ReplayCodec.In in = new ReplayCodec.In(new ByteArrayInputStream(raw));

            long t = ch.startMs;
            while (in.hasMore()) {
                int delta = in.readVarInt();
                int len = in.readVarInt();
                byte[] evBytes = in.readBytes(len);
                t += delta;

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
            ReplayEventType type = ReplayEventType.values()[typeOrdinal];

            if (type != ReplayEventType.PLAYER_SNAPSHOT) return null;

            String world = evIn.readString(128);
            double x = evIn.readDouble();
            double y = evIn.readDouble();
            double z = evIn.readDouble();
            float yaw = evIn.readFloat();
            float pitch = evIn.readFloat();



            // onGround bool exists, but not needed for first snapshot
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

    private void pasteSnapshotsInto(World sandbox, List<ReplayStorage.WorldChunkSnapshot> snaps, int originChunkX, int originChunkZ) {
        for (ReplayStorage.WorldChunkSnapshot s : snaps) {
            // decode
            byte[] raw = ReplayStorage.gunzip(s.data);
            ReplayChunkSnapshotCodec.DecodedChunk dc;
            try {
                dc = ReplayChunkSnapshotCodec.decodeChunk(raw);
            } catch (Exception ex) {
                if (plugin.getConfig().getBoolean("debug")) ex.printStackTrace();
                continue;
            }

            int dx = s.chunkX - originChunkX;
            int dz = s.chunkZ - originChunkZ;

            int targetChunkX = dx; // shift into small coords around 0
            int targetChunkZ = dz;

            Chunk chunk = sandbox.getChunkAt(targetChunkX, targetChunkZ);

            int minY = dc.minY;
            int maxY = dc.maxY;
            int height = (maxY - minY + 1);

            int idx = 0;
            for (int y = minY; y <= maxY; y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        int pal = dc.indices[idx++];
                        String bd = dc.palette[pal];
                        try {
                            BlockData data = Bukkit.createBlockData(bd);
                            chunk.getBlock(x, y, z).setBlockData(data, false);
                        } catch (Exception ignored) {}
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

        final String sandboxWorldName; // null if not using sandbox
        ReplayPlayback playback;

        ViewingSession(long replayId,
                       Location returnLocation,
                       GameMode returnGamemode,
                       boolean returnFlying,
                       String sandboxWorldName) {
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