package me.hackerguardian.main.detection.ml;

import me.hackerguardian.main.detection.telemetry.BehaviorSnapshot;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Explicitly-labeled training sample recorder.
 *
 * It never invents labels from model output or heuristic detections. A capture
 * exists only because an operator deliberately marked a controlled player
 * session LEGIT or CHEAT.
 */
public final class MlDatasetRecorder {

    private final Path datasetPath;
    private final Logger logger;
    private final Map<UUID, CaptureSession> sessions = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor writerExecutor;
    private final BufferedWriter output;
    private final AtomicLong droppedRows = new AtomicLong();
    private final AtomicInteger pendingSinceFlush = new AtomicInteger();

    private volatile String lastWriteError = "";

    public MlDatasetRecorder(File datasetFile, Logger logger, int queueCapacity) throws IOException {
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

        int capacity = Math.max(128, Math.min(queueCapacity, 100_000));
        this.writerExecutor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "HackerGuardian-ML-Dataset");
                    thread.setDaemon(true);
                    return thread;
                },
                (runnable, executor) -> droppedRows.incrementAndGet()
        );
    }

    public CaptureSessionInfo startCapture(UUID playerId,
                                           String playerName,
                                           MlCaptureLabel label,
                                           String operator,
                                           long durationMs) {
        if (playerId == null) throw new IllegalArgumentException("playerId is required");
        if (label == null) throw new IllegalArgumentException("label is required");

        long now = System.currentTimeMillis();
        long safeDuration = Math.max(60_000L, Math.min(durationMs, 2L * 60L * 60L * 1000L));
        CaptureSession session = new CaptureSession(
                UUID.randomUUID().toString(),
                playerId,
                safe(playerName),
                label,
                safe(operator),
                now,
                now + safeDuration
        );
        sessions.put(playerId, session);
        return session.info();
    }

    public boolean stopCapture(UUID playerId) {
        return playerId != null && sessions.remove(playerId) != null;
    }

    public boolean stopCaptureByPlayerName(String playerName) {
        if (playerName == null) return false;
        for (CaptureSession session : sessions.values()) {
            if (session.playerName.equalsIgnoreCase(playerName)) {
                return sessions.remove(session.playerId, session);
            }
        }
        return false;
    }

    public List<CaptureSessionInfo> getActiveCaptures() {
        pruneExpired(System.currentTimeMillis());
        List<CaptureSessionInfo> result = new ArrayList<>();
        for (CaptureSession session : sessions.values()) result.add(session.info());
        result.sort(Comparator.comparing(CaptureSessionInfo::getPlayerName, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    public void record(BehaviorSnapshot snapshot) {
        if (snapshot == null) return;

        long now = System.currentTimeMillis();
        CaptureSession session = sessions.get(snapshot.getPlayerId());
        if (session == null) return;
        if (now >= session.expiresAtMs) {
            sessions.remove(snapshot.getPlayerId(), session);
            return;
        }

        FeatureVector vector = FeatureSchemaV1.extract(snapshot);
        String row = buildRow(snapshot, vector, session);
        writerExecutor.execute(() -> writeRow(row));
    }

    public File getDatasetFile() {
        return datasetPath.toFile();
    }

    public long getDroppedRows() {
        return droppedRows.get();
    }

    public String getLastWriteError() {
        return lastWriteError;
    }

    public void shutdown() {
        sessions.clear();
        writerExecutor.shutdown();
        try {
            if (!writerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
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
            if (logger != null) logger.warning("Failed to close ML dataset file: " + e.getMessage());
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
                    throw new IOException("Existing ML dataset header is incompatible with "
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
            if (pendingSinceFlush.incrementAndGet() >= 16) {
                output.flush();
                pendingSinceFlush.set(0);
            }
            lastWriteError = "";
        } catch (IOException e) {
            lastWriteError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (logger != null) logger.warning("Failed to write ML training sample: " + lastWriteError);
        }
    }

    private static String header() {
        StringBuilder header = new StringBuilder(
                "schema_id,captured_at_ms,session_id,player_uuid,player_name,label,operator,world,window_ms"
        );
        for (String feature : FeatureSchemaV1.featureNames()) {
            header.append(',').append(feature);
        }
        return header.toString();
    }

    private static String buildRow(BehaviorSnapshot snapshot,
                                   FeatureVector vector,
                                   CaptureSession session) {
        StringBuilder row = new StringBuilder();
        appendCsv(row, FeatureSchemaV1.ID);
        row.append(',').append(snapshot.getCapturedAtMs()).append(',');
        appendCsv(row, session.sessionId);
        row.append(',');
        appendCsv(row, snapshot.getPlayerId().toString());
        row.append(',');
        appendCsv(row, snapshot.getPlayerName());
        row.append(',');
        appendCsv(row, session.label.name());
        row.append(',');
        appendCsv(row, session.operator);
        row.append(',');
        appendCsv(row, snapshot.getWorldName());
        row.append(',').append(snapshot.getWindowMs());
        for (int i = 0; i < vector.size(); i++) {
            row.append(',').append(Double.toString(vector.get(i)));
        }
        return row.toString();
    }

    private static void appendCsv(StringBuilder target, String raw) {
        String value = safe(raw);
        target.append('"').append(value.replace("\"", "\"\"")).append('"');
    }

    private void pruneExpired(long now) {
        for (CaptureSession session : sessions.values()) {
            if (now >= session.expiresAtMs) sessions.remove(session.playerId, session);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class CaptureSession {
        final String sessionId;
        final UUID playerId;
        final String playerName;
        final MlCaptureLabel label;
        final String operator;
        final long startedAtMs;
        final long expiresAtMs;

        CaptureSession(String sessionId,
                       UUID playerId,
                       String playerName,
                       MlCaptureLabel label,
                       String operator,
                       long startedAtMs,
                       long expiresAtMs) {
            this.sessionId = sessionId;
            this.playerId = playerId;
            this.playerName = playerName;
            this.label = label;
            this.operator = operator;
            this.startedAtMs = startedAtMs;
            this.expiresAtMs = expiresAtMs;
        }

        CaptureSessionInfo info() {
            return new CaptureSessionInfo(
                    sessionId,
                    playerId,
                    playerName,
                    label,
                    operator,
                    startedAtMs,
                    expiresAtMs
            );
        }
    }

    public static final class CaptureSessionInfo {
        private final String sessionId;
        private final UUID playerId;
        private final String playerName;
        private final MlCaptureLabel label;
        private final String operator;
        private final long startedAtMs;
        private final long expiresAtMs;

        CaptureSessionInfo(String sessionId,
                           UUID playerId,
                           String playerName,
                           MlCaptureLabel label,
                           String operator,
                           long startedAtMs,
                           long expiresAtMs) {
            this.sessionId = sessionId;
            this.playerId = playerId;
            this.playerName = playerName;
            this.label = label;
            this.operator = operator;
            this.startedAtMs = startedAtMs;
            this.expiresAtMs = expiresAtMs;
        }

        public String getSessionId() { return sessionId; }
        public UUID getPlayerId() { return playerId; }
        public String getPlayerName() { return playerName; }
        public MlCaptureLabel getLabel() { return label; }
        public String getOperator() { return operator; }
        public long getStartedAtMs() { return startedAtMs; }
        public long getExpiresAtMs() { return expiresAtMs; }
    }
}
