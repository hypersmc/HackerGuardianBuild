package me.hackerguardian.main.detection.learning;

import me.hackerguardian.main.HackerGuardian;
import me.hackerguardian.main.detection.probe.BehaviorProbeEngine;
import me.hackerguardian.main.detection.probe.ProbeResultRecorder;
import me.hackerguardian.main.detection.telemetry.BehaviorSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Learning Mode v2: continuously collects candidate-normal behavior from a
 * deliberately trusted player population without turning detections into
 * training labels.
 */
public final class LearningRuntime {

    private final HackerGuardian plugin;
    private final boolean enabled;
    private final boolean notifyPlayers;
    private final String trustedPermission;
    private final Set<UUID> trustedAllowlist;
    private final long quarantineMs;
    private final long minimumBaselineMs;
    private final long sampleIntervalMs;
    private final long maxObservationGapMs;
    private final long stateSaveTicks;

    private final Map<UUID, LearningPlayerState> states = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastObservedMs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSampleMs = new ConcurrentHashMap<>();
    private final Set<UUID> notifiedThisSession = ConcurrentHashMap.newKeySet();

    private final LearningStateStore stateStore;
    private NormalityDatasetRecorder datasetRecorder;
    private ProbeResultRecorder probeResultRecorder;
    private BehaviorProbeEngine probeEngine;
    private BukkitTask stateSaveTask;
    private boolean running;

    public LearningRuntime(HackerGuardian plugin, FileConfiguration config) {
        this.plugin = plugin;
        String root = "DetectionV2.learning.";
        this.enabled = config.getBoolean(root + "enabled", false);
        this.notifyPlayers = config.getBoolean(root + "notify_players", true);
        this.trustedPermission = config.getString(root + "trusted_permission", "hg.learning.trusted");
        this.trustedAllowlist = parseUuidSet(config.getStringList(root + "trusted_uuids"));
        this.quarantineMs = daysToMs(config.getDouble(root + "quarantine_days", 14.0));
        this.minimumBaselineMs = hoursToMs(config.getDouble(root + "minimum_baseline_hours", 10.0));
        this.sampleIntervalMs = secondsToMs(config.getDouble(root + "sample_interval_seconds", 5.0));
        this.maxObservationGapMs = Math.max(5_000L, sampleIntervalMs * 3L);
        this.stateSaveTicks = Math.max(20L, config.getLong(root + "state_save_interval_ticks", 1200L));

        Path statePath = resolveDataPath(
                config.getString(root + "state_file", "ml/normality/learning-state.properties"),
                "ml/normality/learning-state.properties"
        );
        Path manifestPath = resolveDataPath(
                config.getString(root + "manifest_file", "ml/normality/trust-manifest-v1.csv"),
                "ml/normality/trust-manifest-v1.csv"
        );
        this.stateStore = new LearningStateStore(statePath, manifestPath, plugin.getLogger(), minimumBaselineMs);
        states.putAll(stateStore.load());

        if (enabled) initializeOutputs(config);
    }

    private void initializeOutputs(FileConfiguration config) {
        String root = "DetectionV2.learning.";
        try {
            File datasetFile = resolveDataPath(
                    config.getString(root + "dataset.file", "ml/normality/candidate-behavior-v1.csv"),
                    "ml/normality/candidate-behavior-v1.csv"
            ).toFile();
            datasetRecorder = new NormalityDatasetRecorder(
                    datasetFile,
                    plugin.getLogger(),
                    config.getInt(root + "dataset.queue_capacity", 8192)
            );
            plugin.getLogger().info("Learning Mode candidate-normality recorder ready: " + datasetFile.getPath());
        } catch (Exception e) {
            plugin.getLogger().warning("Learning Mode candidate dataset is unavailable: " + e.getMessage());
            datasetRecorder = null;
        }

        try {
            File probeFile = resolveDataPath(
                    config.getString(root + "probes.result_file", "ml/normality/probe-results-v1.csv"),
                    "ml/normality/probe-results-v1.csv"
            ).toFile();
            probeResultRecorder = new ProbeResultRecorder(
                    probeFile,
                    plugin.getLogger(),
                    config.getInt(root + "probes.queue_capacity", 1024)
            );
            probeEngine = new BehaviorProbeEngine(plugin, config, probeResultRecorder);
        } catch (Exception e) {
            plugin.getLogger().warning("Behavioral probe subsystem is unavailable: " + e.getMessage());
            if (probeResultRecorder != null) probeResultRecorder.shutdown();
            probeResultRecorder = null;
            probeEngine = null;
        }
    }

    public void start() {
        if (!enabled || running) return;
        running = true;
        Bukkit.getPluginManager().registerEvents(new LearningListener(this), plugin);
        if (probeEngine != null) probeEngine.start();

        stateSaveTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                () -> stateStore.saveAsync(states.values()),
                stateSaveTicks,
                stateSaveTicks
        );

        for (Player player : Bukkit.getOnlinePlayers()) onJoin(player);
        plugin.getLogger().info("Learning Mode v2 enabled: trusted population telemetry is being collected as quarantined candidate-normal data.");
    }

    public void observe(Player player, BehaviorSnapshot snapshot) {
        if (!running || player == null || snapshot == null) return;
        long now = snapshot.getCapturedAtMs();
        TrustDecision trust = trustDecision(player);
        LearningPlayerState state = states.computeIfAbsent(player.getUniqueId(), LearningPlayerState::new);
        state.markSeen(player.getName(), trust != null, now);

        if (trust == null) {
            sessions.remove(player.getUniqueId());
            lastObservedMs.remove(player.getUniqueId());
            lastSampleMs.remove(player.getUniqueId());
            return;
        }

        UUID sessionId = sessions.computeIfAbsent(player.getUniqueId(), ignored -> UuidV7.next());
        Long previous = lastObservedMs.put(player.getUniqueId(), now);
        if (previous != null && now > previous) {
            state.addCollected(Math.min(now - previous, maxObservationGapMs));
        }

        maybeNotify(player);
        long eligibleAfterMs = safeAdd(now, quarantineMs);
        Long lastSample = lastSampleMs.get(player.getUniqueId());
        if (datasetRecorder != null && (lastSample == null || now - lastSample >= sampleIntervalMs)) {
            lastSampleMs.put(player.getUniqueId(), now);
            datasetRecorder.record(snapshot, sessionId, trust.source, eligibleAfterMs);
        }

        if (probeEngine != null) {
            LearningPlayerState.Snapshot stateSnapshot = state.snapshot();
            boolean started = probeEngine.consider(
                    player,
                    snapshot,
                    sessionId,
                    stateSnapshot.getCollectedMs(),
                    stateSnapshot.getLastProbeMs(),
                    trust.source,
                    eligibleAfterMs
            );
            if (started) state.markProbe(now);
        }
    }

    public void onJoin(Player player) {
        if (!running || player == null) return;
        long now = System.currentTimeMillis();
        TrustDecision trust = trustDecision(player);
        LearningPlayerState state = states.computeIfAbsent(player.getUniqueId(), LearningPlayerState::new);
        state.markSeen(player.getName(), trust != null, now);
        sessions.put(player.getUniqueId(), UuidV7.next());
        lastObservedMs.remove(player.getUniqueId());
        lastSampleMs.remove(player.getUniqueId());
        if (trust != null) maybeNotify(player);
    }

    public void onQuit(Player player) {
        if (player == null) return;
        long now = System.currentTimeMillis();
        LearningPlayerState state = states.get(player.getUniqueId());
        TrustDecision trust = trustDecision(player);
        if (state != null) state.markSeen(player.getName(), trust != null, now);
        sessions.remove(player.getUniqueId());
        lastObservedMs.remove(player.getUniqueId());
        lastSampleMs.remove(player.getUniqueId());
        notifiedThisSession.remove(player.getUniqueId());
        if (probeEngine != null) probeEngine.cancelPlayer(player.getUniqueId(), "QUIT");
    }

    public void onMove(PlayerMoveEvent event) {
        if (probeEngine != null) probeEngine.onMove(event);
    }

    public void onSwing(PlayerAnimationEvent event) {
        if (probeEngine != null) probeEngine.onSwing(event);
    }

    public boolean forceProbe(Player player, BehaviorSnapshot snapshot) {
        if (!running || probeEngine == null || player == null || snapshot == null) return false;
        UUID sessionId = sessions.computeIfAbsent(player.getUniqueId(), ignored -> UuidV7.next());
        TrustDecision trust = trustDecision(player);
        String source = trust == null ? "MANUAL_PROBE" : trust.source;
        return probeEngine.forceProbe(
                player,
                snapshot,
                sessionId,
                source,
                safeAdd(System.currentTimeMillis(), quarantineMs)
        );
    }

    public void stop() {
        running = false;
        if (stateSaveTask != null) {
            stateSaveTask.cancel();
            stateSaveTask = null;
        }
        if (probeEngine != null) probeEngine.shutdown();
        stateStore.saveNow(states.values());
        stateStore.shutdown();
        if (datasetRecorder != null) datasetRecorder.shutdown();
        if (probeResultRecorder != null) probeResultRecorder.shutdown();
        sessions.clear();
        lastObservedMs.clear();
        lastSampleMs.clear();
        notifiedThisSession.clear();
    }

    public boolean isEnabled() { return enabled; }
    public boolean isRunning() { return running; }
    public String getTrustedPermission() { return trustedPermission; }
    public double getMinimumBaselineHours() { return minimumBaselineMs / 3_600_000.0; }
    public double getQuarantineDays() { return quarantineMs / 86_400_000.0; }
    public NormalityDatasetRecorder getDatasetRecorder() { return datasetRecorder; }
    public LearningStateStore getStateStore() { return stateStore; }
    public BehaviorProbeEngine getProbeEngine() { return probeEngine; }
    public Collection<LearningPlayerState> getStates() { return Collections.unmodifiableCollection(states.values()); }

    public PlayerStatus getPlayerStatus(Player player) {
        if (player == null) return null;
        LearningPlayerState state = states.get(player.getUniqueId());
        LearningPlayerState.Snapshot snapshot = state == null ? null : state.snapshot();
        TrustDecision trust = trustDecision(player);
        long collected = snapshot == null ? 0L : snapshot.getCollectedMs();
        return new PlayerStatus(
                player.getUniqueId(), player.getName(), trust != null,
                trust == null ? "NONE" : trust.source,
                collected,
                collected >= minimumBaselineMs,
                snapshot == null ? 0L : snapshot.getLastProbeMs(),
                sessions.get(player.getUniqueId())
        );
    }

    private void maybeNotify(Player player) {
        if (!notifyPlayers || player == null || !notifiedThisSession.add(player.getUniqueId())) return;
        player.sendMessage("§6[HackerGuardian] §eLearning Mode is active. Your gameplay telemetry is being collected as candidate data for the server's normal-behavior model.");
        if (probeEngine != null && probeEngine.isEnabled()) {
            player.sendMessage("§6[HackerGuardian] §7After enough collected play time, occasional client-side test entities may appear so reaction behavior can be measured. These tests do not punish you.");
        }
    }

    private TrustDecision trustDecision(Player player) {
        if (player == null) return null;
        if (trustedAllowlist.contains(player.getUniqueId())) return new TrustDecision("UUID_ALLOWLIST");
        if (trustedPermission != null && !trustedPermission.isBlank() && player.hasPermission(trustedPermission)) {
            return new TrustDecision("PERMISSION");
        }
        return null;
    }

    private Path resolveDataPath(String configured, String fallback) {
        String relative = configured == null || configured.isBlank() ? fallback : configured.trim();
        Path root = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Learning file path must stay inside the plugin data folder: " + relative);
        }
        return resolved;
    }

    private static Set<UUID> parseUuidSet(java.util.List<String> values) {
        Set<UUID> result = new HashSet<>();
        if (values == null) return result;
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            try { result.add(UUID.fromString(value.trim())); }
            catch (IllegalArgumentException ignored) {}
        }
        return Collections.unmodifiableSet(result);
    }

    private static long secondsToMs(double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0.0) return 1000L;
        return Math.max(250L, (long) Math.min(Long.MAX_VALUE, seconds * 1000.0));
    }

    private static long hoursToMs(double hours) {
        if (!Double.isFinite(hours) || hours <= 0.0) return 0L;
        return (long) Math.min(Long.MAX_VALUE, hours * 3_600_000.0);
    }

    private static long daysToMs(double days) {
        if (!Double.isFinite(days) || days <= 0.0) return 0L;
        return (long) Math.min(Long.MAX_VALUE, days * 86_400_000.0);
    }

    private static long safeAdd(long a, long b) {
        if (b <= 0L) return a;
        return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }

    private static final class TrustDecision {
        final String source;
        TrustDecision(String source) { this.source = source; }
    }

    public static final class PlayerStatus {
        private final UUID playerId;
        private final String playerName;
        private final boolean trusted;
        private final String trustSource;
        private final long collectedMs;
        private final boolean baselineHoursMet;
        private final long lastProbeMs;
        private final UUID sessionId;

        PlayerStatus(UUID playerId, String playerName, boolean trusted, String trustSource,
                     long collectedMs, boolean baselineHoursMet, long lastProbeMs, UUID sessionId) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.trusted = trusted;
            this.trustSource = trustSource;
            this.collectedMs = collectedMs;
            this.baselineHoursMet = baselineHoursMet;
            this.lastProbeMs = lastProbeMs;
            this.sessionId = sessionId;
        }

        public UUID getPlayerId() { return playerId; }
        public String getPlayerName() { return playerName; }
        public boolean isTrusted() { return trusted; }
        public String getTrustSource() { return trustSource; }
        public long getCollectedMs() { return collectedMs; }
        public double getCollectedHours() { return collectedMs / 3_600_000.0; }
        public boolean isBaselineHoursMet() { return baselineHoursMet; }
        public long getLastProbeMs() { return lastProbeMs; }
        public UUID getSessionId() { return sessionId; }
    }
}
