package me.hackerguardian.api.status;

import me.hackerguardian.api.learning.LearningPlayerStatusRepository;
import me.hackerguardian.compat.ServerCompatibility;
import me.hackerguardian.main.HackerGuardian;
import me.hackerguardian.main.detection.DetectionRuntime;
import me.hackerguardian.main.detection.learning.LearningPlayerState;
import me.hackerguardian.main.detection.learning.LearningRuntime;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

/** Periodically publishes sanitized backend state to the shared SQL database. */
public final class PaperServerStatusHeartbeat {

    private final HackerGuardian plugin;
    private final ServerStatusRepository repository;
    private final LearningPlayerStatusRepository learningPlayers;
    private final ServerCompatibility compatibility;
    private final String serverName;
    private BukkitTask task;

    public PaperServerStatusHeartbeat(HackerGuardian plugin) throws Exception {
        this.plugin = plugin;
        this.repository = new ServerStatusRepository(plugin.getDatabase().getDataSource());
        this.repository.ensureTable();
        this.learningPlayers = new LearningPlayerStatusRepository(plugin.getDatabase().getDataSource());
        this.learningPlayers.ensureTable();
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
            int trackedPlayers = detectionEnabled ? detection.getCollector().getTrackedPlayerCount() : 0;

            List<String> detectors = new ArrayList<>();
            if (detectionEnabled) {
                for (String id : detection.getEngine().getDetectorIds()) {
                    String type = "snapshot";
                    if (detection.getMlDetector() != null && id.equals(detection.getMlDetector().id())) {
                        type = detection.getMlDetector().isLoaded() ? "ml_loaded" : "ml_unavailable";
                    } else if (detection.getNormalityDetector() != null && id.equals(detection.getNormalityDetector().id())) {
                        type = detection.getNormalityDetector().isLoaded() ? "normality_loaded" : "normality_unavailable";
                    }
                    detectors.add(type + "|" + id);
                }
                for (String id : detection.getDeterministicRuntime().getCheckIds()) {
                    detectors.add("deterministic|" + id);
                }
            }

            LearningRuntime learning = detectionEnabled ? detection.getLearningRuntime() : null;
            boolean learningEnabled = learning != null && learning.isEnabled();
            int trustedPlayers = 0;
            double activeHours = 0.0;
            int activeProbes = 0;
            List<LearningPlayerStatusRepository.PlayerStatus> playerStatuses = new ArrayList<>();
            long now = System.currentTimeMillis();
            if (learning != null) {
                double minimumHours = learning.getMinimumBaselineHours();
                for (LearningPlayerState state : learning.getStates()) {
                    LearningPlayerState.Snapshot snapshot = state.snapshot();
                    if (snapshot.isTrustedLastSeen()) trustedPlayers++;
                    activeHours += snapshot.getCollectedHours();
                    playerStatuses.add(new LearningPlayerStatusRepository.PlayerStatus(
                            serverName,
                            snapshot.getPlayerId().toString(),
                            snapshot.getPlayerName(),
                            snapshot.isTrustedLastSeen(),
                            snapshot.getCollectedHours(),
                            snapshot.getFirstTrustedMs(),
                            snapshot.getLastSeenMs(),
                            snapshot.getLastProbeMs(),
                            snapshot.getCollectedHours() >= minimumHours,
                            now
                    ));
                }
                if (learning.getProbeEngine() != null) {
                    activeProbes = learning.getProbeEngine().getActiveProbeCount();
                }
            }

            boolean syntheticProbes = learningEnabled
                    && learning.getProbeEngine() != null
                    && compatibility.supportsSyntheticPlayerPackets();

            repository.heartbeat(new ServerStatusRepository.Status(
                    serverName,
                    plugin.getDescription().getVersion(),
                    compatibility.minecraftVersionString(),
                    Bukkit.getOnlinePlayers().size(),
                    detectionEnabled,
                    trackedPlayers,
                    String.join(",", detectors),
                    learningEnabled,
                    trustedPlayers,
                    activeHours,
                    activeProbes,
                    syntheticProbes,
                    now
            ));
            learningPlayers.upsertAll(playerStatuses);
        } catch (Exception e) {
            plugin.getLogger().warning("[HG-API] Backend status heartbeat failed: " + e.getMessage());
        }
    }
}
