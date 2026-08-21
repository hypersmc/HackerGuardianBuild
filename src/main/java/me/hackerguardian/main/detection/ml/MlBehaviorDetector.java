package me.hackerguardian.main.detection.ml;

import me.hackerguardian.main.detection.DetectionCategory;
import me.hackerguardian.main.detection.DetectionFinding;
import me.hackerguardian.main.detection.Detector;
import me.hackerguardian.main.detection.telemetry.BehaviorSnapshot;
import org.bukkit.GameMode;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Detection-v2 adapter around a learned HGML model.
 *
 * It remains evidence-only: model output becomes a DetectionFinding and never
 * calls moderation, replay, warning or punishment code directly.
 */
public final class MlBehaviorDetector implements Detector {

    public static final String DETECTOR_ID = "ml-behavior-v1";

    private final Logger logger;
    private final File modelFile;
    private final int minimumActivitySamples;
    private final int maxPingMs;
    private final double minimumTps;
    private final double findingFloor;
    private final double baseReliability;
    private final AtomicReference<LogisticRegressionModel> model = new AtomicReference<>();

    private volatile String lastLoadError = "Model has not been loaded yet";

    public MlBehaviorDetector(Logger logger,
                              File modelFile,
                              int minimumActivitySamples,
                              int maxPingMs,
                              double minimumTps,
                              double findingFloor,
                              double baseReliability) {
        this.logger = logger;
        this.modelFile = modelFile;
        this.minimumActivitySamples = Math.max(1, minimumActivitySamples);
        this.maxPingMs = Math.max(50, maxPingMs);
        this.minimumTps = clamp(minimumTps, 1.0, 20.0);
        this.findingFloor = clamp(findingFloor, 0.01, 0.99);
        this.baseReliability = clamp(baseReliability, 0.05, 1.0);
    }

    @Override
    public String id() {
        return DETECTOR_ID;
    }

    public synchronized boolean reload() {
        try {
            LogisticRegressionModel loaded = MlModelLoader.load(modelFile);
            model.set(loaded);
            lastLoadError = "";
            if (logger != null) {
                logger.info("Loaded HGML model '" + loaded.getModelId() + "' (schema="
                        + loaded.getSchemaId() + ", threshold="
                        + String.format("%.3f", loaded.getDecisionThreshold()) + ").");
            }
            return true;
        } catch (Exception e) {
            model.set(null);
            lastLoadError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (logger != null) {
                logger.warning("HGML model unavailable: " + lastLoadError);
            }
            return false;
        }
    }

    public boolean isLoaded() {
        return model.get() != null;
    }

    public LogisticRegressionModel getModel() {
        return model.get();
    }

    public File getModelFile() {
        return modelFile;
    }

    public String getLastLoadError() {
        return lastLoadError;
    }

    @Override
    public List<DetectionFinding> analyze(BehaviorSnapshot snapshot) {
        LogisticRegressionModel loaded = model.get();
        if (loaded == null || snapshot == null) return Collections.emptyList();

        GameMode gameMode = snapshot.getGameMode();
        if (gameMode == GameMode.CREATIVE || gameMode == GameMode.SPECTATOR) {
            return Collections.emptyList();
        }

        int activitySamples = snapshot.getMovementSamples()
                + snapshot.getSwingCount()
                + snapshot.getHitCount()
                + snapshot.getBlocksBroken()
                + snapshot.getBlocksPlaced();
        if (activitySamples < minimumActivitySamples) return Collections.emptyList();
        if (snapshot.getPingMs() > maxPingMs) return Collections.emptyList();
        if (snapshot.getTps() < minimumTps) return Collections.emptyList();

        FeatureVector vector = FeatureSchemaV1.extract(snapshot);
        double probability = loaded.predictProbability(vector);
        if (probability < findingFloor) return Collections.emptyList();

        double reliability = reliability(snapshot, loaded, activitySamples);
        Map<String, Double> evidence = new LinkedHashMap<>();
        evidence.put("ml_probability", probability);
        evidence.put("decision_threshold", loaded.getDecisionThreshold());
        evidence.put("activity_samples", (double) activitySamples);
        evidence.put("ping_ms", (double) snapshot.getPingMs());
        evidence.put("tps", snapshot.getTps());
        if (Double.isFinite(loaded.getValidationAuc())) {
            evidence.put("validation_auc", loaded.getValidationAuc());
        }
        for (Map.Entry<String, Double> contribution : loaded.strongestContributions(vector, 3).entrySet()) {
            evidence.put("contribution." + contribution.getKey(), contribution.getValue());
        }

        boolean crossedThreshold = probability >= loaded.getDecisionThreshold();
        String summary = String.format(
                "%s model %s scored %.1f%% (review threshold %.1f%%).",
                crossedThreshold ? "ML behavior" : "Elevated ML behavior",
                loaded.getModelId(),
                probability * 100.0,
                loaded.getDecisionThreshold() * 100.0
        );

        return Collections.singletonList(new DetectionFinding(
                id(),
                DetectionCategory.BEHAVIOR,
                probability,
                reliability,
                summary,
                evidence
        ));
    }

    private double reliability(BehaviorSnapshot snapshot,
                               LogisticRegressionModel loaded,
                               int activitySamples) {
        double modelQuality = Double.isFinite(loaded.getValidationAuc())
                ? clamp(loaded.getValidationAuc(), 0.50, 1.0)
                : 0.65;

        double sampleFactor = 0.70 + (0.30 * clamp(
                activitySamples / (double) Math.max(minimumActivitySamples * 2, 1), 0.0, 1.0));
        double pingFactor = 1.0 - (0.35 * clamp(snapshot.getPingMs() / (double) maxPingMs, 0.0, 1.0));

        double tpsRange = Math.max(0.25, 20.0 - minimumTps);
        double tpsFactor = 0.70 + (0.30 * clamp((snapshot.getTps() - minimumTps) / tpsRange, 0.0, 1.0));

        return clamp(baseReliability * modelQuality * sampleFactor * pingFactor * tpsFactor, 0.0, 1.0);
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) return min;
        return Math.max(min, Math.min(max, value));
    }
}
