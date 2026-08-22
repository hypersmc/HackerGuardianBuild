package me.hackerguardian.main.detection.ml;

import java.util.Arrays;

/**
 * Immutable ordered feature vector consumed by an ML model.
 *
 * The schema id is part of the value on purpose: a model must never silently
 * evaluate a vector from a different feature order/version.
 */
public final class FeatureVector {

    private final String schemaId;
    private final double[] values;

    FeatureVector(String schemaId, double[] values) {
        if (schemaId == null || schemaId.isBlank()) {
            throw new IllegalArgumentException("schemaId is required");
        }
        if (values == null || values.length == 0) {
            throw new IllegalArgumentException("feature values are required");
        }

        this.schemaId = schemaId;
        this.values = Arrays.copyOf(values, values.length);
        for (double value : this.values) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Feature vectors may only contain finite values");
            }
        }
    }

    public String getSchemaId() {
        return schemaId;
    }

    public int size() {
        return values.length;
    }

    public double get(int index) {
        return values[index];
    }

    public double[] copyValues() {
        return Arrays.copyOf(values, values.length);
    }
}
