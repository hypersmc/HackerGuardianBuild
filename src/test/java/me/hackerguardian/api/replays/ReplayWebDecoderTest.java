package me.hackerguardian.api.replays;

import me.hackerguardian.main.replay.ReplayCodec;
import me.hackerguardian.main.replay.ReplayEvent;
import me.hackerguardian.main.replay.events.ArmSwingEvent;
import me.hackerguardian.main.replay.events.PlayerSnapshotEvent;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayWebDecoderTest {

    @Test
    void decodesInternalChunkIntoCaptureRelativeBrowserFrames() throws Exception {
        UUID playerId = UUID.randomUUID();
        long captureStart = 10_000L;
        ReplayApiRepository.ReplayRecord replay = new ReplayApiRepository.ReplayRecord(
                42L,
                playerId.toString(),
                "Example",
                "survival",
                12_500L,
                16_000L,
                "DETECTION",
                "detector=combat.reach-envelope;strength=STRONG",
                null,
                1,
                "gzip",
                0L,
                1,
                captureStart,
                15_000L
        );

        byte[] compressed = chunk(
                new TimedEvent(0, new PlayerSnapshotEvent(
                        "world", 20.5, 64.0, -83.25, 114.0f, 7.5f, true
                )),
                new TimedEvent(250, new ArmSwingEvent())
        );
        ReplayApiRepository.ChunkData row = new ReplayApiRepository.ChunkData(
                0, captureStart, 15_000L, compressed.length, compressed
        );

        ReplayWebDecoder.DecodedChunk decoded = ReplayWebDecoder.decode(replay, row);
        assertEquals(0, decoded.seq());
        assertEquals(0L, decoded.startMs());
        assertEquals(5_000L, decoded.endMs());
        assertEquals(2, decoded.frames().size());

        Map<String, Object> first = decoded.frames().get(0);
        assertEquals(0L, first.get("t"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> players = (List<Map<String, Object>>) first.get("players");
        assertEquals(1, players.size());
        assertEquals(playerId.toString(), players.get(0).get("uuid"));
        assertEquals("Example", players.get(0).get("name"));
        assertEquals("world", players.get(0).get("world"));
        assertEquals(true, players.get(0).get("subject"));
        assertEquals(true, players.get(0).get("on_ground"));

        @SuppressWarnings("unchecked")
        Map<String, Object> position = (Map<String, Object>) players.get(0).get("position");
        assertEquals(20.5, (Double) position.get("x"), 1.0E-9);
        assertEquals(64.0, (Double) position.get("y"), 1.0E-9);
        assertEquals(-83.25, (Double) position.get("z"), 1.0E-9);

        Map<String, Object> second = decoded.frames().get(1);
        assertEquals(250L, second.get("t"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) second.get("events");
        assertEquals(1, events.size());
        assertEquals("ARM_SWING", events.get(0).get("type"));

        assertEquals("world", ReplayWebDecoder.firstWorld(replay, row));
    }

    @Test
    void malformedEventDoesNotExposeOrAbortPrivateChunkFormat() throws Exception {
        ReplayApiRepository.ReplayRecord replay = new ReplayApiRepository.ReplayRecord(
                7L,
                UUID.randomUUID().toString(),
                "Example",
                "pvp",
                1_000L,
                2_000L,
                "MANUAL",
                "",
                null,
                1,
                "gzip",
                0L,
                1,
                1_000L,
                2_000L
        );

        byte[] compressed;
        try (ByteArrayOutputStream raw = new ByteArrayOutputStream()) {
            DataOutputStream out = new DataOutputStream(raw);
            ReplayCodec.writeVarInt(out, 0);
            ReplayCodec.writeVarInt(out, 1);
            out.writeByte(127); // unknown event ordinal
            out.flush();
            compressed = gzip(raw.toByteArray());
        }

        ReplayWebDecoder.DecodedChunk decoded = ReplayWebDecoder.decode(
                replay,
                new ReplayApiRepository.ChunkData(0, 1_000L, 2_000L, compressed.length, compressed)
        );
        assertEquals(1, decoded.frames().size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) decoded.frames().get(0).get("events");
        assertNotNull(events);
        assertTrue(events.stream().anyMatch(event -> "UNKNOWN".equals(event.get("type"))));
    }

    private static byte[] chunk(TimedEvent... events) throws Exception {
        try (ByteArrayOutputStream raw = new ByteArrayOutputStream()) {
            DataOutputStream out = new DataOutputStream(raw);
            for (TimedEvent timed : events) {
                byte[] encoded = ReplayCodec.encodeEvent(timed.event());
                assertNotNull(encoded);
                ReplayCodec.writeVarInt(out, timed.deltaMs());
                ReplayCodec.writeVarInt(out, encoded.length);
                out.write(encoded);
            }
            out.flush();
            return gzip(raw.toByteArray());
        }
    }

    private static byte[] gzip(byte[] raw) throws Exception {
        try (ByteArrayOutputStream compressed = new ByteArrayOutputStream();
             GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(raw);
            gzip.finish();
            return compressed.toByteArray();
        }
    }

    private record TimedEvent(int deltaMs, ReplayEvent event) {}
}
