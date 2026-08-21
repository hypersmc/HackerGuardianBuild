package me.hackerguardian.main.replay;

import org.bukkit.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class ReplaySandboxWorld {

    private final JavaPlugin plugin;

    public ReplaySandboxWorld(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public World loadOrCreate(long replayId) {
        String prefix = plugin.getConfig().getString("Replays.sandbox.world_prefix", "hg_replay_");
        String name = prefix + replayId;

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
        World w = Bukkit.getWorld(worldName);
        if (w == null) return;

        String name = w.getName();
        Bukkit.unloadWorld(w, false);

        // Delete folder
        File folder = w.getWorldFolder();
        deleteRecursive(folder);

        plugin.getLogger().info("[Replay] Deleted sandbox world " + name);
    }

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] files = f.listFiles();
        if (files != null) {
            for (File c : files) deleteRecursive(c);
        }
        f.delete();
    }
}

