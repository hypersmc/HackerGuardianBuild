package me.hackerguardian.main.detection.probe;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/** Bounded asynchronous writer for client-side behavioral probe results. */
public final class ProbeResultRecorder {

    private static final String HEADER = "probe_id,player_uuid,player_name,session_id,probe_entity_uuid,probe_name,spawned_at_ms,completed_at_ms,completion_reason,world,eligible_after_ms,trust_source,forced,initial_angle_deg,distance,first_rotation_ms,first_fov_ms,first_swing_ms,first_attack_ms,min_angle_deg,max_rotation_rate_dps,ping_ms,tps";

    private final Path path;
    private final Logger logger;
    private final ThreadPoolExecutor writerExecutor;
    private final BufferedWriter output;
    private final AtomicLong droppedRows = new AtomicLong();
    private final AtomicInteger pendingSinceFlush = new AtomicInteger();

    public ProbeResultRecorder(File file, Logger logger, int queueCapacity) throws IOException {
        if (file == null) throw new IllegalArgumentException("file is required");
        this.path = file.toPath().toAbsolutePath().normalize();
        this.logger = logger;
        prepare();
        this.output = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        int capacity = Math.max(64, Math.min(queueCapacity, 50_000));
        this.writerExecutor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "HackerGuardian-Probe-Results");
                    thread.setDaemon(true);
                    return thread;
                },
                (runnable, executor) -> droppedRows.incrementAndGet()
        );
    }

    public void record(BehaviorProbeEngine.ProbeResult result) {
        if (result == null) return;
        String row = result.toCsvRow();
        writerExecutor.execute(() -> write(row));
    }

    public File getFile() { return path.toFile(); }
    public long getDroppedRows() { return droppedRows.get(); }

    public void shutdown() {
        writerExecutor.shutdown();
        try {
            if (!writerExecutor.awaitTermination(5, TimeUnit.SECONDS)) writerExecutor.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writerExecutor.shutdownNow();
        }
        try {
            output.flush();
            output.close();
        } catch (IOException e) {
            if (logger != null) logger.warning("Failed to close probe result file: " + e.getMessage());
        }
    }

    private void prepare() throws IOException {
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        if (Files.exists(path) && Files.size(path) > 0L) {
            try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                if (!HEADER.equals(reader.readLine())) {
                    throw new IOException("Existing probe dataset header is incompatible: " + path);
                }
            }
            return;
        }
        Files.writeString(path, HEADER + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private void write(String row) {
        try {
            output.write(row);
            output.newLine();
            if (pendingSinceFlush.incrementAndGet() >= 16) {
                output.flush();
                pendingSinceFlush.set(0);
            }
        } catch (IOException e) {
            if (logger != null) logger.warning("Failed to write probe result: " + e.getMessage());
        }
    }
}
