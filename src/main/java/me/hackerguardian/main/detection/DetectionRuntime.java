package me.hackerguardian.main.detection;

import me.hackerguardian.main.HackerGuardian;
import me.hackerguardian.main.detection.detectors.ClickBurstDetector;
import me.hackerguardian.main.detection.detectors.ReachEnvelopeDetector;
import me.hackerguardian.main.detection.telemetry.BehaviorSnapshot;
import me.hackerguardian.main.detection.telemetry.BehaviorTelemetryCollector;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Lifecycle owner for the new detection pipeline.
 *
 * v2 is intentionally observe-only in this foundation: it records telemetry,
 * evaluates detectors, and keeps a bounded in-memory history. It cannot warn,
 * replay-trigger, report, kick, or punish players yet.
 */
public final class DetectionRuntime {

    private final HackerGuardian plugin;
    private final BehaviorTelemetryCollector collector;
    private final DetectionEngine engine;
    private final long assessmentIntervalTicks;

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

        this.collector = new BehaviorTelemetryCollector(windowMs);
        this.engine = new DetectionEngine(plugin.getLogger(), historySize);

        registerConfiguredDetectors(config);
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
    }

    public void start() {
        if (running) return;
        running = true;

        Bukkit.getPluginManager().registerEvents(new DetectionTelemetryListener(collector, engine), plugin);
        assessmentTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                this::assessOnlinePlayers,
                assessmentIntervalTicks,
                assessmentIntervalTicks
        );

        plugin.getLogger().info("Detection v2 started in OBSERVE-ONLY mode with "
                + engine.getDetectorCount() + " detector(s), window=" + collector.getWindowMs() + "ms.");
    }

    private void assessOnlinePlayers() {
        if (!running) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            assessNow(player);
        }
    }

    public DetectionAssessment assessNow(Player player) {
        if (player == null) return null;
        BehaviorSnapshot snapshot = collector.snapshot(player);
        return engine.assess(snapshot);
    }

    public void stop() {
        running = false;
        if (assessmentTask != null) {
            assessmentTask.cancel();
            assessmentTask = null;
        }
        collector.clear();
        engine.clear();
    }

    public boolean isRunning() {
        return running;
    }

    public BehaviorTelemetryCollector getCollector() {
        return collector;
    }

    public DetectionEngine getEngine() {
        return engine;
    }

    private static long clampLong(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
