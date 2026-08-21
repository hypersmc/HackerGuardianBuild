package me.hackerguardian.main.detection.ml;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MlModelLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsValidArtifactAndRejectsFeatureOrderMismatch() throws Exception {
        Path valid = tempDir.resolve("valid.hgml");
        Files.writeString(valid, artifact(FeatureSchemaV1.featureNamesCsv()));

        LogisticRegressionModel model = MlModelLoader.load(valid.toFile());
        assertEquals("loader-test-v1", model.getModelId());
        assertEquals(FeatureSchemaV1.ID, model.getSchemaId());
        assertEquals(FeatureSchemaV1.FEATURE_COUNT, model.getFeatureCount());
        assertEquals(0.8, model.getDecisionThreshold(), 1.0e-9);

        String[] reordered = FeatureSchemaV1.featureNames().toArray(new String[0]);
        String first = reordered[0];
        reordered[0] = reordered[1];
        reordered[1] = first;

        Path invalid = tempDir.resolve("invalid.hgml");
        Files.writeString(invalid, artifact(String.join(",", reordered)));
        assertThrows(IOException.class, () -> MlModelLoader.load(invalid.toFile()));
    }

    private static String artifact(String featureNames) {
        double[] zeros = new double[FeatureSchemaV1.FEATURE_COUNT];
        double[] ones = new double[FeatureSchemaV1.FEATURE_COUNT];
        Arrays.fill(ones, 1.0);

        return "artifact.version=1\n"
                + "model.type=logistic-regression\n"
                + "model.id=loader-test-v1\n"
                + "schema.id=" + FeatureSchemaV1.ID + "\n"
                + "features.names=" + featureNames + "\n"
                + "normalization.mean=" + csv(zeros) + "\n"
                + "normalization.scale=" + csv(ones) + "\n"
                + "model.weights=" + csv(zeros) + "\n"
                + "model.bias=0\n"
                + "decision.threshold=0.8\n"
                + "training.samples=100\n"
                + "training.positive_samples=50\n"
                + "training.created_at=2026-08-21T00:00:00Z\n"
                + "metrics.validation_accuracy=0.9\n"
                + "metrics.validation_precision=0.95\n"
                + "metrics.validation_recall=0.8\n"
                + "metrics.validation_f1=0.87\n"
                + "metrics.validation_auc=0.96\n";
    }

    private static String csv(double[] values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) out.append(',');
            out.append(values[i]);
        }
        return out.toString();
    }
}
