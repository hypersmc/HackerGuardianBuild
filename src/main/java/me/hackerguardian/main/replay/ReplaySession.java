package me.hackerguardian.main.replay;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class ReplaySession {

    private final JavaPlugin plugin;
    private final ReplayStorage storage;
    private final long chunkMs;
    private final Executor ioExecutor;

    private final long replayId;
    private int seq = 0;

    private long chunkStartMs;
    private long chunkEndMs;
    private long lastTsMs;

    private ByteArrayOutputStream chunkBuf = new ByteArrayOutputStream(8192);
    private DataOutputStream out = new DataOutputStream(chunkBuf);
    private CompletableFuture<Void> pendingIo = CompletableFuture.completedFuture(null);
    private boolean closed = false;

    public ReplaySession(JavaPlugin plugin, long replayId, ReplayStorage storage, long chunkMs,
                         long firstTsMs, Executor ioExecutor) {
        this.plugin = plugin;
        this.replayId = replayId;
        this.storage = storage;
        this.chunkMs = chunkMs;
        this.ioExecutor = ioExecutor;
        this.chunkStartMs = firstTsMs;
        this.chunkEndMs = firstTsMs;
        this.lastTsMs = firstTsMs;
    }

    public long replayId() { return replayId; }

    /** Single-writer: appends are expected from the server thread. */
    public synchronized void append(long tsMs, byte[] eventBytes) throws IOException {
        if (closed || eventBytes == null) return;

        if (tsMs - chunkStartMs >= chunkMs) {
            flushLocked();
            chunkStartMs = tsMs;
            chunkEndMs = tsMs;
            lastTsMs = tsMs;
        }

        int delta = (int) Math.max(0, Math.min(Integer.MAX_VALUE, tsMs - lastTsMs));
        lastTsMs = tsMs;
        chunkEndMs = tsMs;

        ReplayCodec.writeVarInt(out, delta);
        ReplayCodec.writeVarInt(out, eventBytes.length);
        out.write(eventBytes);
    }

    public synchronized void flush() {
        if (!closed) flushLocked();
    }

    private void flushLocked() {
        try {
            out.flush();
        } catch (IOException e) {
            plugin.getLogger().warning("[Replay] Failed to flush in-memory replay " + replayId + ": " + e.getMessage());
        }

        byte[] raw = chunkBuf.toByteArray();
        if (raw.length == 0) {
            reset();
            return;
        }

        final int mySeq = seq++;
        final long start = chunkStartMs;
        final long end = chunkEndMs;
        final byte[] toWrite = raw;

        reset();

        pendingIo = pendingIo.handle((ignored, failure) -> {
            if (failure != null) {
                plugin.getLogger().warning("[Replay] Earlier replay I/O failed for " + replayId + ": " + rootMessage(failure));
            }
            return null;
        }).thenRunAsync(() -> {
            try {
                storage.appendChunk(replayId, mySeq, start, end, toWrite);
            } catch (Exception e) {
                throw new ReplayIoException("Failed to append replay chunk " + replayId + "/" + mySeq, e);
            }
        }, ioExecutor);
    }

    /**
     * Queues the final chunk followed by the replay completion marker on the same
     * executor chain, guaranteeing ended_at is written after all chunks.
     */
    public synchronized CompletableFuture<Void> close(long endedAt) {
        if (closed) return pendingIo;
        flushLocked();
        closed = true;

        pendingIo = pendingIo.handle((ignored, failure) -> {
            if (failure != null) {
                plugin.getLogger().warning("[Replay] Replay " + replayId + " had a chunk write failure: " +
                        rootMessage(failure));
            }
            return null;
        }).thenRunAsync(() -> {
            try {
                storage.finishReplay(replayId, endedAt);
            } catch (Exception e) {
                throw new ReplayIoException("Failed to finish replay " + replayId, e);
            }
        }, ioExecutor).whenComplete((ignored, failure) -> {
            if (failure != null) {
                plugin.getLogger().warning("[Replay] Failed to finalize replay " + replayId + ": " + rootMessage(failure));
            }
        });

        return pendingIo;
    }

    private void reset() {
        chunkBuf = new ByteArrayOutputStream(8192);
        out = new DataOutputStream(chunkBuf);
    }

    public long getReplayId() {
        return replayId;
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) cur = cur.getCause();
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }

    private static final class ReplayIoException extends RuntimeException {
        ReplayIoException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
