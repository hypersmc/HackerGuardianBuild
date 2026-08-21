package me.hackerguardian.main.replay.events;

import me.hackerguardian.main.replay.ReplayCodec;
import me.hackerguardian.main.replay.ReplayEvent;
import me.hackerguardian.main.replay.ReplayEventType;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

public final class ProjectileLaunchReplayEvent implements ReplayEvent {
    private final String entityType;
    private final String world;
    private final double x, y, z;
    private final double vx, vy, vz;

    public ProjectileLaunchReplayEvent(String entityType, String world, double x, double y, double z, double vx, double vy, double vz) {
        this.entityType = entityType;
        this.world = world;
        this.x = x; this.y = y; this.z = z;
        this.vx = vx; this.vy = vy; this.vz = vz;
    }

    public static ProjectileLaunchReplayEvent from(Entity proj) {
        Location l = proj.getLocation();
        Vector v = proj.getVelocity();
        String w = (l.getWorld() != null) ? l.getWorld().getName() : "world";
        return new ProjectileLaunchReplayEvent(proj.getType().name(), w, l.getX(), l.getY(), l.getZ(), v.getX(), v.getY(), v.getZ());
    }

    @Override public ReplayEventType type() { return ReplayEventType.PROJECTILE_LAUNCH; }

    @Override
    public void encode(ReplayCodec.Out out) throws Exception {
        out.writeString(entityType, 64);
        out.writeString(world, 128);
        out.writeDouble(x); out.writeDouble(y); out.writeDouble(z);
        out.writeDouble(vx); out.writeDouble(vy); out.writeDouble(vz);
    }
}
