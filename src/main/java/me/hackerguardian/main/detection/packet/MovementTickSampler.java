package me.hackerguardian.main.detection.packet;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * Bukkit-only capture of lightweight environment hints. Must run on the
 * server's primary thread. Not a full collision/VoxelShape implementation.
 */
public final class MovementTickSampler {
    private MovementTickSampler() {}

    public static MovementTickTimeline.TickInput sample(Player player) {
        if (player == null || !player.isOnline()) return null;
        Location loc = player.getLocation();
        World world = loc.getWorld();
        if (world == null) return null;
        Vector v = player.getVelocity();
        if (v == null) return null;
        try {
            var position = new MovementPacketBuffer.Position(
                    loc.getX(), loc.getY(), loc.getZ());
            var velocity = new MovementPacketBuffer.Velocity(
                    v.getX(), v.getY(), v.getZ());

            int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
            double eye = loc.getY() + player.getEyeHeight();
            if (!Double.isFinite(eye)) return null;
            int headY = (int) Math.floor(eye);
            if (y <= world.getMinHeight() || y >= world.getMaxHeight()
                    || headY < world.getMinHeight() || headY >= world.getMaxHeight()) {
                return null; // Outside known block height: do not fabricate collision context.
            }
            if (!world.isChunkLoaded(x >> 4, z >> 4)) return null;

            Block feet = world.getBlockAt(x, y, z);
            Block below = world.getBlockAt(x, y - 1, z);
            Block head = world.getBlockAt(x, headY, z);
            var env = new MovementTickTimeline.Environment(
                    feet.isPassable(), below.isPassable(), head.isPassable(),
                    feet.isLiquid(), below.isLiquid());
            GameMode mode = player.getGameMode();
            var flags = new MovementTickTimeline.PlayerFlags(
                    player.isOnGround(), player.isFlying(), player.isGliding(),
                    player.isSwimming(), player.isInsideVehicle(),
                    mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR);

            // Ground research uses a tiny allowlist of ordinary full blocks.
            // Slabs, stairs, ice, slime, honey, soul sand, fluids, ladders,
            // plugin flight, unusual speed settings and status effects are
            // explicitly NOT within this research envelope.
            Material belowType = below.getType();
            boolean ordinary = (belowType == Material.STONE
                    || belowType == Material.DIRT
                    || belowType == Material.GRASS_BLOCK
                    || belowType == Material.COBBLESTONE
                    || belowType == Material.DEEPSLATE)
                    && Math.abs(loc.getY() - Math.rint(loc.getY())) < 0.02
                    && !player.getAllowFlight() && !player.isRiptiding();
            var probeContext = new MovementTickTimeline.ProbeContext(
                    true, ordinary, player.getActivePotionEffects().isEmpty(),
                    Math.abs(player.getWalkSpeed() - 0.2F) < 0.0001F);
            return new MovementTickTimeline.TickInput(
                    world.getUID().toString(), position, velocity, flags, env, probeContext);
        } catch (IllegalArgumentException ex) {
            // Skip any malformed coordinate or velocity. Never feed a guessed
            // value to later movement validators.
            return null;
        }
    }
}
