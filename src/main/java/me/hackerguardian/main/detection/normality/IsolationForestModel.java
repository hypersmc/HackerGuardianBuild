package me.hackerguardian.main.detection.normality;

import me.hackerguardian.main.detection.ml.FeatureVector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Dependency-free Java inference for a trained Isolation Forest artifact. */
public final class IsolationForestModel {

    private static final double EULER_GAMMA = 0.5772156649015329;

    private final String modelId;
    private final String schemaId;
    private final List<String> featureNames;
    private final int sampleSize;
    private final long trainingSamples;
    private final int trainingPlayers;
    private final int trainingSessions;
    private final double decisionThreshold;
    private final double validationMean;
    private final double validationP95;
    private final double validationP99;
    private final double validationP999;
    private final List<Tree> trees;

    IsolationForestModel(String modelId,
                         String schemaId,
                         List<String> featureNames,
                         int sampleSize,
                         long trainingSamples,
                         int trainingPlayers,
                         int trainingSessions,
                         double decisionThreshold,
                         double validationMean,
                         double validationP95,
                         double validationP99,
                         double validationP999,
                         List<Tree> trees) {
        this.modelId = modelId;
        this.schemaId = schemaId;
        this.featureNames = Collections.unmodifiableList(new ArrayList<>(featureNames));
        this.sampleSize = sampleSize;
        this.trainingSamples = trainingSamples;
        this.trainingPlayers = trainingPlayers;
        this.trainingSessions = trainingSessions;
        this.decisionThreshold = decisionThreshold;
        this.validationMean = validationMean;
        this.validationP95 = validationP95;
        this.validationP99 = validationP99;
        this.validationP999 = validationP999;
        this.trees = Collections.unmodifiableList(new ArrayList<>(trees));
    }

    public double anomalyScore(FeatureVector vector) {
        if (vector == null) throw new IllegalArgumentException("feature vector is required");
        if (!schemaId.equals(vector.getSchemaId())) {
            throw new IllegalArgumentException("Model schema " + schemaId + " cannot evaluate " + vector.getSchemaId());
        }
        if (vector.size() != featureNames.size()) {
            throw new IllegalArgumentException("Model expects " + featureNames.size() + " features, got " + vector.size());
        }
        if (trees.isEmpty()) return 0.0;

        double pathSum = 0.0;
        for (Tree tree : trees) pathSum += tree.pathLength(vector);
        double averagePath = pathSum / trees.size();
        double normalizer = averagePathLength(sampleSize);
        if (normalizer <= 0.0) return 0.0;
        double score = Math.pow(2.0, -averagePath / normalizer);
        if (!Double.isFinite(score)) return 0.0;
        return Math.max(0.0, Math.min(1.0, score));
    }

    public String getModelId() { return modelId; }
    public String getSchemaId() { return schemaId; }
    public List<String> getFeatureNames() { return featureNames; }
    public int getFeatureCount() { return featureNames.size(); }
    public int getTreeCount() { return trees.size(); }
    public int getSampleSize() { return sampleSize; }
    public long getTrainingSamples() { return trainingSamples; }
    public int getTrainingPlayers() { return trainingPlayers; }
    public int getTrainingSessions() { return trainingSessions; }
    public double getDecisionThreshold() { return decisionThreshold; }
    public double getValidationMean() { return validationMean; }
    public double getValidationP95() { return validationP95; }
    public double getValidationP99() { return validationP99; }
    public double getValidationP999() { return validationP999; }

    static double averagePathLength(int size) {
        if (size <= 1) return 0.0;
        if (size == 2) return 1.0;
        return 2.0 * (Math.log(size - 1.0) + EULER_GAMMA) - (2.0 * (size - 1.0) / size);
    }

    static final class Tree {
        private final Node[] nodes;

        Tree(Node[] nodes) {
            this.nodes = nodes;
        }

        double pathLength(FeatureVector vector) {
            int index = 0;
            int depth = 0;
            for (int guard = 0; guard <= nodes.length; guard++) {
                Node node = nodes[index];
                if (node.leaf) return depth + averagePathLength(node.sampleCount);
                index = vector.get(node.featureIndex) < node.threshold ? node.leftIndex : node.rightIndex;
                depth++;
            }
            throw new IllegalStateException("Isolation tree traversal exceeded node count");
        }
    }

    static final class Node {
        final boolean leaf;
        final int sampleCount;
        final int featureIndex;
        final double threshold;
        final int leftIndex;
        final int rightIndex;

        private Node(boolean leaf, int sampleCount, int featureIndex,
                     double threshold, int leftIndex, int rightIndex) {
            this.leaf = leaf;
            this.sampleCount = sampleCount;
            this.featureIndex = featureIndex;
            this.threshold = threshold;
            this.leftIndex = leftIndex;
            this.rightIndex = rightIndex;
        }

        static Node leaf(int sampleCount) {
            return new Node(true, Math.max(1, sampleCount), -1, 0.0, -1, -1);
        }

        static Node split(int featureIndex, double threshold, int leftIndex, int rightIndex) {
            return new Node(false, 0, featureIndex, threshold, leftIndex, rightIndex);
        }
    }
}
