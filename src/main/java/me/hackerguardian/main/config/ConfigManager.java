package me.hackerguardian.main.config;

import me.hackerguardian.main.HackerGuardian;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.List;

/**
 * Loads HackerGuardian's modular Paper configuration files.
 *
 * Existing components still read through JavaPlugin#getConfig(). To avoid a
 * flag-day migration, values from the feature files are merged into the
 * in-memory main configuration after load. Nothing is written back to
 * config.yml, so the on-disk configuration stays split and readable.
 */
public final class ConfigManager {

    private static final List<String> MODULES = List.of(
            "database.yml",
            "detection.yml",
            "replays.yml",
            "moderation.yml",
            "api.yml",
            "secure-link.yml"
    );

    private final HackerGuardian plugin;

    public ConfigManager(HackerGuardian plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();

        for (String resource : MODULES) {
            ensureResource(resource);
            mergeIntoMain(YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), resource)));
        }
    }

    private void ensureResource(String resource) {
        File file = new File(plugin.getDataFolder(), resource);
        if (!file.exists()) {
            plugin.saveResource(resource, false);
        }
    }

    private void mergeIntoMain(YamlConfiguration source) {
        for (String key : source.getKeys(true)) {
            if (source.isConfigurationSection(key)) continue;
            plugin.getConfig().set(key, source.get(key));
        }
    }
}
