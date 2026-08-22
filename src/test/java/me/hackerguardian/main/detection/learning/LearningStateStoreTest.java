package me.hackerguardian.main.detection.learning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LearningStateStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsCollectedTimeAndGeneratesEligibilityManifest() throws Exception {
        Path stateFile = tempDir.resolve("learning-state.properties");
        Path manifestFile = tempDir.resolve("trust-manifest-v1.csv");
        long oneHour = 3_600_000L;

        UUID matureId = UUID.fromString("019c0000-0000-7000-8000-000000000001");
        LearningPlayerState mature = new LearningPlayerState(matureId);
        mature.markSeen("MaturePlayer", true, 1_000L);
        mature.addCollected(oneHour * 2L);
        mature.markProbe(5_000L);

        UUID newId = UUID.fromString("019c0000-0000-7000-8000-000000000002");
        LearningPlayerState newer = new LearningPlayerState(newId);
        newer.markSeen("NewPlayer", true, 2_000L);
        newer.addCollected(oneHour / 2L);

        LearningStateStore store = new LearningStateStore(
                stateFile, manifestFile, Logger.getAnonymousLogger(), oneHour);
        store.saveNow(List.of(mature, newer));
        store.shutdown();

        assertTrue(Files.isRegularFile(stateFile));
        assertTrue(Files.isRegularFile(manifestFile));

        LearningStateStore reloadedStore = new LearningStateStore(
                stateFile, manifestFile, Logger.getAnonymousLogger(), oneHour);
        Map<UUID, LearningPlayerState> loaded = reloadedStore.load();
        reloadedStore.shutdown();

        LearningPlayerState.Snapshot matureSnapshot = loaded.get(matureId).snapshot();
        assertNotNull(matureSnapshot);
        assertEquals(oneHour * 2L, matureSnapshot.getCollectedMs());
        assertEquals(5_000L, matureSnapshot.getLastProbeMs());
        assertTrue(matureSnapshot.isTrustedLastSeen());

        LearningPlayerState.Snapshot newerSnapshot = loaded.get(newId).snapshot();
        assertNotNull(newerSnapshot);
        assertEquals(oneHour / 2L, newerSnapshot.getCollectedMs());

        String manifest = Files.readString(manifestFile);
        String matureLine = manifest.lines()
                .filter(line -> line.contains(matureId.toString()))
                .findFirst().orElseThrow();
        String newerLine = manifest.lines()
                .filter(line -> line.contains(newId.toString()))
                .findFirst().orElseThrow();

        assertTrue(matureLine.contains(",true,"));
        assertTrue(matureLine.endsWith(",5000"));
        assertFalse(newerLine.contains(",true,5000"));
        // The final boolean before last_probe_ms is baseline_eligible.
        assertTrue(matureLine.matches(".*,[Tt]rue,5000$"));
        assertTrue(newerLine.matches(".*,[Ff]alse,0$"));
    }
}
