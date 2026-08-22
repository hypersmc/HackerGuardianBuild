package me.hackerguardian.main.detection.normality;

import me.hackerguardian.main.detection.ml.FeatureSchemaV1;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IsolationForestModelLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsStrictHgifArtifact() throws Exception {
        Path artifact = tempDir.resolve("normality.hgif");
        Files.writeString(artifact, validArtifact("S,0,5.0,1,2"));

        IsolationForestModel model = IsolationForestModelLoader.load(artifact.toFile());
        assertEquals("test-normality", model.getModelId());
        assertEquals(1, model.getTreeCount());
        assertEquals(5, model.getTrainingPlayers());
        assertEquals(0.62, model.getDecisionThreshold(), 1.0e-9);
        assertTrue(Double.isFinite(model.getValidationP99()));
    }

    @Test
    void rejectsCyclicOrSelfReferencingTrees() throws Exception {
        Path artifact = tempDir.resolve("bad.hgif");
        Files.writeString(artifact, validArtifact("S,0,5.0,0,2"));
        assertThrows(IOException.class, () -> IsolationForestModelLoader.load(artifact.toFile()));
    }

    private static String validArtifact(String rootNode) {
        return "artifact.version=1\n"
                + "model.type=isolation-forest\n"
                + "model.id=test-normality\n"
                + "schema.id=" + FeatureSchemaV1.ID + "\n"
                + "features.names=" + FeatureSchemaV1.featureNamesCsv() + "\n"
                + "tree.count=1\n"
                + "sample.size=2\n"
                + "training.samples=100\n"
                + "training.players=5\n"
                + "training.sessions=10\n"
                + "decision.threshold=0.62\n"
                + "metrics.validation_normal_mean=0.45\n"
                + "metrics.validation_normal_p95=0.54\n"
                + "metrics.validation_normal_p99=0.59\n"
                + "metrics.validation_normal_p999=0.61\n"
                + "tree.0.node_count=3\n"
                + "tree.0.node.0=" + rootNode + "\n"
                + "tree.0.node.1=L,1\n"
                + "tree.0.node.2=L,1\n";
    }
}
