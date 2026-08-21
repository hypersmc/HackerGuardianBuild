package me.hackerguardian.main.replay;

import org.bukkit.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.UUID;

public final class ReplaySandboxWorld {

    private final JavaPlugin plugin;

    public ReplaySandboxWorld(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public World loadOrCreate(long replayId, UUID viewerUuid) {
        String prefix = plugin.getConfig().getString("Replays.sandbox.world_prefix", "hg_replay_");
        String viewer = viewerUuid == null ? "unknown" : viewerUuid.toString().replace("-", "");
        String name = prefix + replayId + "_" + viewer;

        World existing = Bukkit.getWorld(name);
        if (existing != null) return existing;

        WorldCreator wc = new WorldCreator(name);
        wc.type(WorldType.FLAT);
        wc.generateStructures(false);

        World w = wc.createWorld();
        if (w != null) {
            w.setAutoSave(false);
            w.setKeepSpawnInMemory(false);
            w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
            w.setGameRule(GameRule.DO_MOB_SPAWNING, false);
            w.setGameRule(GameRule.RANDOM_TICK_SPEED, 0);
            w.setTime(6000);
            w.setStorm(false);
        }
        return w;
    }

    public void deleteWorld(String worldName) {
        if (worldName == null || worldName.isBlank()) return;

        String prefix = plugin.getConfig().getString("Replays.sandbox.world_prefix", "hg_replay_");
        if (!worldName.startsWith(prefix)) {
            plugin.getLogger().warning("[Replay] Refusing to delete non-sandbox world: " + worldName);
            return;
        }

        World w = Bukkit.getWorld(worldName);
        File folder;

        if (w != null) {
            folder = w.getWorldFolder();
            Bukkit.unloadWorld(w, false);
        } else {
            folder = new File(Bukkit.getWorldContainer(), worldName);
        }

        deleteRecursive(folder);
        plugin.getLogger().info("[Replay] Deleted sandbox world " + worldName);
    }

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] files = f.listFiles();
        if (files != null) {
            for (File child : files) deleteRecursive(child);
        }
        if (!f.delete() && f.exists()) {
            plugin.getLogger().warning("[Replay] Could not delete " + f.getAbsolutePath());
        }
    }
}
