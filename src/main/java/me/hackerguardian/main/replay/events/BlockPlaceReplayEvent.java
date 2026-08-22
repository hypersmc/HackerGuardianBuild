package me.hackerguardian.main.replay.events;

import me.hackerguardian.main.replay.ReplayCodec;
import me.hackerguardian.main.replay.ReplayEvent;
import me.hackerguardian.main.replay.ReplayEventType;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockPlaceEvent;

public final class BlockPlaceReplayEvent implements ReplayEvent {
    private final String world;
    private final int x, y, z;
    private final String blockState;
    private final String previousBlockState;

    public BlockPlaceReplayEvent(String world, int x, int y, int z, String blockState) {
        this(world, x, y, z, blockState, null);
    }

    public BlockPlaceReplayEvent(String world, int x, int y, int z, String blockState, String previousBlockState) {
        this.world = world;
        this.x = x; this.y = y; this.z = z;
        this.blockState = blockState;
        this.previousBlockState = previousBlockState;
    }

    public static BlockPlaceReplayEvent from(Block block) {
        Location location = block.getLocation();
        String world = location.getWorld() != null ? location.getWorld().getName() : "world";
        return new BlockPlaceReplayEvent(
                world,
                location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                block.getBlockData().getAsString()
        );
    }

    public static BlockPlaceReplayEvent from(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        Location location = block.getLocation();
        String world = location.getWorld() != null ? location.getWorld().getName() : "world";
        return new BlockPlaceReplayEvent(
                world,
                location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                block.getBlockData().getAsString(),
                event.getBlockReplacedState().getBlockData().getAsString()
        );
    }

    @Override public ReplayEventType type() { return ReplayEventType.BLOCK_PLACE; }

    @Override
    public void encode(ReplayCodec.Out out) throws Exception {
        out.writeString(world, 128);
        out.writeInt(x); out.writeInt(y); out.writeInt(z);
        out.writeString(blockState, 256);
        // Optional trailing field. Old readers safely ignore it, while the web 3D
        // renderer can reverse a placement when seeking before the snapshot anchor.
        if (previousBlockState != null) out.writeString(previousBlockState, 256);
    }
}
