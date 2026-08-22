package me.hackerguardian.api.status;

import me.hackerguardian.compat.ServerCompatibility;
import me.hackerguardian.main.HackerGuardian;
import me.hackerguardian.main.detection.DetectionRuntime;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

/** Periodically publishes sanitized backend state to the shared SQL database. */
public final class PaperServerStatusHeartbeat {

    private final HackerGuardian plugin;
    private final ServerStatusRepository repository;
    private final ServerCompatibility compatibility;
    private final String serverName;
    private BukkitTask task;

    public PaperServerStatusHeartbeat(HackerGuardian plugin) throws Exception {
        this.plugin = plugin;
        this.repository = new ServerStatusRepository(plugin.getDatabase().getDataSource());
        this.repository.ensureTable();
        this.compatibility = ServerCompatibility.detect();
        this.serverName = plugin.getConfig().getString("Settings.server_name", "default");
    }

    public void start() {
        if (task != null) return;
        long intervalTicks = Math.max(100L,
                plugin.getConfig().getLong("SettingsWeb.Api.server_status_interval_ticks", 400L));
        publish();
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::publish, intervalTicks, intervalTicks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        try {
            repository.markOffline(serverName);
        } catch (Exception e) {
            plugin.getLogger().warning("[HG-API] Failed to mark backend status offline: " + e.getMessage());
        }
    }

    private void publish() {
        try {
            DetectionRuntime detection = plugin.getDetectionRuntime();
            boolean detectionEnabled = detection != null && detection.isRunning();
            boolean learningEnabled = detectionEnabled && detection.getLearningRuntime().isEnabled();
            boolean syntheticProbes = learningEnabled
                    && detection.getLearningRuntime().getProbeEngine() != null
                    && compatibility.supportsSyntheticPlayerPackets();

            repository.heartbeat(new ServerStatusRepository.Status(
                    serverName,
                    plugin.getDescription().getVersion(),
                    compatibility.minecraftVersionString(),
                    Bukkit.getOnlinePlayers().size(),
                    detectionEnabled,
                    learningEnabled,
                    syntheticProbes,
                    System.currentTimeMillis()
            ));
        } catch (Exception e) {
            plugin.getLogger().warning("[HG-API] Backend status heartbeat failed: " + e.getMessage());
        }
    }
}
