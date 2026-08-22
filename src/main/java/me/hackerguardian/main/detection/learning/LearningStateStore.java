package me.hackerguardian.main.detection.learning;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** Persists small learning-account state separately from the high-volume dataset. */
public final class LearningStateStore {

    private final Path statePath;
    private final Path manifestPath;
    private final Logger logger;
    private final long minimumBaselineMs;
    private final ExecutorService writer;

    public LearningStateStore(Path statePath,
                              Path manifestPath,
                              Logger logger,
                              long minimumBaselineMs) {
        this.statePath = statePath.toAbsolutePath().normalize();
        this.manifestPath = manifestPath.toAbsolutePath().normalize();
        this.logger = logger;
        this.minimumBaselineMs = Math.max(0L, minimumBaselineMs);
        this.writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "HackerGuardian-Learning-State");
            thread.setDaemon(true);
            return thread;
        });
    }

    public Map<UUID, LearningPlayerState> load() {
        Map<UUID, LearningPlayerState> result = new HashMap<>();
        if (!Files.isRegularFile(statePath)) return result;

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(statePath, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            warn("Unable to load learning state: " + e.getMessage());
            return result;
        }

        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith("player.") || !key.endsWith(".name")) continue;
            String rawUuid = key.substring("player.".length(), key.length() - ".name".length());
            try {
                UUID playerId = UUID.fromString(rawUuid);
                String prefix = "player." + rawUuid + ".";
                LearningPlayerState state = new LearningPlayerState(playerId);
                state.restore(
                        properties.getProperty(prefix + "name", ""),
                        parseLong(properties.getProperty(prefix + "first_trusted_ms")),
                        parseLong(properties.getProperty(prefix + "last_seen_ms")),
                        parseLong(properties.getProperty(prefix + "collected_ms")),
                        parseLong(properties.getProperty(prefix + "last_probe_ms")),
                        Boolean.parseBoolean(properties.getProperty(prefix + "trusted", "false"))
                );
                result.put(playerId, state);
            } catch (IllegalArgumentException ignored) {
                warn("Ignoring malformed learning state entry: " + rawUuid);
            }
        }
        return result;
    }

    public void saveAsync(Collection<LearningPlayerState> states) {
        List<LearningPlayerState.Snapshot> snapshots = snapshot(states);
        writer.execute(() -> saveSnapshots(snapshots));
    }

    public void saveNow(Collection<LearningPlayerState> states) {
        saveSnapshots(snapshot(states));
    }

    public void shutdown() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) writer.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
    }

    public Path getStatePath() { return statePath; }
    public Path getManifestPath() { return manifestPath; }

    private List<LearningPlayerState.Snapshot> snapshot(Collection<LearningPlayerState> states) {
        List<LearningPlayerState.Snapshot> result = new ArrayList<>();
        if (states == null) return result;
        for (LearningPlayerState state : states) {
            if (state != null) result.add(state.snapshot());
        }
        result.sort((a, b) -> a.getPlayerId().toString().compareTo(b.getPlayerId().toString()));
        return result;
    }

    private void saveSnapshots(List<LearningPlayerState.Snapshot> states) {
        try {
            writeState(states);
            writeManifest(states);
        } catch (IOException e) {
            warn("Unable to persist learning state/manifest: " + e.getMessage());
        }
    }

    private void writeState(List<LearningPlayerState.Snapshot> states) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("format.version", "1");
        for (LearningPlayerState.Snapshot state : states) {
            String prefix = "player." + state.getPlayerId() + ".";
            properties.setProperty(prefix + "name", safe(state.getPlayerName()));
            properties.setProperty(prefix + "first_trusted_ms", Long.toString(state.getFirstTrustedMs()));
            properties.setProperty(prefix + "last_seen_ms", Long.toString(state.getLastSeenMs()));
            properties.setProperty(prefix + "collected_ms", Long.toString(state.getCollectedMs()));
            properties.setProperty(prefix + "last_probe_ms", Long.toString(state.getLastProbeMs()));
            properties.setProperty(prefix + "trusted", Boolean.toString(state.isTrustedLastSeen()));
        }

        ensureParent(statePath);
        Path temp = statePath.resolveSibling(statePath.getFileName() + ".tmp");
        try (Writer out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            properties.store(out, "HackerGuardian Learning Mode v2 state");
        }
        atomicReplace(temp, statePath);
    }

    private void writeManifest(List<LearningPlayerState.Snapshot> states) throws IOException {
        ensureParent(manifestPath);
        Path temp = manifestPath.resolveSibling(manifestPath.getFileName() + ".tmp");
        try (BufferedWriter out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            out.write("player_uuid,player_name,trusted,first_trusted_ms,last_seen_ms,collected_ms,collected_hours,baseline_eligible,last_probe_ms");
            out.newLine();
            for (LearningPlayerState.Snapshot state : states) {
                appendCsv(out, state.getPlayerId().toString()); out.write(',');
                appendCsv(out, state.getPlayerName()); out.write(',');
                out.write(Boolean.toString(state.isTrustedLastSeen())); out.write(',');
                out.write(Long.toString(state.getFirstTrustedMs())); out.write(',');
                out.write(Long.toString(state.getLastSeenMs())); out.write(',');
                out.write(Long.toString(state.getCollectedMs())); out.write(',');
                out.write(Double.toString(state.getCollectedHours())); out.write(',');
                out.write(Boolean.toString(state.isTrustedLastSeen() && state.getCollectedMs() >= minimumBaselineMs)); out.write(',');
                out.write(Long.toString(state.getLastProbeMs()));
                out.newLine();
            }
        }
        atomicReplace(temp, manifestPath);
    }

    private static void appendCsv(BufferedWriter out, String raw) throws IOException {
        String value = safe(raw);
        out.write('"');
        out.write(value.replace("\"", "\"\""));
        out.write('"');
    }

    private static long parseLong(String raw) {
        if (raw == null || raw.isBlank()) return 0L;
        try { return Math.max(0L, Long.parseLong(raw.trim())); }
        catch (NumberFormatException ignored) { return 0L; }
    }

    private static void ensureParent(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
    }

    private static void atomicReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private void warn(String message) {
        if (logger != null) logger.warning(message);
    }
}
