package me.hackerguardian.main.detection.probe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProbeResultRecorderTest {

    @TempDir
    Path tempDir;

    @Test
    void headerAndRowsStaySchemaCompatible() throws Exception {
        Path file = tempDir.resolve("probe-results-v1.csv");
        ProbeResultRecorder recorder = new ProbeResultRecorder(
                file.toFile(), Logger.getAnonymousLogger(), 64);

        BehaviorProbeEngine.ProbeResult result = new BehaviorProbeEngine.ProbeResult(
                UUID.fromString("019c0000-0000-7000-8000-000000000001"),
                UUID.fromString("019c0000-0000-7000-8000-000000000002"),
                "PlayerOne",
                UUID.fromString("019c0000-0000-7000-8000-000000000003"),
                UUID.fromString("019c0000-0000-7000-8000-000000000004"),
                "P123456789abc",
                1_000L,
                3_000L,
                "TIMEOUT",
                "world",
                9_000L,
                80.0,
                4.0,
                120L,
                260L,
                300L,
                -1L,
                4.5,
                720.0,
                42,
                19.9,
                "UUID_ALLOWLIST",
                false
        );

        recorder.record(result);
        recorder.shutdown();

        List<String> lines = Files.readAllLines(file);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("trust_source,forced"));
        assertEquals(lines.get(0).split(",", -1).length, lines.get(1).split(",", -1).length);
        assertTrue(lines.get(1).contains("UUID_ALLOWLIST"));
    }
}
