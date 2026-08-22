package me.hackerguardian.main.detection.normality;

import me.hackerguardian.main.detection.DetectionCategory;
import me.hackerguardian.main.detection.DetectionFinding;
import me.hackerguardian.main.detection.Detector;
import me.hackerguardian.main.detection.ml.FeatureSchemaV1;
import me.hackerguardian.main.detection.ml.FeatureVector;
import me.hackerguardian.main.detection.telemetry.BehaviorSnapshot;
import org.bukkit.GameMode;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/** Population-normality detector backed by an offline-trained Isolation Forest. */
public final class NormalityDetector implements Detector {

    public static final String DETECTOR_ID = "ml.population-normality-v1";

    private final Logger logger;
    private final File modelFile;
    private final int minimumActivitySamples;
    private final int maxPingMs;
    private final double minimumTps;
    private final double findingFloor;
    private final double baseReliability;
    private final int targetTrainingPlayers;
    private final AtomicReference<IsolationForestModel> model = new AtomicReference<>();
    private volatile String lastLoadError = "Model has not been loaded yet";

    public NormalityDetector(Logger logger,
                             File modelFile,
                             int minimumActivitySamples,
                             int maxPingMs,
                             double minimumTps,
                             double findingFloor,
                             double baseReliability,
                             int targetTrainingPlayers) {
        this.logger = logger;
        this.modelFile = modelFile;
        this.minimumActivitySamples = Math.max(1, minimumActivitySamples);
        this.maxPingMs = Math.max(50, maxPingMs);
        this.minimumTps = clamp(minimumTps, 1.0, 20.0);
        this.findingFloor = clamp(findingFloor, 0.01, 0.99);
        this.baseReliability = clamp(baseReliability, 0.05, 1.0);
        this.targetTrainingPlayers = Math.max(1, targetTrainingPlayers);
    }

    @Override
    public String id() { return DETECTOR_ID; }

    public synchronized boolean reload() {
        try {
            IsolationForestModel loaded = IsolationForestModelLoader.load(modelFile);
            model.set(loaded);
            lastLoadError = "";
            if (logger != null) {
                logger.info("Loaded population-normality model '" + loaded.getModelId()
                        + "' (trees=" + loaded.getTreeCount()
                        + ", players=" + loaded.getTrainingPlayers()
                        + ", threshold=" + String.format("%.3f", loaded.getDecisionThreshold()) + ").");
            }
            return true;
        } catch (Exception e) {
            model.set(null);
            lastLoadError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (logger != null) logger.warning("Population-normality model unavailable: " + lastLoadError);
            return false;
        }
    }

    public boolean isLoaded() { return model.get() != null; }
    public IsolationForestModel getModel() { return model.get(); }
    public File getModelFile() { return modelFile; }
    public String getLastLoadError() { return lastLoadError; }

    @Override
    public List<DetectionFinding> analyze(BehaviorSnapshot snapshot) {
        IsolationForestModel loaded = model.get();
        if (loaded == null || snapshot == null) return Collections.emptyList();
        GameMode gameMode = snapshot.getGameMode();
        if (gameMode == GameMode.CREATIVE || gameMode == GameMode.SPECTATOR) return Collections.emptyList();

        int activitySamples = snapshot.getMovementSamples()
                + snapshot.getSwingCount()
                + snapshot.getHitCount()
                + snapshot.getBlocksBroken()
                + snapshot.getBlocksPlaced();
        if (activitySamples < minimumActivitySamples) return Collections.emptyList();
        if (snapshot.getPingMs() > maxPingMs || snapshot.getTps() < minimumTps) return Collections.emptyList();

        FeatureVector vector = FeatureSchemaV1.extract(snapshot);
        double anomaly = loaded.anomalyScore(vector);
        double threshold = Math.max(findingFloor, loaded.getDecisionThreshold());
        if (anomaly < threshold) return Collections.emptyList();

        double reliability = reliability(snapshot, loaded, activitySamples);
        Map<String, Double> evidence = new LinkedHashMap<>();
        evidence.put("anomaly_score", anomaly);
        evidence.put("normality_threshold", threshold);
        evidence.put("training_samples", (double) loaded.getTrainingSamples());
        evidence.put("training_players", (double) loaded.getTrainingPlayers());
        evidence.put("training_sessions", (double) loaded.getTrainingSessions());
        evidence.put("activity_samples", (double) activitySamples);
        evidence.put("ping_ms", (double) snapshot.getPingMs());
        evidence.put("tps", snapshot.getTps());
        if (Double.isFinite(loaded.getValidationP99())) evidence.put("validation_normal_p99", loaded.getValidationP99());
        if (Double.isFinite(loaded.getValidationP999())) evidence.put("validation_normal_p999", loaded.getValidationP999());

        String summary = String.format(
                "Behavior is an outlier against learned trusted-player population normality: %.1f%% (review floor %.1f%%).",
                anomaly * 100.0,
                threshold * 100.0
        );
        return Collections.singletonList(new DetectionFinding(
                id(), DetectionCategory.BEHAVIOR, anomaly, reliability, summary, evidence
        ));
    }

    private double reliability(BehaviorSnapshot snapshot,
                               IsolationForestModel loaded,
                               int activitySamples) {
        double populationMaturity = clamp(loaded.getTrainingPlayers() / (double) targetTrainingPlayers, 0.15, 1.0);
        double sampleFactor = 0.70 + 0.30 * clamp(
                activitySamples / (double) Math.max(1, minimumActivitySamples * 2), 0.0, 1.0);
        double pingFactor = 1.0 - 0.35 * clamp(snapshot.getPingMs() / (double) maxPingMs, 0.0, 1.0);
        double tpsRange = Math.max(0.25, 20.0 - minimumTps);
        double tpsFactor = 0.70 + 0.30 * clamp((snapshot.getTps() - minimumTps) / tpsRange, 0.0, 1.0);
        return clamp(baseReliability * populationMaturity * sampleFactor * pingFactor * tpsFactor, 0.0, 1.0);
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) return min;
        return Math.max(min, Math.min(max, value));
    }
}
