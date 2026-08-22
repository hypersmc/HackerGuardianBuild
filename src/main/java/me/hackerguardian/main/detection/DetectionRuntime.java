package me.hackerguardian.main.detection;

import me.hackerguardian.main.HackerGuardian;
import me.hackerguardian.main.detection.detectors.ClickBurstDetector;
import me.hackerguardian.main.detection.detectors.ReachEnvelopeDetector;
import me.hackerguardian.main.detection.learning.LearningRuntime;
import me.hackerguardian.main.detection.ml.MlBehaviorDetector;
import me.hackerguardian.main.detection.ml.MlDatasetRecorder;
import me.hackerguardian.main.detection.normality.NormalityDetector;
import me.hackerguardian.main.detection.telemetry.BehaviorSnapshot;
import me.hackerguardian.main.detection.telemetry.BehaviorTelemetryCollector;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.nio.file.Path;

/** Lifecycle owner for Detection v2, supervised ML, and normality learning. */
public final class DetectionRuntime {

    private final HackerGuardian plugin;
    private final BehaviorTelemetryCollector collector;
    private final DetectionEngine engine;
    private final long assessmentIntervalTicks;
    private final int defaultCaptureMinutes;
    private final LearningRuntime learningRuntime;

    private MlBehaviorDetector mlDetector;
    private NormalityDetector normalityDetector;
    private MlDatasetRecorder datasetRecorder;
    private BukkitTask assessmentTask;
    private boolean running;

    public DetectionRuntime(HackerGuardian plugin) {
        this.plugin = plugin;
        FileConfiguration config = plugin.getConfig();

        long windowMs = config.getLong("DetectionV2.window_ms", 5000L);
        int historySize = config.getInt("DetectionV2.history_size", 20);
        this.assessmentIntervalTicks = clampLong(
                config.getLong("DetectionV2.assessment_interval_ticks", 20L),
                1L,
                20L * 60L
        );
        this.defaultCaptureMinutes = (int) clampLong(
                config.getLong("DetectionV2.ml.dataset.default_capture_minutes", 10L),
                1L,
                120L
        );

        this.collector = new BehaviorTelemetryCollector(windowMs);
        this.engine = new DetectionEngine(plugin.getLogger(), historySize);
        this.learningRuntime = new LearningRuntime(plugin, config);

        initializeDatasetRecorder(config);
        registerConfiguredDetectors(config);
    }

    private void initializeDatasetRecorder(FileConfiguration config) {
        if (!config.getBoolean("DetectionV2.ml.dataset.enabled", true)) return;
        try {
            File datasetFile = resolveDataFile(
                    config.getString("DetectionV2.ml.dataset.file", "ml/dataset-v1.csv"),
                    "ml/dataset-v1.csv"
            );
            datasetRecorder = new MlDatasetRecorder(
                    datasetFile,
                    plugin.getLogger(),
                    config.getInt("DetectionV2.ml.dataset.queue_capacity", 2048)
            );
            plugin.getLogger().info("ML labeled-dataset recorder ready: " + datasetFile.getPath());
        } catch (Exception e) {
            datasetRecorder = null;
            plugin.getLogger().warning("ML dataset recorder is unavailable: " + e.getMessage());
        }
    }

    private void registerConfiguredDetectors(FileConfiguration config) {
        if (config.getBoolean("DetectionV2.detectors.reach.enabled", true)) {
            engine.registerDetector(new ReachEnvelopeDetector(
                    config.getDouble("DetectionV2.detectors.reach.soft_distance", 4.5),
                    config.getDouble("DetectionV2.detectors.reach.hard_distance", 6.0)
            ));
        }

        if (config.getBoolean("DetectionV2.detectors.click_burst.enabled", true)) {
            engine.registerDetector(new ClickBurstDetector(
                    config.getDouble("DetectionV2.detectors.click_burst.soft_cps", 22.0),
                    config.getDouble("DetectionV2.detectors.click_burst.hard_cps", 35.0),
                    config.getInt("DetectionV2.detectors.click_burst.minimum_swings", 20)
            ));
        }

        if (config.getBoolean("DetectionV2.ml.enabled", false)) {
            File modelFile = resolveDataFile(
                    config.getString("DetectionV2.ml.model_file", "models/behavior-v1.hgml"),
                    "models/behavior-v1.hgml"
            );
            mlDetector = new MlBehaviorDetector(
                    plugin.getLogger(),
                    modelFile,
                    config.getInt("DetectionV2.ml.minimum_activity_samples", 20),
                    config.getInt("DetectionV2.ml.max_ping_ms", 500),
                    config.getDouble("DetectionV2.ml.minimum_tps", 18.0),
                    config.getDouble("DetectionV2.ml.finding_floor", 0.55),
                    config.getDouble("DetectionV2.ml.base_reliability", 0.85)
            );
            mlDetector.reload();
            engine.registerDetector(mlDetector);
        }

        if (config.getBoolean("DetectionV2.normality.enabled", false)) {
            File modelFile = resolveDataFile(
                    config.getString("DetectionV2.normality.model_file", "models/population-normality-v1.hgif"),
                    "models/population-normality-v1.hgif"
            );
            normalityDetector = new NormalityDetector(
                    plugin.getLogger(),
                    modelFile,
                    config.getInt("DetectionV2.normality.minimum_activity_samples", 20),
                    config.getInt("DetectionV2.normality.max_ping_ms", 500),
                    config.getDouble("DetectionV2.normality.minimum_tps", 18.0),
                    config.getDouble("DetectionV2.normality.finding_floor", 0.60),
                    config.getDouble("DetectionV2.normality.base_reliability", 0.60),
                    config.getInt("DetectionV2.normality.target_training_players", 25)
            );
            normalityDetector.reload();
            engine.registerDetector(normalityDetector);
        }
    }

    public void start() {
        if (running) return;
        running = true;

        Bukkit.getPluginManager().registerEvents(new DetectionTelemetryListener(collector, engine), plugin);
        learningRuntime.start();
        assessmentTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                this::assessOnlinePlayers,
                assessmentIntervalTicks,
                assessmentIntervalTicks
        );

        String supervisedState = mlDetector == null
                ? "disabled"
                : (mlDetector.isLoaded() ? "loaded" : "enabled/model-unavailable");
        String normalityState = normalityDetector == null
                ? "disabled"
                : (normalityDetector.isLoaded() ? "loaded" : "enabled/model-unavailable");
        plugin.getLogger().info("Detection v2 started in OBSERVE-ONLY mode with "
                + engine.getDetectorCount() + " detector(s), window=" + collector.getWindowMs()
                + "ms, supervised-ML=" + supervisedState
                + ", population-normality=" + normalityState
                + ", learning=" + (learningRuntime.isEnabled() ? "enabled" : "disabled") + ".");
    }

    private void assessOnlinePlayers() {
        if (!running) return;
        for (Player player : Bukkit.getOnlinePlayers()) assess(player, true);
    }

    public DetectionAssessment assessNow(Player player) {
        return assess(player, false);
    }

    public BehaviorSnapshot snapshotNow(Player player) {
        return player == null ? null : collector.snapshot(player);
    }

    private DetectionAssessment assess(Player player, boolean recordTrainingSample) {
        if (player == null) return null;
        BehaviorSnapshot snapshot = collector.snapshot(player);
        if (snapshot == null) return null;

        if (recordTrainingSample) {
            if (datasetRecorder != null) datasetRecorder.record(snapshot);
            learningRuntime.observe(player, snapshot);
        }
        return engine.assess(snapshot);
    }

    public boolean reloadMlModel() {
        return mlDetector != null && mlDetector.reload();
    }

    public boolean reloadNormalityModel() {
        return normalityDetector != null && normalityDetector.reload();
    }

    public void stop() {
        running = false;
        if (assessmentTask != null) {
            assessmentTask.cancel();
            assessmentTask = null;
        }
        learningRuntime.stop();
        if (datasetRecorder != null) {
            datasetRecorder.shutdown();
            datasetRecorder = null;
        }
        collector.clear();
        engine.clear();
    }

    public boolean isRunning() { return running; }
    public BehaviorTelemetryCollector getCollector() { return collector; }
    public DetectionEngine getEngine() { return engine; }
    public MlBehaviorDetector getMlDetector() { return mlDetector; }
    public NormalityDetector getNormalityDetector() { return normalityDetector; }
    public MlDatasetRecorder getDatasetRecorder() { return datasetRecorder; }
    public LearningRuntime getLearningRuntime() { return learningRuntime; }
    public int getDefaultCaptureMinutes() { return defaultCaptureMinutes; }

    private File resolveDataFile(String configured, String fallback) {
        String relative = configured == null || configured.isBlank() ? fallback : configured.trim();
        Path root = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Detection file path must stay inside the plugin data folder: " + relative);
        }
        return resolved.toFile();
    }

    private static long clampLong(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
