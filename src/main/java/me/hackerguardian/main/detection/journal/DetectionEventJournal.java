package me.hackerguardian.main.detection.journal;

import me.hackerguardian.main.detection.DetectionAssessment;
import me.hackerguardian.main.detection.DetectionFinding;
import me.hackerguardian.main.detection.learning.UuidV7;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Bounded asynchronous bridge from DetectionAssessment to the shared API journal.
 * Repeated assessment windows are suppressed so a one-second assessment loop
 * does not become an unbounded duplicate-event stream.
 */
public final class DetectionEventJournal {

    private final DetectionEventRepository repository;
    private final Logger logger;
    private final String serverName;
    private final long repeatMs;
    private final double scoreDelta;
    private final ThreadPoolExecutor executor;
    private final Map<String, LastEmission> last = new ConcurrentHashMap<>();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private volatile String lastError = "";

    public DetectionEventJournal(DataSource dataSource,
                                 Logger logger,
                                 String serverName,
                                 long repeatMs,
                                 double scoreDelta,
                                 int queueCapacity,
                                 int retentionDays) throws Exception {
        this.repository = new DetectionEventRepository(dataSource);
        this.repository.ensureTable();
        this.logger = logger;
        this.serverName = serverName == null || serverName.isBlank() ? "default" : serverName;
        this.repeatMs = Math.max(1000L, Math.min(repeatMs, 300_000L));
        this.scoreDelta = Math.max(0.01, Math.min(scoreDelta, 1.0));

        int capacity = Math.max(256, Math.min(queueCapacity, 100_000));
        this.executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "HackerGuardian-DetectionJournal");
                    thread.setDaemon(true);
                    return thread;
                },
                (runnable, ignored) -> dropped.incrementAndGet()
        );

        int days = Math.max(1, Math.min(retentionDays, 3650));
        long cutoff = System.currentTimeMillis() - (days * 86_400_000L);
        executor.execute(() -> {
            try {
                repository.cleanupBefore(cutoff);
            } catch (Exception e) {
                logFailure("journal retention cleanup", e);
            }
        });
    }

    public void record(DetectionAssessment assessment) {
        if (assessment == null || !assessment.hasFindings()) return;
        long now = assessment.getCapturedAtMs();
        for (DetectionFinding finding : assessment.getFindings()) {
            if (finding == null) continue;
            String key = assessment.getPlayerId() + "\n" + finding.getDetectorId();
            double weighted = finding.getWeightedScore();
            String strength = finding.getStrength().name();

            LastEmission previous = last.get(key);
            if (previous != null
                    && now - previous.timeMs < repeatMs
                    && Math.abs(weighted - previous.weightedScore) < scoreDelta
                    && strength.equals(previous.strength)) {
                continue;
            }
            last.put(key, new LastEmission(now, weighted, strength));

            DetectionEventRepository.Event event = DetectionEventRepository.fromFinding(
                    UuidV7.next().toString(),
                    serverName,
                    now,
                    assessment.getPlayerId().toString(),
                    assessment.getPlayerName(),
                    assessment.getRiskScore(),
                    assessment.getReliability(),
                    finding,
                    null
            );
            executor.execute(() -> write(event));
        }
    }

    private void write(DetectionEventRepository.Event event) {
        try {
            repository.insert(event);
            written.incrementAndGet();
            lastError = "";
        } catch (Exception e) {
            logFailure("detection journal write", e);
        }
    }

    private void logFailure(String action, Exception error) {
        lastError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        if (logger != null) logger.warning("[Detection] Failed " + action + ": " + lastError);
    }

    public long getDroppedEvents() { return dropped.get(); }
    public long getWrittenEvents() { return written.get(); }
    public String getLastError() { return lastError; }
    public DetectionEventRepository getRepository() { return repository; }

    public void clearPlayer(java.util.UUID playerId) {
        if (playerId == null) return;
        String prefix = playerId + "\n";
        last.keySet().removeIf(key -> key.startsWith(prefix));
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(8, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                executor.awaitTermination(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        last.clear();
    }

    private record LastEmission(long timeMs, double weightedScore, String strength) {}
}
