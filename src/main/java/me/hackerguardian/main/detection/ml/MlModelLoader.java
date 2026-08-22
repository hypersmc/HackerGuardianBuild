package me.hackerguardian.main.detection.ml;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Properties;

/** Strict loader for the HGML v1 properties artifact. */
public final class MlModelLoader {

    public static final String ARTIFACT_VERSION = "1";
    public static final String MODEL_TYPE = "logistic-regression";

    private MlModelLoader() {}

    public static LogisticRegressionModel load(File file) throws IOException {
        if (file == null) throw new IllegalArgumentException("model file is required");
        if (!file.isFile()) throw new IOException("Model file does not exist: " + file.getPath());

        Properties props = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            props.load(reader);
        }

        String artifactVersion = required(props, "artifact.version");
        if (!ARTIFACT_VERSION.equals(artifactVersion)) {
            throw new IOException("Unsupported HGML artifact version: " + artifactVersion);
        }

        String modelType = required(props, "model.type");
        if (!MODEL_TYPE.equalsIgnoreCase(modelType)) {
            throw new IOException("Unsupported model type: " + modelType);
        }

        String schemaId = required(props, "schema.id");
        if (!FeatureSchemaV1.ID.equals(schemaId)) {
            throw new IOException("Model requires feature schema " + schemaId
                    + " but this build provides " + FeatureSchemaV1.ID);
        }

        validateFeatureOrder(required(props, "features.names"));

        double[] means = parseArray(required(props, "normalization.mean"), "normalization.mean");
        double[] scales = parseArray(required(props, "normalization.scale"), "normalization.scale");
        double[] weights = parseArray(required(props, "model.weights"), "model.weights");

        if (means.length != FeatureSchemaV1.FEATURE_COUNT
                || scales.length != FeatureSchemaV1.FEATURE_COUNT
                || weights.length != FeatureSchemaV1.FEATURE_COUNT) {
            throw new IOException("HGML model vector length does not match schema feature count "
                    + FeatureSchemaV1.FEATURE_COUNT);
        }

        for (double scale : scales) {
            if (scale <= 1.0E-12) throw new IOException("normalization.scale values must be greater than zero");
        }

        double threshold = requiredDouble(props, "decision.threshold");
        if (threshold <= 0.0 || threshold >= 1.0) {
            throw new IOException("decision.threshold must be between 0 and 1");
        }

        return new LogisticRegressionModel(
                required(props, "model.id"),
                schemaId,
                threshold,
                means,
                scales,
                weights,
                requiredDouble(props, "model.bias"),
                optionalInt(props, "training.samples", 0),
                optionalInt(props, "training.positive_samples", 0),
                props.getProperty("training.created_at", "").trim(),
                optionalDouble(props, "metrics.validation_accuracy"),
                optionalDouble(props, "metrics.validation_precision"),
                optionalDouble(props, "metrics.validation_recall"),
                optionalDouble(props, "metrics.validation_f1"),
                optionalDouble(props, "metrics.validation_auc")
        );
    }

    private static void validateFeatureOrder(String raw) throws IOException {
        String[] names = raw.split(",", -1);
        List<String> expected = FeatureSchemaV1.featureNames();
        if (names.length != expected.size()) {
            throw new IOException("features.names contains " + names.length
                    + " entries; expected " + expected.size());
        }
        for (int i = 0; i < names.length; i++) {
            if (!expected.get(i).equals(names[i].trim())) {
                throw new IOException("Feature order mismatch at index " + i + ": expected '"
                        + expected.get(i) + "' but model contains '" + names[i].trim() + "'");
            }
        }
    }

    private static String required(Properties props, String key) throws IOException {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IOException("Missing required HGML property: " + key);
        }
        return value.trim();
    }

    private static double requiredDouble(Properties props, String key) throws IOException {
        return parseFiniteDouble(required(props, key), key);
    }

    private static double optionalDouble(Properties props, String key) throws IOException {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) return Double.NaN;
        return parseFiniteDouble(value.trim(), key);
    }

    private static int optionalInt(Properties props, String key, int fallback) throws IOException {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) return fallback;
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 0) throw new NumberFormatException("negative value");
            return parsed;
        } catch (NumberFormatException e) {
            throw new IOException("Invalid integer for " + key + ": " + value, e);
        }
    }

    private static double parseFiniteDouble(String raw, String key) throws IOException {
        try {
            double value = Double.parseDouble(raw);
            if (!Double.isFinite(value)) throw new NumberFormatException("non-finite value");
            return value;
        } catch (NumberFormatException e) {
            throw new IOException("Invalid number for " + key + ": " + raw, e);
        }
    }

    private static double[] parseArray(String raw, String key) throws IOException {
        String[] parts = raw.split(",", -1);
        double[] values = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = parseFiniteDouble(parts[i].trim(), key + "[" + i + "]");
        }
        return values;
    }
}
