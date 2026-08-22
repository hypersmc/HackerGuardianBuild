package me.hackerguardian.main.detection.learning;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class UuidV7Test {

    @Test
    void producesVersionSevenRfcVariantAndPreservesTimestamp() {
        long timestamp = 1_787_393_000_123L;
        UUID first = UuidV7.fromTimestamp(timestamp);
        UUID second = UuidV7.fromTimestamp(timestamp);

        assertEquals(7, first.version());
        assertEquals(2, first.variant());
        assertEquals(timestamp, UuidV7.timestampMillis(first));
        assertNotEquals(first, second);
    }
}
