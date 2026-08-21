package me.hackerguardian.main.detection.ml;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogisticRegressionModelTest {

    @Test
    void performsDeterministicSchemaCheckedInference() {
        double[] means = new double[FeatureSchemaV1.FEATURE_COUNT];
        double[] scales = new double[FeatureSchemaV1.FEATURE_COUNT];
        double[] weights = new double[FeatureSchemaV1.FEATURE_COUNT];
        for (int i = 0; i < scales.length; i++) scales[i] = 1.0;
        weights[0] = 2.0;
        weights[9] = 0.5;

        LogisticRegressionModel model = new LogisticRegressionModel(
                "unit-logreg-v1",
                FeatureSchemaV1.ID,
                0.75,
                means,
                scales,
                weights,
                -1.0,
                100,
                50,
                "2026-08-21T00:00:00Z",
                0.9,
                0.95,
                0.8,
                0.87,
                0.96
        );

        double[] benign = new double[FeatureSchemaV1.FEATURE_COUNT];
        double[] suspicious = new double[FeatureSchemaV1.FEATURE_COUNT];
        suspicious[0] = 2.0;
        suspicious[9] = 1.0;

        double benignProbability = model.predictProbability(new FeatureVector(FeatureSchemaV1.ID, benign));
        FeatureVector suspiciousVector = new FeatureVector(FeatureSchemaV1.ID, suspicious);
        double suspiciousProbability = model.predictProbability(suspiciousVector);

        assertTrue(benignProbability < 0.5);
        assertTrue(suspiciousProbability > 0.95);
        assertTrue(model.predictsPositive(suspiciousVector));

        Map<String, Double> contributions = model.strongestContributions(suspiciousVector, 2);
        assertEquals(2, contributions.size());
        assertTrue(contributions.containsKey("movement_samples_per_second"));
        assertTrue(contributions.get("movement_samples_per_second") > 0.0);

        assertThrows(
                IllegalArgumentException.class,
                () -> model.predictProbability(new FeatureVector("behavior-v999", suspicious))
        );
    }
}
