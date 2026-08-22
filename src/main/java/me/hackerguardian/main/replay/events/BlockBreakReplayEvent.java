package me.hackerguardian.main.replay.events;

import me.hackerguardian.main.replay.ReplayCodec;
import me.hackerguardian.main.replay.ReplayEvent;
import me.hackerguardian.main.replay.ReplayEventType;
import org.bukkit.Location;
import org.bukkit.block.Block;

public final class BlockBreakReplayEvent implements ReplayEvent {
    private final String world;
    private final int x, y, z;
    private final String blockState;

    public BlockBreakReplayEvent(String world, int x, int y, int z, String blockState) {
        this.world = world;
        this.x = x; this.y = y; this.z = z;
        this.blockState = blockState;
    }

    public static BlockBreakReplayEvent from(Block block) {
        Location location = block.getLocation();
        String world = location.getWorld() != null ? location.getWorld().getName() : "world";
        return new BlockBreakReplayEvent(
                world,
                location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                block.getBlockData().getAsString()
        );
    }

    @Override public ReplayEventType type() { return ReplayEventType.BLOCK_BREAK; }

    @Override
    public void encode(ReplayCodec.Out out) throws Exception {
        out.writeString(world, 128);
        out.writeInt(x); out.writeInt(y); out.writeInt(z);
        out.writeString(blockState, 256);
    }
}
