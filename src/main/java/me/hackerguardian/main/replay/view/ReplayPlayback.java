package me.hackerguardian.main.replay.view;

import me.hackerguardian.main.replay.*;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

public final class ReplayPlayback {

    private final JavaPlugin plugin;
    private final Player staff;
    private final List<ReplayStorage.ReplayChunk> chunks;
    private final long seekToMs;

    private int chunkIndex = 0;
    private ReplayCodec.In chunkIn;
    private long currentTs;

    private FakeReplayPlayer ghost;
    private BukkitTask scheduled;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    private final Map<String, BlockData> originalBlocks = new HashMap<>();
    private boolean seeking = true;
    private boolean seekSatisfied = false;

    private final Map<UUID, FakeReplayPlayer> nearbyGhosts = new HashMap<>();
    private final Map<UUID, Long> nearbyLastSeen = new HashMap<>();
    private final Map<UUID, UUID> nearbyFakeUuids = new HashMap<>();
    private final long nearbyDespawnGraceMs = 4000L;
    private final ReplayViewer replayViewer;

    public ReplayPlayback(JavaPlugin plugin, Player staff, List<ReplayStorage.ReplayChunk> chunks,
                          long seekToMs, ReplayViewer replayViewer) {
        this.plugin = plugin;
        this.staff = staff;
        this.chunks = chunks;
        this.seekToMs = seekToMs;
        this.replayViewer = replayViewer;
    }

    public void start(Location startLoc, String displayName, WrappedGameProfile skinOrNull) {
        ghost = new FakeReplayPlayer(plugin, staff, null, displayName, skinOrNull);
        ghost.spawn(startLoc);

        loadChunk(0);

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                fastSeekTo(seekToMs);
                plugin.getLogger().info("Replay seek done. chunkIndex=" + chunkIndex + " currentTs=" + currentTs);
                scheduleNext();
            } catch (Exception e) {
                staff.sendMessage(ChatColor.RED + "Replay start failed.");
                if (plugin.getConfig().getBoolean("debug")) e.printStackTrace();
                finish();
            }
        });
    }

    /** Stop playback resources only. Viewer restoration is owned by ReplayViewer. */
    public void stop() {
        if (!stopped.compareAndSet(false, true)) return;
        if (scheduled != null) scheduled.cancel();
        if (ghost != null) ghost.destroy();
        restoreFakeBlocks();
        despawnNearby();
    }

    /** Natural playback completion: stop resources and ask the viewer owner to restore the staff member. */
    private void finish() {
        stop();
        replayViewer.stopViewing(staff);
    }

    private void scheduleNext() {
        if (stopped.get()) return;
        if (!staff.isOnline()) { finish(); return; }

        try {
            Record rec = readNextRecord();
            if (rec == null) {
                plugin.getLogger().info("Replay finished early. chunkIndex=" + chunkIndex + " currentTs=" + currentTs);
                staff.sendMessage(ChatColor.GRAY + "Replay finished.");
                finish();
                return;
            }

            long ticks = Math.max(1L, (rec.deltaMs + 49L) / 50L);
            scheduled = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                applyEvent(rec.eventBytes);
                scheduleNext();
            }, ticks);

        } catch (Exception ex) {
            staff.sendMessage(ChatColor.RED + "Replay playback error (decode).");
            if (plugin.getConfig().getBoolean("debug")) ex.printStackTrace();
            finish();
        }
    }

    private static final class Record {
        final int deltaMs;
        final byte[] eventBytes;
        Record(int deltaMs, byte[] eventBytes) { this.deltaMs = deltaMs; this.eventBytes = eventBytes; }
    }

    private Record readNextRecord() throws IOException {
        while (true) {
            if (chunkIn == null) return null;

            try {
                int delta = chunkIn.readVarInt();
                int len = chunkIn.readVarInt();
                byte[] ev = chunkIn.readBytes(len);
                currentTs += delta;
                return new Record(delta, ev);

            } catch (EOFException eof) {
                if (!nextChunk()) return null;
            }
        }
    }

    private boolean nextChunk() {
        chunkIndex++;
        if (chunkIndex >= chunks.size()) return false;
        loadChunk(chunkIndex);
        return chunkIn != null;
    }

    private void loadChunk(int idx) {
        try {
            ReplayStorage.ReplayChunk ch = chunks.get(idx);
            byte[] raw = gunzip(ch.data);
            this.chunkIn = new ReplayCodec.In(new ByteArrayInputStream(raw));
            this.currentTs = ch.startMs;
        } catch (Exception e) {
            this.chunkIn = null;
        }
    }

    private void fastSeekTo(long seekToMs) throws IOException {
        if (currentTs >= seekToMs) return;

        while (currentTs < seekToMs) {
            Record rec = readNextRecord();
            if (rec == null) return;
            applyEvent(rec.eventBytes);
        }
    }

    private void applyEvent(byte[] evBytes) {
        try {
            ReplayCodec.In evIn = new ReplayCodec.In(new ByteArrayInputStream(evBytes));
            int ord = evIn.readVarInt();

            ReplayEventType[] values = ReplayEventType.values();
            if (ord < 0 || ord >= values.length) return;
            ReplayEventType type = values[ord];

            switch (type) {
                case PLAYER_SNAPSHOT -> {
                    evIn.readString(128); // original world; playback uses viewer/sandbox world
                    double x = evIn.readDouble();
                    double y = evIn.readDouble();
                    double z = evIn.readDouble();
                    float yaw = evIn.readFloat();
                    float pitch = evIn.readFloat();

                    World w = staff.getWorld();
                    if (w != null) {
                        ghost.moveTo(new Location(w, x, y, z, yaw, pitch));
                        if (seeking && currentTs >= seekToMs) seekSatisfied = true;
                    }
                }

                case BLOCK_BREAK, BLOCK_PLACE -> {
                    String world = evIn.readString(128);
                    int bx = evIn.readInt();
                    int by = evIn.readInt();
                    int bz = evIn.readInt();
                    String bt = evIn.readString(64);

                    World w = staff.getWorld();
                    if (w == null) return;

                    Location loc = new Location(w, bx, by, bz);
                    String key = world + ":" + bx + ":" + by + ":" + bz;

                    if (!originalBlocks.containsKey(key)) {
                        originalBlocks.put(key, loc.getBlock().getBlockData().clone());
                    }

                    if (type == ReplayEventType.BLOCK_BREAK) {
                        ghost.swingMainHand();
                        staff.sendBlockChange(loc, Material.AIR.createBlockData());
                    } else {
                        Material m = Material.matchMaterial(bt);
                        if (m != null && m.isBlock()) {
                            ghost.setMainHand(m);
                            ghost.swingMainHand();
                            staff.sendBlockChange(loc, m.createBlockData());
                        }
                    }
                }

                case ARM_SWING -> ghost.swingMainHand();

                case SNEAK_TOGGLE -> evIn.readBoolean();

                case SPRINT_TOGGLE -> evIn.readBoolean();

                case ITEM_CONSUME -> {
                    String mat = evIn.readString(64);
                    Material m = Material.matchMaterial(mat);
                    if (m != null) ghost.setMainHand(m);
                }

                case INVENTORY_CLICK -> {
                    // TODO: render inventory state once the event schema is finalized.
                }

                case ITEM_DROP -> {
                    // TODO: render viewer-only dropped item entities.
                }

                case ITEM_PICKUP -> {
                    // TODO: render item pickup visuals.
                }

                case PROJECTILE_LAUNCH -> {
                    // TODO: render replay projectile entities.
                }

                case PROJECTILE_HIT -> {
                    // TODO: render projectile hit effects.
                }

                case NEARBY_SNAPSHOT -> {
                    evIn.readString(128); // original world; playback uses viewer/sandbox world
                    World w = staff.getWorld();
                    if (w == null) return;

                    int count = evIn.readVarInt();

                    for (int i = 0; i < count; i++) {
                        UUID uid = evIn.readUUID();
                        String name = evIn.readString(16);

                        double x = evIn.readDouble();
                        double y = evIn.readDouble();
                        double z = evIn.readDouble();

                        float yaw = evIn.readFloat();
                        float pitch = evIn.readFloat();

                        String handType = evIn.readString(64);

                        nearbyLastSeen.put(uid, currentTs);
                        UUID fakeUuid = nearbyFakeUuids.computeIfAbsent(uid, k -> UUID.randomUUID());

                        FakeReplayPlayer g = nearbyGhosts.get(uid);
                        if (g == null) {
                            g = new FakeReplayPlayer(plugin, staff, fakeUuid, name, null);
                            try {
                                g.spawn(new Location(w, x, y, z, yaw, pitch));
                            } catch (Exception ex) {
                                plugin.getLogger().warning("[ReplayView] Failed to spawn nearby ghost name=" + name
                                        + " uid=" + uid + " : " + ex);
                                if (plugin.getConfig().getBoolean("debug")) ex.printStackTrace();
                            }
                            nearbyGhosts.put(uid, g);
                        } else {
                            g.moveTo(new Location(w, x, y, z, yaw, pitch));
                        }

                        Material mh = Material.matchMaterial(handType);
                        g.setMainHand(mh == null ? Material.AIR : mh);
                    }

                    long cutoff = currentTs - nearbyDespawnGraceMs;
                    Iterator<Map.Entry<UUID, Long>> it = nearbyLastSeen.entrySet().iterator();
                    while (it.hasNext()) {
                        Map.Entry<UUID, Long> e = it.next();
                        if (e.getValue() < cutoff) {
                            UUID uid = e.getKey();
                            FakeReplayPlayer g = nearbyGhosts.remove(uid);
                            nearbyFakeUuids.remove(uid);

                            if (g != null) {
                                try { g.destroy(); } catch (Exception ignored) {}
                            }
                            it.remove();
                        }
                    }
                }
            }

        } catch (Exception ex) {
            if (plugin.getConfig().getBoolean("debug")) {
                plugin.getLogger().warning("Replay applyEvent failed at ts=" + currentTs
                        + " bytes=" + evBytes.length + " : " + ex);
                ex.printStackTrace();
            }
        }
    }

    private void restoreFakeBlocks() {
        if (!staff.isOnline()) return;
        for (Map.Entry<String, BlockData> e : originalBlocks.entrySet()) {
            String[] parts = e.getKey().split(":");
            if (parts.length != 4) continue;
            World w = Bukkit.getWorld(parts[0]);
            if (w == null) continue;
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            staff.sendBlockChange(new Location(w, x, y, z), e.getValue());
        }
        originalBlocks.clear();
    }

    private void despawnNearby() {
        for (FakeReplayPlayer g : nearbyGhosts.values()) {
            try { g.destroy(); } catch (Exception ignored) {}
        }
        nearbyGhosts.clear();
        nearbyLastSeen.clear();
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
}
