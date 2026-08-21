package me.hackerguardian.main.replay;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public final class ReplaySession {

    private final JavaPlugin plugin;
    private final ReplayStorage storage;
    private final long chunkMs;

    private final long replayId;
    private int seq = 0;

    private long chunkStartMs;
    private long chunkEndMs;
    private long lastTsMs;

    private ByteArrayOutputStream chunkBuf = new ByteArrayOutputStream(8192);
    private DataOutputStream out = new DataOutputStream(chunkBuf);

    public ReplaySession(JavaPlugin plugin, long replayId, ReplayStorage storage, long chunkMs, long firstTsMs) {
        this.plugin = plugin;
        this.replayId = replayId;
        this.storage = storage;
        this.chunkMs = chunkMs;
        this.chunkStartMs = firstTsMs;
        this.chunkEndMs = firstTsMs;
        this.lastTsMs = firstTsMs;
    }

    public long replayId() { return replayId; }

    /** Single-writer: NO async appends. */
    public synchronized void append(long tsMs, byte[] eventBytes) throws IOException {
        if (eventBytes == null) return;

        // rotate chunk
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
        flushLocked();
    }

    private void flushLocked() {
        try { out.flush(); } catch (IOException ignored) {}

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

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                storage.appendChunk(replayId, mySeq, start, end, toWrite);
            } catch (Exception ignored) {}
        });
    }

    public void close(long endedAt) {
        synchronized (this) {
            flushLocked();
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try { storage.finishReplay(replayId, endedAt); } catch (Exception ignored) {}
        });
    }

    private void reset() {
        chunkBuf = new ByteArrayOutputStream(8192);
        out = new DataOutputStream(chunkBuf);
    }

    public long getReplayId() {
        return replayId;
    }
}