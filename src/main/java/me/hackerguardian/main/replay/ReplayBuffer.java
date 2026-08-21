package me.hackerguardian.main.replay;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public final class ReplayBuffer {

    public static final class Entry {
        public final long tsMs;
        public final byte[] eventBytes;
        public Entry(long tsMs, byte[] eventBytes) {
            this.tsMs = tsMs;
            this.eventBytes = eventBytes;
        }
    }

    private final long maxAgeMs;
    private final ArrayDeque<Entry> deque = new ArrayDeque<>();

    public ReplayBuffer(long maxAgeMs) {
        this.maxAgeMs = maxAgeMs;
    }

    public synchronized void add(long tsMs, byte[] bytes) {
        if (bytes == null) return;
        deque.addLast(new Entry(tsMs, bytes));
        trim(tsMs);
    }

    public synchronized List<Entry> snapshot(long nowMs) {
        trim(nowMs);
        return new ArrayList<>(deque);
    }

    private void trim(long nowMs) {
        long cutoff = nowMs - maxAgeMs;
        while (!deque.isEmpty() && deque.peekFirst().tsMs < cutoff) {
            deque.removeFirst();
        }
    }
}