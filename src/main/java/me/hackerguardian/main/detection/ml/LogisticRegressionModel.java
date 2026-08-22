package me.hackerguardian.main.detection.ml;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small, dependency-free supervised ML model used as HackerGuardian's first
 * measurable baseline.
 *
 * The weights are learned offline from labeled FeatureSchemaV1 samples. The
 * runtime only performs deterministic normalization + logistic inference.
 */
public final class LogisticRegressionModel {

    private final String modelId;
    private final String schemaId;
    private final double decisionThreshold;
    private final double[] means;
    private final double[] scales;
    private final double[] weights;
    private final double bias;

    private final int trainingSamples;
    private final int positiveSamples;
    private final String trainedAt;
    private final double validationAccuracy;
    private final double validationPrecision;
    private final double validationRecall;
    private final double validationF1;
    private final double validationAuc;

    public LogisticRegressionModel(String modelId,
                                   String schemaId,
                                   double decisionThreshold,
                                   double[] means,
                                   double[] scales,
                                   double[] weights,
                                   double bias,
                                   int trainingSamples,
                                   int positiveSamples,
                                   String trainedAt,
                                   double validationAccuracy,
                                   double validationPrecision,
                                   double validationRecall,
                                   double validationF1,
                                   double validationAuc) {
        if (modelId == null || modelId.isBlank()) throw new IllegalArgumentException("modelId is required");
        if (schemaId == null || schemaId.isBlank()) throw new IllegalArgumentException("schemaId is required");
        if (!Double.isFinite(decisionThreshold) || decisionThreshold <= 0.0 || decisionThreshold >= 1.0) {
            throw new IllegalArgumentException("decisionThreshold must be between 0 and 1");
        }
        if (means == null || scales == null || weights == null
                || means.length == 0 || means.length != scales.length || means.length != weights.length) {
            throw new IllegalArgumentException("model vectors must have matching non-zero lengths");
        }
        if (!Double.isFinite(bias)) throw new IllegalArgumentException("model bias must be finite");

        this.modelId = modelId;
        this.schemaId = schemaId;
        this.decisionThreshold = decisionThreshold;
        this.means = Arrays.copyOf(means, means.length);
        this.scales = Arrays.copyOf(scales, scales.length);
        this.weights = Arrays.copyOf(weights, weights.length);
        this.bias = bias;
        this.trainingSamples = Math.max(0, trainingSamples);
        this.positiveSamples = Math.max(0, positiveSamples);
        this.trainedAt = trainedAt == null ? "" : trainedAt;
        this.validationAccuracy = validationAccuracy;
        this.validationPrecision = validationPrecision;
        this.validationRecall = validationRecall;
        this.validationF1 = validationF1;
        this.validationAuc = validationAuc;

        for (int i = 0; i < this.means.length; i++) {
            if (!Double.isFinite(this.means[i]) || !Double.isFinite(this.scales[i])
                    || !Double.isFinite(this.weights[i])) {
                throw new IllegalArgumentException("model vectors may only contain finite values");
            }
            if (this.scales[i] <= 1.0E-12) {
                throw new IllegalArgumentException("normalization scales must be greater than zero");
            }
        }
    }

    public String getModelId() { return modelId; }
    public String getSchemaId() { return schemaId; }
    public double getDecisionThreshold() { return decisionThreshold; }
    public int getFeatureCount() { return weights.length; }
    public int getTrainingSamples() { return trainingSamples; }
    public int getPositiveSamples() { return positiveSamples; }
    public String getTrainedAt() { return trainedAt; }
    public double getValidationAccuracy() { return validationAccuracy; }
    public double getValidationPrecision() { return validationPrecision; }
    public double getValidationRecall() { return validationRecall; }
    public double getValidationF1() { return validationF1; }
    public double getValidationAuc() { return validationAuc; }

    public double predictProbability(FeatureVector vector) {
        return sigmoid(predictLogit(vector));
    }

    public boolean predictsPositive(FeatureVector vector) {
        return predictProbability(vector) >= decisionThreshold;
    }

    public Map<String, Double> strongestContributions(FeatureVector vector, int limit) {
        validateVector(vector);
        int cappedLimit = Math.max(0, Math.min(limit, weights.length));
        if (cappedLimit == 0) return Map.of();

        List<Contribution> contributions = new ArrayList<>(weights.length);
        for (int i = 0; i < weights.length; i++) {
            double normalized = normalizedValue(vector.get(i), i);
            contributions.add(new Contribution(i, weights[i] * normalized));
        }
        contributions.sort(Comparator.comparingDouble((Contribution c) -> Math.abs(c.value)).reversed());

        Map<String, Double> result = new LinkedHashMap<>();
        for (int i = 0; i < cappedLimit; i++) {
            Contribution contribution = contributions.get(i);
            String name = contribution.index < FeatureSchemaV1.featureNames().size()
                    ? FeatureSchemaV1.featureNames().get(contribution.index)
                    : "feature_" + contribution.index;
            result.put(name, contribution.value);
        }
        return result;
    }

    private double predictLogit(FeatureVector vector) {
        validateVector(vector);
        double logit = bias;
        for (int i = 0; i < weights.length; i++) {
            logit += weights[i] * normalizedValue(vector.get(i), i);
        }
        return logit;
    }

    private double normalizedValue(double raw, int index) {
        // Training uses the same clamp after standardization. It prevents one
        // extreme runtime value from dominating the whole linear model.
        double normalized = (raw - means[index]) / scales[index];
        return Math.max(-8.0, Math.min(8.0, normalized));
    }

    private void validateVector(FeatureVector vector) {
        if (vector == null) throw new IllegalArgumentException("feature vector is required");
        if (!schemaId.equals(vector.getSchemaId())) {
            throw new IllegalArgumentException("Model schema " + schemaId
                    + " is incompatible with vector schema " + vector.getSchemaId());
        }
        if (vector.size() != weights.length) {
            throw new IllegalArgumentException("Expected " + weights.length
                    + " features but received " + vector.size());
        }
    }

    private static double sigmoid(double value) {
        if (value >= 0.0) {
            double exp = Math.exp(-value);
            return 1.0 / (1.0 + exp);
        }
        double exp = Math.exp(value);
        return exp / (1.0 + exp);
    }

    private static final class Contribution {
        final int index;
        final double value;

        Contribution(int index, double value) {
            this.index = index;
            this.value = value;
        }
    }
}
