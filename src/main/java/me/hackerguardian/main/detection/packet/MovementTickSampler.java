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
        return sample(player, false);
    }

    public static MovementTickTimeline.TickInput sample(Player player, boolean collectProbeContext) {
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

            if (!collectProbeContext) {
                // No extra potion/inventory/ground-profile work at all when
                // the research probe is disabled.
                return new MovementTickTimeline.TickInput(
                        world.getUID().toString(), position, velocity, flags, env);
            }

            // Ground research uses a tiny allowlist of ordinary full blocks.
            // Slabs, stairs, ice, slime, honey, soul sand, fluids, ladders,
            // plugin flight, unusual speed settings and status effects are
            // explicitly NOT within this research envelope.
            Material belowType = below.getType();
            // An intentionally conservative allowlist of ordinary full
            // blocks. In particular, DIRT_PATH is NOT a full block and must
            // remain ineligible: feet may be inside its fractional hitbox.
            boolean supportedBlock = switch (belowType) {
                case STONE, DIRT, GRASS_BLOCK, COBBLESTONE, DEEPSLATE,
                        STONE_BRICKS, OAK_PLANKS, SPRUCE_PLANKS,
                        BIRCH_PLANKS, ANDESITE, DIORITE, GRANITE, SANDSTONE -> true;
                default -> false;
            };
            boolean levelY = Math.abs(loc.getY() - Math.rint(loc.getY())) < 0.02;
            boolean allowedFlight = player.getAllowFlight();
            boolean riptiding = player.isRiptiding();
            boolean ordinary = supportedBlock && levelY && !allowedFlight && !riptiding;

            // Only collect these explanations when the opt-in research probe
            // is enabled. All values are simple text derived on the main
            // thread, not Bukkit objects retained across callbacks.
            String reason;
            if (!feet.isPassable()) {
                reason = "feet=" + feet.getType().name();
            } else if (!head.isPassable()) {
                reason = "head=" + head.getType().name();
            } else if (!supportedBlock) {
                reason = "below=" + belowType.name();
            } else if (!levelY) {
                double fraction = loc.getY() - Math.floor(loc.getY());
                reason = "feetY-offset=" + (Math.round(fraction * 1000.0) / 1000.0);
            } else if (allowedFlight) {
                reason = "flight permission enabled";
            } else if (riptiding) {
                reason = "riptide active";
            } else if (below.isPassable() || feet.isLiquid() || below.isLiquid()) {
                reason = "block/liquid collision hints";
            } else {
                reason = "";
            }

            var probeContext = new MovementTickTimeline.ProbeContext(
                    true, ordinary, player.getActivePotionEffects().isEmpty(),
                    Math.abs(player.getWalkSpeed() - 0.2F) < 0.0001F, reason);
            return new MovementTickTimeline.TickInput(
                    world.getUID().toString(), position, velocity, flags, env, probeContext);
        } catch (IllegalArgumentException ex) {
            // Skip any malformed coordinate or velocity. Never feed a guessed
            // value to later movement validators.
            return null;
        }
    }
}
