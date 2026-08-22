package me.hackerguardian.api.replays;

import me.hackerguardian.main.replay.ReplayChunkSnapshotCodec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ReplayWorldWebCodecTest {

    @Test
    void omitsAllAirSections() throws Exception {
        String[] palette = {"minecraft:air"};
        int[] indices = new int[16 * 16 * 16];
        ReplayChunkSnapshotCodec.DecodedChunk chunk =
                new ReplayChunkSnapshotCodec.DecodedChunk(0, 15, palette, indices);

        assertTrue(ReplayWorldWebCodec.sections(chunk).isEmpty());
    }

    @Test
    void createsLocalPaletteAndRunLengthEncoding() throws Exception {
        String[] palette = {"minecraft:air", "minecraft:stone"};
        int[] indices = new int[16 * 16 * 16];
        indices[0] = 1;
        indices[1] = 1;

        ReplayChunkSnapshotCodec.DecodedChunk chunk =
                new ReplayChunkSnapshotCodec.DecodedChunk(0, 15, palette, indices);

        List<Map<String, Object>> sections = ReplayWorldWebCodec.sections(chunk);
        assertEquals(1, sections.size());
        Map<String, Object> section = sections.get(0);
        assertEquals(0, section.get("base_y"));
        assertEquals(16, section.get("height"));

        @SuppressWarnings("unchecked")
        List<String> localPalette = (List<String>) section.get("palette");
        assertEquals(List.of("minecraft:stone", "minecraft:air"), localPalette);

        @SuppressWarnings("unchecked")
        List<List<Integer>> runs = (List<List<Integer>>) section.get("runs");
        assertFalse(runs.isEmpty());
        assertEquals(List.of(0, 2), runs.get(0));
        assertEquals(4096, runs.stream().mapToInt(run -> run.get(1)).sum());
    }
}
