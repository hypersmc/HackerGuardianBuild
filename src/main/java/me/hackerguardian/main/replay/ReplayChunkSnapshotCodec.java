package me.hackerguardian.main.replay;

import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;

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
        return encode(minY, maxY, (x, y, z) -> chunk.getBlock(x, y, z).getBlockData().getAsString());
    }

    /**
     * Encode a Bukkit ChunkSnapshot. ChunkSnapshot is detached from the live chunk,
     * so the expensive full-height palette walk can run on replay I/O workers after
     * the snapshot itself has been acquired on the server thread.
     */
    public static byte[] encodeChunk(ChunkSnapshot snapshot, int minY, int maxY) throws IOException {
        return encode(minY, maxY, (x, y, z) -> snapshot.getBlockData(x, y, z).getAsString());
    }

    private static byte[] encode(int minY, int maxY, BlockStateSource source) throws IOException {
        if (maxY < minY) throw new IOException("Invalid chunk snapshot height");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ReplayCodec.Out out = new ReplayCodec.Out(baos);
        out.writeVarInt(minY);
        out.writeVarInt(maxY);

        Map<String, Integer> paletteIndex = new HashMap<>();
        List<String> palette = new ArrayList<>();

        for (int y = minY; y <= maxY; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    String blockData = source.blockData(x, y, z);
                    paletteIndex.computeIfAbsent(blockData, key -> {
                        palette.add(key);
                        return palette.size() - 1;
                    });
                }
            }
        }

        out.writeVarInt(palette.size());
        for (String state : palette) out.writeString(state, 256);

        for (int y = minY; y <= maxY; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    out.writeVarInt(paletteIndex.get(source.blockData(x, y, z)));
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
        if (height <= 0 || height > 4096) throw new IOException("Invalid chunk height: " + height);

        int paletteSize = in.readVarInt();
        if (paletteSize <= 0 || paletteSize > 65_536) throw new IOException("Invalid chunk palette size: " + paletteSize);
        String[] palette = new String[paletteSize];
        for (int i = 0; i < paletteSize; i++) {
            palette[i] = in.readString(256);
        }

        int total = 16 * height * 16;
        int[] indices = new int[total];
        for (int i = 0; i < total; i++) {
            int index = in.readVarInt();
            if (index < 0 || index >= paletteSize) throw new IOException("Chunk palette index out of range");
            indices[i] = index;
        }

        return new DecodedChunk(minY, maxY, palette, indices);
    }

    @FunctionalInterface
    private interface BlockStateSource {
        String blockData(int x, int y, int z);
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
