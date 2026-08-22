package me.hackerguardian.main.detection.learning;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Small dependency-free UUIDv7 generator for time-sortable learning/probe ids. */
public final class UuidV7 {

    private UuidV7() {}

    public static UUID next() {
        return fromTimestamp(System.currentTimeMillis());
    }

    static UUID fromTimestamp(long timestampMs) {
        long timestamp = timestampMs & 0x0000FFFFFFFFFFFFL;
        long randomA = ThreadLocalRandom.current().nextLong() & 0x0FFFL;
        long randomB = ThreadLocalRandom.current().nextLong() & 0x3FFFFFFFFFFFFFFFL;

        long most = (timestamp << 16) | 0x7000L | randomA;
        long least = 0x8000000000000000L | randomB;
        return new UUID(most, least);
    }

    public static long timestampMillis(UUID uuid) {
        if (uuid == null || uuid.version() != 7) {
            throw new IllegalArgumentException("UUIDv7 is required");
        }
        return (uuid.getMostSignificantBits() >>> 16) & 0x0000FFFFFFFFFFFFL;
    }
}
