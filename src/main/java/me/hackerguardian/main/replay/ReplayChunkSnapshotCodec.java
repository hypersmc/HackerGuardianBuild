package me.hackerguardian.main.replay;

import org.bukkit.Chunk;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ReplayChunkSnapshotCodec {

    private ReplayChunkSnapshotCodec() {}

    // Encodes a chunk into a compact palette:
    // palette: list of unique blockdata strings
    // chunk blocks: 16*worldHeight*16 palette indices (VarInt)
    public static byte[] encodeChunk(Chunk chunk, int minY, int maxY) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ReplayCodec.Out out = new ReplayCodec.Out(baos);

        int height = (maxY - minY + 1);
        out.writeVarInt(minY);
        out.writeVarInt(maxY);

        // Build palette
        Map<String, Integer> paletteIndex = new HashMap<>();
        List<String> palette = new ArrayList<>();

        int cx = chunk.getX();
        int cz = chunk.getZ();

        // First pass: palette
        for (int y = minY; y <= maxY; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    String bd = chunk.getBlock(x, y, z).getBlockData().getAsString();
                    paletteIndex.computeIfAbsent(bd, k -> {
                        palette.add(k);
                        return palette.size() - 1;
                    });
                }
            }
        }

        out.writeVarInt(palette.size());
        for (String s : palette) out.writeString(s, 256);

        // Second pass: write palette indices
        for (int y = minY; y <= maxY; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    String bd = chunk.getBlock(x, y, z).getBlockData().getAsString();
                    out.writeVarInt(paletteIndex.get(bd));
                }
            }
        }

        return baos.toByteArray();
    }

    public static DecodedChunk decodeChunk(byte[] raw) throws IOException {
        ReplayCodec.In in = new ReplayCodec.In(new ByteArrayInputStream(raw));
        int minY = in.readVarInt();
        int maxY = in.readVarInt();
        int height = (maxY - minY + 1);

        int paletteSize = in.readVarInt();
        String[] palette = new String[paletteSize];
        for (int i = 0; i < paletteSize; i++) {
            palette[i] = in.readString(256);
        }

        int total = 16 * height * 16;
        int[] indices = new int[total];
        for (int i = 0; i < total; i++) {
            indices[i] = in.readVarInt();
        }

        return new DecodedChunk(minY, maxY, palette, indices);
    }

    public static final class DecodedChunk {
        public final int minY;
        public final int maxY;
        public final String[] palette;
        public final int[] indices;

        public DecodedChunk(int minY, int maxY, String[] palette, int[] indices) {
            this.minY = minY;
            this.maxY = maxY;
            this.palette = palette;
            this.indices = indices;
        }
    }
}
