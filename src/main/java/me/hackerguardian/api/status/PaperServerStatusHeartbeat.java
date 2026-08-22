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
import java.util.concurrent.atomic.AtomicBoolean;

/** Periodically publishes sanitized backend state to the shared SQL database. */
public final class PaperServerStatusHeartbeat {

    private final HackerGuardian plugin;
    private final ServerStatusRepository repository;
    private final LearningPlayerStatusRepository learningPlayers;
    private final ServerCompatibility compatibility;
    private final String serverName;
    private final AtomicBoolean writeInFlight = new AtomicBoolean();
    private BukkitTask captureTask;

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
        if (captureTask != null) return;
        long intervalTicks = Math.max(100L,
                plugin.getConfig().getLong("SettingsWeb.Api.server_status_interval_ticks", 400L));
        captureAndPublish();
        // Bukkit/runtime state is captured on the main thread. Only JDBC work is
        // moved off-thread so the heartbeat does not call Bukkit APIs async or
        // block the server tick on database latency.
        captureTask = Bukkit.getScheduler().runTaskTimer(plugin, this::captureAndPublish, intervalTicks, intervalTicks);
    }

    public void stop() {
        if (captureTask != null) {
            captureTask.cancel();
            captureTask = null;
        }
        try {
            repository.markOffline(serverName);
        } catch (Exception e) {
            plugin.getLogger().warning("[HG-API] Failed to mark backend status offline: " + e.getMessage());
        }
    }

    private void captureAndPublish() {
        if (!writeInFlight.compareAndSet(false, true)) return;
        final Snapshot captured;
        try {
            captured = capture();
        } catch (Exception e) {
            writeInFlight.set(false);
            plugin.getLogger().warning("[HG-API] Backend status capture failed: " + e.getMessage());
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                repository.heartbeat(captured.server());
                learningPlayers.upsertAll(captured.players());
            } catch (Exception e) {
                plugin.getLogger().warning("[HG-API] Backend status heartbeat failed: " + e.getMessage());
            } finally {
                writeInFlight.set(false);
            }
        });
    }

    private Snapshot capture() {
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

        ServerStatusRepository.Status serverStatus = new ServerStatusRepository.Status(
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
        );
        return new Snapshot(serverStatus, List.copyOf(playerStatuses));
    }

    private record Snapshot(ServerStatusRepository.Status server,
                            List<LearningPlayerStatusRepository.PlayerStatus> players) {}
}
