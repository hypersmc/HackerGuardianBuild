package me.hackerguardian.main.detection.normality;

import me.hackerguardian.main.detection.ml.FeatureSchemaV1;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/** Strict loader for HackerGuardian Isolation Forest (HGIF) v1 artifacts. */
public final class IsolationForestModelLoader {

    public static final String ARTIFACT_VERSION = "1";
    public static final String MODEL_TYPE = "isolation-forest";

    private IsolationForestModelLoader() {}

    public static IsolationForestModel load(File file) throws IOException {
        if (file == null || !file.isFile()) {
            throw new IOException("Normality model file does not exist: " + (file == null ? "null" : file.getPath()));
        }

        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            p.load(reader);
        }

        requireEquals(p, "artifact.version", ARTIFACT_VERSION);
        requireEquals(p, "model.type", MODEL_TYPE);
        requireEquals(p, "schema.id", FeatureSchemaV1.ID);

        String modelId = required(p, "model.id");
        List<String> features = Arrays.asList(required(p, "features.names").split(",", -1));
        if (!features.equals(FeatureSchemaV1.featureNames())) {
            throw new IOException("Normality artifact feature order does not match " + FeatureSchemaV1.ID);
        }

        int treeCount = parseInt(p, "tree.count", 1, 512);
        int sampleSize = parseInt(p, "sample.size", 2, 1_000_000);
        long trainingSamples = parseLong(p, "training.samples", 1L, Long.MAX_VALUE);
        int trainingPlayers = parseInt(p, "training.players", 1, 10_000_000);
        int trainingSessions = parseInt(p, "training.sessions", 1, 100_000_000);
        double threshold = parseDouble(p, "decision.threshold", 0.0, 1.0);
        double validationMean = optionalDouble(p, "metrics.validation_normal_mean");
        double validationP95 = optionalDouble(p, "metrics.validation_normal_p95");
        double validationP99 = optionalDouble(p, "metrics.validation_normal_p99");
        double validationP999 = optionalDouble(p, "metrics.validation_normal_p999");

        List<IsolationForestModel.Tree> trees = new ArrayList<>(treeCount);
        for (int treeIndex = 0; treeIndex < treeCount; treeIndex++) {
            int nodeCount = parseInt(p, "tree." + treeIndex + ".node_count", 1, 100_000);
            IsolationForestModel.Node[] nodes = new IsolationForestModel.Node[nodeCount];
            for (int nodeIndex = 0; nodeIndex < nodeCount; nodeIndex++) {
                String key = "tree." + treeIndex + ".node." + nodeIndex;
                String raw = required(p, key);
                String[] parts = raw.split(",", -1);
                if (parts.length == 2 && parts[0].equals("L")) {
                    int samples = parseInt(parts[1], key + " leaf sample count", 1, 1_000_000);
                    nodes[nodeIndex] = IsolationForestModel.Node.leaf(samples);
                } else if (parts.length == 5 && parts[0].equals("S")) {
                    int feature = parseInt(parts[1], key + " feature", 0, FeatureSchemaV1.FEATURE_COUNT - 1);
                    double split = parseFinite(parts[2], key + " threshold");
                    int left = parseInt(parts[3], key + " left", 0, nodeCount - 1);
                    int right = parseInt(parts[4], key + " right", 0, nodeCount - 1);
                    if (left == nodeIndex || right == nodeIndex || left == right) {
                        throw new IOException(key + " contains an invalid child reference");
                    }
                    nodes[nodeIndex] = IsolationForestModel.Node.split(feature, split, left, right);
                } else {
                    throw new IOException("Malformed " + key + ": " + raw);
                }
            }
            validateTree(nodes, treeIndex);
            trees.add(new IsolationForestModel.Tree(nodes));
        }

        return new IsolationForestModel(
                modelId,
                FeatureSchemaV1.ID,
                FeatureSchemaV1.featureNames(),
                sampleSize,
                trainingSamples,
                trainingPlayers,
                trainingSessions,
                threshold,
                validationMean,
                validationP95,
                validationP99,
                validationP999,
                trees
        );
    }

    private static void validateTree(IsolationForestModel.Node[] nodes, int treeIndex) throws IOException {
        byte[] state = new byte[nodes.length];
        visit(nodes, 0, state, treeIndex);
        for (int i = 0; i < state.length; i++) {
            if (state[i] == 0) {
                throw new IOException("Isolation tree " + treeIndex + " contains unreachable node " + i);
            }
        }
    }

    private static void visit(IsolationForestModel.Node[] nodes,
                              int index,
                              byte[] state,
                              int treeIndex) throws IOException {
        if (state[index] == 1) throw new IOException("Isolation tree " + treeIndex + " contains a cycle at node " + index);
        if (state[index] == 2) return;
        state[index] = 1;
        IsolationForestModel.Node node = nodes[index];
        if (!node.leaf) {
            visit(nodes, node.leftIndex, state, treeIndex);
            visit(nodes, node.rightIndex, state, treeIndex);
        }
        state[index] = 2;
    }

    private static void requireEquals(Properties p, String key, String expected) throws IOException {
        String actual = required(p, key);
        if (!expected.equals(actual)) throw new IOException(key + " must be '" + expected + "', got '" + actual + "'");
    }

    private static String required(Properties p, String key) throws IOException {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) throw new IOException("Missing normality model property: " + key);
        return value.trim();
    }

    private static int parseInt(Properties p, String key, int min, int max) throws IOException {
        return parseInt(required(p, key), key, min, max);
    }

    private static int parseInt(String raw, String label, int min, int max) throws IOException {
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < min || value > max) throw new IOException(label + " is out of range: " + value);
            return value;
        } catch (NumberFormatException e) {
            throw new IOException(label + " is not an integer: " + raw, e);
        }
    }

    private static long parseLong(Properties p, String key, long min, long max) throws IOException {
        String raw = required(p, key);
        try {
            long value = Long.parseLong(raw);
            if (value < min || value > max) throw new IOException(key + " is out of range: " + value);
            return value;
        } catch (NumberFormatException e) {
            throw new IOException(key + " is not an integer: " + raw, e);
        }
    }

    private static double parseDouble(Properties p, String key, double min, double max) throws IOException {
        double value = parseFinite(required(p, key), key);
        if (value < min || value > max) throw new IOException(key + " is out of range: " + value);
        return value;
    }

    private static double optionalDouble(Properties p, String key) throws IOException {
        String raw = p.getProperty(key);
        return raw == null || raw.isBlank() ? Double.NaN : parseFinite(raw, key);
    }

    private static double parseFinite(String raw, String label) throws IOException {
        try {
            double value = Double.parseDouble(raw.trim());
            if (!Double.isFinite(value)) throw new IOException(label + " must be finite");
            return value;
        } catch (NumberFormatException e) {
            throw new IOException(label + " is not numeric: " + raw, e);
        }
    }
}
