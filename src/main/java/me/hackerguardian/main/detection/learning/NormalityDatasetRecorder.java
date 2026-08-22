package me.hackerguardian.main.detection.learning;

import me.hackerguardian.main.detection.ml.FeatureSchemaV1;
import me.hackerguardian.main.detection.ml.FeatureVector;
import me.hackerguardian.main.detection.telemetry.BehaviorSnapshot;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * High-volume candidate-normality recorder.
 *
 * Rows are not called LEGIT. They retain trust provenance plus a quarantine
 * timestamp; the offline trainer later combines them with the current trust
 * manifest before deciding which rows are eligible for the population model.
 */
public final class NormalityDatasetRecorder {

    private final Path datasetPath;
    private final Logger logger;
    private final ThreadPoolExecutor writerExecutor;
    private final BufferedWriter output;
    private final AtomicLong droppedRows = new AtomicLong();
    private final AtomicLong writtenRows = new AtomicLong();
    private final AtomicInteger pendingSinceFlush = new AtomicInteger();
    private volatile String lastWriteError = "";

    public NormalityDatasetRecorder(File datasetFile, Logger logger, int queueCapacity) throws IOException {
        if (datasetFile == null) throw new IllegalArgumentException("datasetFile is required");
        this.datasetPath = datasetFile.toPath().toAbsolutePath().normalize();
        this.logger = logger;
        prepareDatasetFile();
        this.output = Files.newBufferedWriter(
                datasetPath,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );

        int capacity = Math.max(256, Math.min(queueCapacity, 200_000));
        this.writerExecutor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "HackerGuardian-Normality-Dataset");
                    thread.setDaemon(true);
                    return thread;
                },
                (runnable, executor) -> droppedRows.incrementAndGet()
        );
    }

    public void record(BehaviorSnapshot snapshot,
                       UUID sessionId,
                       String trustSource,
                       long eligibleAfterMs) {
        if (snapshot == null || sessionId == null) return;
        FeatureVector vector = FeatureSchemaV1.extract(snapshot);
        String row = buildRow(snapshot, vector, sessionId, trustSource, eligibleAfterMs);
        writerExecutor.execute(() -> writeRow(row));
    }

    public File getDatasetFile() { return datasetPath.toFile(); }
    public long getDroppedRows() { return droppedRows.get(); }
    public long getWrittenRows() { return writtenRows.get(); }
    public String getLastWriteError() { return lastWriteError; }

    public void shutdown() {
        writerExecutor.shutdown();
        try {
            if (!writerExecutor.awaitTermination(8, TimeUnit.SECONDS)) {
                writerExecutor.shutdownNow();
                writerExecutor.awaitTermination(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writerExecutor.shutdownNow();
        }
        try {
            output.flush();
            output.close();
        } catch (IOException e) {
            if (logger != null) logger.warning("Failed to close normality dataset: " + e.getMessage());
        }
    }

    private void prepareDatasetFile() throws IOException {
        Path parent = datasetPath.getParent();
        if (parent != null) Files.createDirectories(parent);
        String expectedHeader = header();
        if (Files.exists(datasetPath) && Files.size(datasetPath) > 0L) {
            try (BufferedReader reader = Files.newBufferedReader(datasetPath, StandardCharsets.UTF_8)) {
                String currentHeader = reader.readLine();
                if (!expectedHeader.equals(currentHeader)) {
                    throw new IOException("Existing normality dataset is incompatible with "
                            + FeatureSchemaV1.ID + ": " + datasetPath);
                }
            }
            return;
        }
        Files.writeString(
                datasetPath,
                expectedHeader + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    private void writeRow(String row) {
        try {
            output.write(row);
            output.newLine();
            writtenRows.incrementAndGet();
            if (pendingSinceFlush.incrementAndGet() >= 64) {
                output.flush();
                pendingSinceFlush.set(0);
            }
            lastWriteError = "";
        } catch (IOException e) {
            lastWriteError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (logger != null) logger.warning("Failed to write normality sample: " + lastWriteError);
        }
    }

    private static String header() {
        StringBuilder header = new StringBuilder(
                "schema_id,captured_at_ms,session_id,player_uuid,player_name,trust_source,eligible_after_ms,world,window_ms"
        );
        for (String feature : FeatureSchemaV1.featureNames()) header.append(',').append(feature);
        return header.toString();
    }

    private static String buildRow(BehaviorSnapshot snapshot,
                                   FeatureVector vector,
                                   UUID sessionId,
                                   String trustSource,
                                   long eligibleAfterMs) {
        StringBuilder row = new StringBuilder();
        appendCsv(row, FeatureSchemaV1.ID);
        row.append(',').append(snapshot.getCapturedAtMs()).append(',');
        appendCsv(row, sessionId.toString()); row.append(',');
        appendCsv(row, snapshot.getPlayerId().toString()); row.append(',');
        appendCsv(row, snapshot.getPlayerName()); row.append(',');
        appendCsv(row, trustSource); row.append(',');
        row.append(Math.max(snapshot.getCapturedAtMs(), eligibleAfterMs)).append(',');
        appendCsv(row, snapshot.getWorldName()); row.append(',');
        row.append(snapshot.getWindowMs());
        for (int i = 0; i < vector.size(); i++) row.append(',').append(Double.toString(vector.get(i)));
        return row.toString();
    }

    private static void appendCsv(StringBuilder target, String raw) {
        String value = raw == null ? "" : raw;
        target.append('"').append(value.replace("\"", "\"\"")).append('"');
    }
}
