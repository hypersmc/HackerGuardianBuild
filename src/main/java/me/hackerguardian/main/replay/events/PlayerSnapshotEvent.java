package me.hackerguardian.main.replay.events;

import me.hackerguardian.main.replay.ReplayCodec;
import me.hackerguardian.main.replay.ReplayEvent;
import me.hackerguardian.main.replay.ReplayEventType;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public final class PlayerSnapshotEvent implements ReplayEvent {
    private final String world;
    private final double x, y, z;
    private final float yaw, pitch;
    private final boolean onGround;

    public PlayerSnapshotEvent(String world, double x, double y, double z, float yaw, float pitch, boolean onGround) {
        this.world = world;
        this.x = x; this.y = y; this.z = z;
        this.yaw = yaw; this.pitch = pitch;
        this.onGround = onGround;
    }

    public static PlayerSnapshotEvent from(Player p) {
        Location l = p.getLocation();
        String world = (l.getWorld() != null) ? l.getWorld().getName() : "world";
        // Bukkit doesn't expose true onGround reliably; use isOnGround where present (Paper), else false
        boolean onGround = p.isOnGround();
        return new PlayerSnapshotEvent(world, l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch(), onGround);
    }

    @Override public ReplayEventType type() { return ReplayEventType.PLAYER_SNAPSHOT; }

    @Override
    public void encode(ReplayCodec.Out out) throws Exception {
        out.writeString(world, 128);
        out.writeDouble(x); out.writeDouble(y); out.writeDouble(z);
        out.writeFloat(yaw); out.writeFloat(pitch);
        out.writeBoolean(onGround);
    }
}
