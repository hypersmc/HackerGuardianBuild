package me.hackerguardian.api.replays;

import me.hackerguardian.main.replay.ReplayCodec;
import me.hackerguardian.main.replay.ReplayEventType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/** Converts the internal replay event stream into a stable web-facing JSON model. */
public final class ReplayWebDecoder {

    private static final int MAX_EVENT_BYTES = 1024 * 1024;
    private static final int MAX_DECODED_CHUNK_BYTES = 32 * 1024 * 1024;
    private static final int MAX_RECORDS_PER_CHUNK = 250_000;

    private ReplayWebDecoder() {}

    public static DecodedChunk decode(ReplayApiRepository.ReplayRecord replay,
                                      ReplayApiRepository.ChunkData chunk) throws IOException {
        if (replay == null || chunk == null) throw new IOException("Replay/chunk is required");
        long origin = replay.captureStartMs() == null ? chunk.startMs() : replay.captureStartMs();
        byte[] raw = gunzipBounded(chunk.data());
        ReplayCodec.In input = new ReplayCodec.In(new ByteArrayInputStream(raw));
        TreeMap<Long, FrameBuilder> frames = new TreeMap<>();

        long absoluteTime = chunk.startMs();
        int records = 0;
        while (true) {
            try {
                int delta = input.readVarInt();
                int length = input.readVarInt();
                if (length < 0 || length > MAX_EVENT_BYTES) throw new IOException("Invalid replay event length: " + length);
                byte[] eventBytes = input.readBytes(length);
                absoluteTime += Math.max(0, delta);
                long relativeTime = Math.max(0L, absoluteTime - origin);
                decodeEvent(replay, relativeTime, eventBytes, frames.computeIfAbsent(relativeTime, FrameBuilder::new));
                if (++records > MAX_RECORDS_PER_CHUNK) throw new IOException("Replay chunk record limit exceeded");
            } catch (EOFException eof) {
                break;
            }
        }

        List<Map<String, Object>> out = new ArrayList<>(frames.size());
        for (FrameBuilder frame : frames.values()) out.add(frame.toMap());
        return new DecodedChunk(
                chunk.seq(),
                Math.max(0L, chunk.startMs() - origin),
                Math.max(0L, chunk.endMs() - origin),
                out
        );
    }

    public static String firstWorld(ReplayApiRepository.ReplayRecord replay,
                                    ReplayApiRepository.ChunkData firstChunk) {
        try {
            byte[] raw = gunzipBounded(firstChunk.data());
            ReplayCodec.In input = new ReplayCodec.In(new ByteArrayInputStream(raw));
            for (int i = 0; i < 10_000; i++) {
                input.readVarInt();
                int length = input.readVarInt();
                if (length < 0 || length > MAX_EVENT_BYTES) return null;
                byte[] eventBytes = input.readBytes(length);
                ReplayCodec.In event = new ReplayCodec.In(new ByteArrayInputStream(eventBytes));
                ReplayEventType type = type(event.readVarInt());
                if (type == ReplayEventType.PLAYER_SNAPSHOT || type == ReplayEventType.NEARBY_SNAPSHOT) {
                    return event.readString(128);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void decodeEvent(ReplayApiRepository.ReplayRecord replay,
                                    long relativeTime,
                                    byte[] bytes,
                                    FrameBuilder frame) {
        try {
            ReplayCodec.In input = new ReplayCodec.In(new ByteArrayInputStream(bytes));
            ReplayEventType type = type(input.readVarInt());
            if (type == null) {
                frame.events.add(Map.of("type", "UNKNOWN"));
                return;
            }

            switch (type) {
                case PLAYER_SNAPSHOT -> {
                    String world = input.readString(128);
                    double x = input.readDouble();
                    double y = input.readDouble();
                    double z = input.readDouble();
                    float yaw = input.readFloat();
                    float pitch = input.readFloat();
                    boolean onGround = input.readBoolean();
                    Boolean sneaking = input.hasMore() ? input.readBoolean() : null;
                    Boolean sprinting = input.hasMore() ? input.readBoolean() : null;
                    String heldItem = input.hasMore() ? input.readString(64) : null;
                    frame.players.add(playerMap(
                            replay.playerUuid(), replay.playerName(), world,
                            x, y, z, yaw, pitch, onGround, heldItem, sneaking, sprinting, true
                    ));
                }
                case NEARBY_SNAPSHOT -> {
                    String world = input.readString(128);
                    int count = Math.max(0, Math.min(256, input.readVarInt()));
                    for (int i = 0; i < count; i++) {
                        UUID uuid = input.readUUID();
                        String name = input.readString(16);
                        double x = input.readDouble();
                        double y = input.readDouble();
                        double z = input.readDouble();
                        float yaw = input.readFloat();
                        float pitch = input.readFloat();
                        String heldItem = input.readString(64);
                        frame.players.add(playerMap(
                                uuid.toString(), name, world,
                                x, y, z, yaw, pitch, null, heldItem, null, null, false
                        ));
                    }
                }
                case BLOCK_BREAK, BLOCK_PLACE -> {
                    String world = input.readString(128);
                    int x = input.readInt();
                    int y = input.readInt();
                    int z = input.readInt();
                    String block = input.readString(256);
                    LinkedHashMap<String, Object> value = new LinkedHashMap<>();
                    value.put("type", type.name());
                    value.put("world", world);
                    value.put("position", Map.of("x", x, "y", y, "z", z));
                    value.put("block", block);
                    if (type == ReplayEventType.BLOCK_PLACE && input.hasMore()) {
                        value.put("previous_block", input.readString(256));
                    }
                    frame.events.add(value);
                }
                case ARM_SWING -> frame.events.add(event(type));
                case SNEAK_TOGGLE, SPRINT_TOGGLE -> frame.events.add(event(type, "enabled", input.readBoolean()));
                case ITEM_CONSUME -> frame.events.add(event(type, "item", input.readString(64)));
                case INVENTORY_CLICK -> frame.events.add(event(type,
                        "slot", input.readVarInt(),
                        "click_type", input.readString(32),
                        "action", input.readString(64),
                        "item", input.readString(64),
                        "amount", input.readVarInt()));
                case ITEM_DROP, ITEM_PICKUP -> frame.events.add(event(type,
                        "item", input.readString(64),
                        "amount", input.readVarInt()));
                case PROJECTILE_LAUNCH -> frame.events.add(event(type,
                        "projectile", input.readString(64),
                        "world", input.readString(128),
                        "position", Map.of("x", input.readDouble(), "y", input.readDouble(), "z", input.readDouble()),
                        "velocity", Map.of("x", input.readDouble(), "y", input.readDouble(), "z", input.readDouble())));
                case PROJECTILE_HIT -> frame.events.add(event(type,
                        "projectile", input.readString(64),
                        "hit_type", input.readString(16),
                        "hit_entity", input.readString(64),
                        "world", input.readString(128),
                        "position", Map.of("x", input.readDouble(), "y", input.readDouble(), "z", input.readDouble())));
                default -> frame.events.add(event(type));
            }
        } catch (Exception ignored) {
            // A single malformed/unknown event must not make the whole chunk unusable.
            frame.events.add(Map.of("type", "DECODE_ERROR", "time_ms", relativeTime));
        }
    }

    private static Map<String, Object> playerMap(String uuid,
                                                  String name,
                                                  String world,
                                                  double x,
                                                  double y,
                                                  double z,
                                                  float yaw,
                                                  float pitch,
                                                  Boolean onGround,
                                                  String heldItem,
                                                  Boolean sneaking,
                                                  Boolean sprinting,
                                                  boolean subject) {
        LinkedHashMap<String, Object> player = new LinkedHashMap<>();
        player.put("uuid", uuid);
        player.put("name", name);
        player.put("subject", subject);
        player.put("world", world);
        player.put("position", Map.of("x", x, "y", y, "z", z));
        player.put("rotation", Map.of("yaw", yaw, "pitch", pitch));
        if (onGround != null) player.put("on_ground", onGround);
        if (heldItem != null) player.put("held_item", heldItem);
        if (sneaking != null) player.put("sneaking", sneaking);
        if (sprinting != null) player.put("sprinting", sprinting);
        return player;
    }

    private static Map<String, Object> event(ReplayEventType type, Object... values) {
        LinkedHashMap<String, Object> event = new LinkedHashMap<>();
        event.put("type", type.name());
        for (int i = 0; i + 1 < values.length; i += 2) {
            event.put(String.valueOf(values[i]), values[i + 1]);
        }
        return event;
    }

    private static ReplayEventType type(int ordinal) {
        ReplayEventType[] values = ReplayEventType.values();
        return ordinal < 0 || ordinal >= values.length ? null : values[ordinal];
    }

    private static byte[] gunzipBounded(byte[] compressed) throws IOException {
        if (compressed == null) return new byte[0];
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(compressed.length * 2, 1024 * 1024))) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = gzip.read(buffer)) != -1) {
                total += read;
                if (total > MAX_DECODED_CHUNK_BYTES) throw new IOException("Decoded replay chunk exceeds safety limit");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (java.util.zip.ZipException notGzip) {
            if (compressed.length > MAX_DECODED_CHUNK_BYTES) throw new IOException("Replay chunk exceeds safety limit");
            return compressed;
        }
    }

    private static final class FrameBuilder {
        final long timeMs;
        final List<Map<String, Object>> players = new ArrayList<>();
        final List<Map<String, Object>> events = new ArrayList<>();

        FrameBuilder(long timeMs) {
            this.timeMs = timeMs;
        }

        Map<String, Object> toMap() {
            LinkedHashMap<String, Object> frame = new LinkedHashMap<>();
            frame.put("t", timeMs);
            frame.put("players", players);
            frame.put("events", events);
            return frame;
        }
    }

    public record DecodedChunk(int seq, long startMs, long endMs, List<Map<String, Object>> frames) {}
}
