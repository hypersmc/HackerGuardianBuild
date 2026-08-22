package me.hackerguardian.api.replays;

import me.hackerguardian.main.replay.ReplayChunkSnapshotCodec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Converts the private replay world-chunk snapshot into a stable, browser-facing
 * section palette. The web contract deliberately does not expose the stored BLOB.
 *
 * Section block order is y -> x -> z. Each section uses run-length encoded local
 * palette indices so mostly uniform Minecraft terrain does not turn into enormous
 * JSON integer arrays.
 */
public final class ReplayWorldWebCodec {

    private static final int MAX_DECODED_BYTES = 64 * 1024 * 1024;
    private static final int MAX_PALETTE_ENTRIES = 8192;

    private ReplayWorldWebCodec() {}

    public static Map<String, Object> decode(byte[] stored) throws IOException {
        byte[] raw = gunzipBounded(stored);
        ReplayChunkSnapshotCodec.DecodedChunk chunk = ReplayChunkSnapshotCodec.decodeChunk(raw);

        if (chunk.palette.length > MAX_PALETTE_ENTRIES) {
            throw new IOException("Replay world palette exceeds safety limit");
        }

        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("min_y", chunk.minY);
        out.put("max_y", chunk.maxY);
        out.put("order", "y-x-z");
        out.put("sections", sections(chunk));
        return out;
    }

    static List<Map<String, Object>> sections(ReplayChunkSnapshotCodec.DecodedChunk chunk) throws IOException {
        if (chunk.maxY < chunk.minY) throw new IOException("Invalid replay world height");
        int expected = 16 * (chunk.maxY - chunk.minY + 1) * 16;
        if (chunk.indices.length != expected) throw new IOException("Replay world index count mismatch");

        List<Map<String, Object>> out = new ArrayList<>();
        for (int baseY = chunk.minY; baseY <= chunk.maxY; baseY += 16) {
            int height = Math.min(16, chunk.maxY - baseY + 1);
            LinkedHashMap<Integer, Integer> globalToLocal = new LinkedHashMap<>();
            List<String> palette = new ArrayList<>();
            List<List<Integer>> runs = new ArrayList<>();
            int last = -1;
            int runLength = 0;
            boolean anyVisible = false;

            for (int localY = 0; localY < height; localY++) {
                int y = baseY + localY;
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        int absoluteIndex = ((y - chunk.minY) * 16 * 16) + (x * 16) + z;
                        int global = chunk.indices[absoluteIndex];
                        if (global < 0 || global >= chunk.palette.length) {
                            throw new IOException("Replay world palette index out of range");
                        }

                        String blockState = chunk.palette[global];
                        if (!isAir(blockState)) anyVisible = true;

                        Integer local = globalToLocal.get(global);
                        if (local == null) {
                            local = palette.size();
                            globalToLocal.put(global, local);
                            palette.add(blockState);
                        }

                        if (local == last) {
                            runLength++;
                        } else {
                            if (runLength > 0) runs.add(List.of(last, runLength));
                            last = local;
                            runLength = 1;
                        }
                    }
                }
            }
            if (runLength > 0) runs.add(List.of(last, runLength));

            // All-air sections have no visual value and are the largest source of
            // wasted transfer for modern-height worlds.
            if (!anyVisible) continue;

            LinkedHashMap<String, Object> section = new LinkedHashMap<>();
            section.put("base_y", baseY);
            section.put("height", height);
            section.put("palette", palette);
            section.put("runs", runs);
            out.add(section);
        }
        return out;
    }

    private static boolean isAir(String blockState) {
        if (blockState == null) return true;
        int bracket = blockState.indexOf('[');
        String id = bracket >= 0 ? blockState.substring(0, bracket) : blockState;
        return "minecraft:air".equals(id)
                || "minecraft:cave_air".equals(id)
                || "minecraft:void_air".equals(id)
                || "AIR".equalsIgnoreCase(id)
                || "CAVE_AIR".equalsIgnoreCase(id)
                || "VOID_AIR".equalsIgnoreCase(id);
    }

    private static byte[] gunzipBounded(byte[] compressed) throws IOException {
        if (compressed == null || compressed.length == 0) return new byte[0];
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(compressed.length * 3, 1024 * 1024))) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = gzip.read(buffer)) != -1) {
                total += read;
                if (total > MAX_DECODED_BYTES) throw new IOException("Replay world chunk exceeds safety limit");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (java.util.zip.ZipException notGzip) {
            if (compressed.length > MAX_DECODED_BYTES) throw new IOException("Replay world chunk exceeds safety limit");
            return compressed;
        }
    }
}
