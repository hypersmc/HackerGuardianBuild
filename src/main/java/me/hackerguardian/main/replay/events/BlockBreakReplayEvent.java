package me.hackerguardian.main.replay.events;

import me.hackerguardian.main.replay.ReplayCodec;
import me.hackerguardian.main.replay.ReplayEvent;
import me.hackerguardian.main.replay.ReplayEventType;
import org.bukkit.Location;
import org.bukkit.block.Block;

public final class BlockBreakReplayEvent implements ReplayEvent {
    private final String world;
    private final int x, y, z;
    private final String blockType;

    public BlockBreakReplayEvent(String world, int x, int y, int z, String blockType) {
        this.world = world;
        this.x = x; this.y = y; this.z = z;
        this.blockType = blockType;
    }

    public static BlockBreakReplayEvent from(Block b) {
        Location l = b.getLocation();
        String w = (l.getWorld() != null) ? l.getWorld().getName() : "world";
        return new BlockBreakReplayEvent(w, l.getBlockX(), l.getBlockY(), l.getBlockZ(), b.getType().name());
    }

    @Override public ReplayEventType type() { return ReplayEventType.BLOCK_BREAK; }

    @Override
    public void encode(ReplayCodec.Out out) throws Exception {
        out.writeString(world, 128);
        out.writeInt(x); out.writeInt(y); out.writeInt(z);
        out.writeString(blockType, 64);
    }
}
