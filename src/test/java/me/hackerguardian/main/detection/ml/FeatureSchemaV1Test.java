package me.hackerguardian.main.detection.ml;

import me.hackerguardian.main.detection.telemetry.BehaviorSnapshot;
import org.bukkit.GameMode;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeatureSchemaV1Test {

    @Test
    void extractsStableOrderedFiniteFeatureVector() {
        BehaviorSnapshot snapshot = BehaviorSnapshot.builder(
                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        "TestPlayer",
                        1_700_000_000_000L,
                        5_000L
                )
                .worldName("world")
                .movement(100, 4.0, 7.0, 0.35, 5.0, 2.0, 3.0, 1.0, 0.8)
                .combat(50, 10.0, 20, 0.4, 2.8, 3.5)
                .blocks(10, 4.1, 15, 4.4)
                .context(
                        75,
                        19.9,
                        GameMode.SURVIVAL,
                        true,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false
                )
                .build();

        FeatureVector vector = FeatureSchemaV1.extract(snapshot);

        assertEquals(FeatureSchemaV1.ID, vector.getSchemaId());
        assertEquals(33, vector.size());
        assertEquals(FeatureSchemaV1.FEATURE_COUNT, FeatureSchemaV1.featureNames().size());

        assertEquals(20.0, vector.get(0), 1.0e-9); // 100 movement samples / 5s
        assertEquals(10.0, vector.get(9), 1.0e-9); // swing CPS
        assertEquals(4.0, vector.get(11), 1.0e-9); // 20 hits / 5s
        assertEquals(2.0, vector.get(14), 1.0e-9); // 10 breaks / 5s
        assertEquals(3.0, vector.get(16), 1.0e-9); // 15 places / 5s
        assertEquals(1.0, vector.get(20), 1.0e-9); // sprinting
        assertEquals(1.0, vector.get(29), 1.0e-9); // survival one-hot
        assertEquals(0.0, vector.get(30), 1.0e-9);
        assertEquals(0.0, vector.get(31), 1.0e-9);
        assertEquals(0.0, vector.get(32), 1.0e-9);

        for (double value : vector.copyValues()) {
            assertTrue(Double.isFinite(value));
        }
    }
}
