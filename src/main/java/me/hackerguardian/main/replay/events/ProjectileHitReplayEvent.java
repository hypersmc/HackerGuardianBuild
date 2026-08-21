package me.hackerguardian.main.replay.events;

import me.hackerguardian.main.replay.ReplayCodec;
import me.hackerguardian.main.replay.ReplayEvent;
import me.hackerguardian.main.replay.ReplayEventType;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;

public final class ProjectileHitReplayEvent implements ReplayEvent {
    private final String projectileType;
    private final String hitType; // BLOCK/ENTITY/NONE
    private final String hitEntityType; // if entity
    private final String world;
    private final double x, y, z;

    public ProjectileHitReplayEvent(String projectileType, String hitType, String hitEntityType, String world, double x, double y, double z) {
        this.projectileType = projectileType;
        this.hitType = hitType;
        this.hitEntityType = hitEntityType;
        this.world = world;
        this.x = x; this.y = y; this.z = z;
    }

    public static ProjectileHitReplayEvent from(Entity projectile, Location loc, Entity hitEntityOrNull, boolean hitBlock) {
        String projType = projectile.getType().name();
        String ht = hitBlock ? "BLOCK" : (hitEntityOrNull != null ? "ENTITY" : "NONE");
        String hitEnt = (hitEntityOrNull != null) ? hitEntityOrNull.getType().name() : EntityType.UNKNOWN.name();
        String w = (loc.getWorld() != null) ? loc.getWorld().getName() : "world";
        return new ProjectileHitReplayEvent(projType, ht, hitEnt, w, loc.getX(), loc.getY(), loc.getZ());
    }

    @Override public ReplayEventType type() { return ReplayEventType.PROJECTILE_HIT; }

    @Override
    public void encode(ReplayCodec.Out out) throws Exception {
        out.writeString(projectileType, 64);
        out.writeString(hitType, 16);
        out.writeString(hitEntityType, 64);
        out.writeString(world, 128);
        out.writeDouble(x); out.writeDouble(y); out.writeDouble(z);
    }
}
