package me.hackerguardian.main.detection.deterministic;

import me.hackerguardian.main.detection.DetectionCategory;
import me.hackerguardian.main.detection.DetectionFinding;
import me.hackerguardian.main.detection.EvidenceStrength;
import me.hackerguardian.main.utils.Tps;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic reach and occlusion checks for mining and block placement. */
public final class BlockInteractionCheckListener implements Listener {

    public static final String MINING_REACH_ID = "mining.reach";
    public static final String MINING_WALL_ID = "mining.through-wall";
    public static final String PLACE_REACH_ID = "world.place-reach";
    public static final String PLACE_WALL_ID = "world.place-through-wall";

    private final DeterministicEvidenceBuffer evidenceBuffer;
    private final boolean reachEnabled;
    private final double maxDistance;
    private final double hardExcessDistance;
    private final boolean lineOfSightEnabled;
    private final double lineOfSightMinimumDistance;

    public BlockInteractionCheckListener(DeterministicEvidenceBuffer evidenceBuffer,
                                         boolean reachEnabled,
                                         double maxDistance,
                                         double hardExcessDistance,
                                         boolean lineOfSightEnabled,
                                         double lineOfSightMinimumDistance) {
        this.evidenceBuffer = evidenceBuffer;
        this.reachEnabled = reachEnabled;
        this.maxDistance = Math.max(4.5, Math.min(12.0, maxDistance));
        this.hardExcessDistance = Math.max(0.25, Math.min(5.0, hardExcessDistance));
        this.lineOfSightEnabled = lineOfSightEnabled;
        this.lineOfSightMinimumDistance = Math.max(0.5, Math.min(5.0, lineOfSightMinimumDistance));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        Player player = event.getPlayer();
        if (!eligible(player)) return;
        evaluate(player, event.getBlock(), MINING_REACH_ID, MINING_WALL_ID, "mine");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (!eligible(player)) return;
        evaluate(player, event.getBlockAgainst(), PLACE_REACH_ID, PLACE_WALL_ID, "place against");
    }

    private void evaluate(Player player,
                          Block target,
                          String reachId,
                          String wallId,
                          String action) {
        if (target == null || target.getWorld() != player.getWorld()) return;

        BoundingBox box = safeBoundingBox(target);
        Location eye = player.getEyeLocation();
        double distance = GeometryMath.distanceToAabb(
                eye.getX(), eye.getY(), eye.getZ(),
                box.getMinX(), box.getMinY(), box.getMinZ(),
                box.getMaxX(), box.getMaxY(), box.getMaxZ()
        );

        if (reachEnabled && distance > maxDistance) {
            double excess = distance - maxDistance;
            double score = clamp(0.70 + ((excess / Math.max(0.1, hardExcessDistance)) * 0.30));
            EvidenceStrength strength = excess >= hardExcessDistance
                    ? EvidenceStrength.HARD
                    : EvidenceStrength.STRONG;

            Map<String, Double> evidence = commonEvidence(player, distance);
            evidence.put("max_distance", maxDistance);
            evidence.put("excess_distance", excess);

            evidenceBuffer.record(player.getUniqueId(), new DetectionFinding(
                    reachId,
                    DetectionCategory.INTERACTION,
                    score,
                    strength == EvidenceStrength.HARD ? 0.99 : 0.94,
                    strength,
                    "Player attempted to " + action + " a block beyond the conservative interaction-distance envelope.",
                    evidence
            ));
        }

        if (lineOfSightEnabled
                && distance >= lineOfSightMinimumDistance
                && fullyOccluded(player, target, box)) {
            Map<String, Double> evidence = commonEvidence(player, distance);
            evidence.put("sampled_rays", 7.0);
            evidence.put("minimum_distance", lineOfSightMinimumDistance);

            evidenceBuffer.record(player.getUniqueId(), new DetectionFinding(
                    wallId,
                    DetectionCategory.INTERACTION,
                    0.90,
                    0.90,
                    EvidenceStrength.STRONG,
                    "Every sampled eye-to-block ray was occluded by another collidable block.",
                    evidence
            ));
        }
    }

    private static Map<String, Double> commonEvidence(Player player, double distance) {
        Map<String, Double> evidence = new LinkedHashMap<>();
        evidence.put("distance_to_hitbox", distance);
        evidence.put("ping_ms", (double) player.getPing());
        evidence.put("tps", Tps.getTPS());
        return evidence;
    }

    private static boolean fullyOccluded(Player player, Block target, BoundingBox box) {
        Location eye = player.getEyeLocation();
        World world = player.getWorld();

        double minX = box.getMinX();
        double minY = box.getMinY();
        double minZ = box.getMinZ();
        double maxX = box.getMaxX();
        double maxY = box.getMaxY();
        double maxZ = box.getMaxZ();
        double cx = (minX + maxX) * 0.5;
        double cy = (minY + maxY) * 0.5;
        double cz = (minZ + maxZ) * 0.5;
        double inset = 0.01;

        double[][] points = new double[][]{
                {cx, cy, cz},
                {minX + inset, cy, cz},
                {maxX - inset, cy, cz},
                {cx, minY + inset, cz},
                {cx, maxY - inset, cz},
                {cx, cy, minZ + inset},
                {cx, cy, maxZ - inset}
        };

        for (double[] point : points) {
            Vector direction = new Vector(
                    point[0] - eye.getX(),
                    point[1] - eye.getY(),
                    point[2] - eye.getZ()
            );
            double distance = direction.length();
            if (distance <= 1.0E-6) return false;
            direction.multiply(1.0 / distance);

            RayTraceResult result;
            try {
                result = world.rayTraceBlocks(
                        eye,
                        direction,
                        distance + 0.08,
                        FluidCollisionMode.NEVER,
                        true
                );
            } catch (Throwable ignored) {
                return false;
            }

            if (result == null || result.getHitBlock() == null || result.getHitBlock().equals(target)) {
                return false;
            }
        }
        return true;
    }

    private static BoundingBox safeBoundingBox(Block block) {
        try {
            BoundingBox box = block.getBoundingBox();
            if (box != null) return box;
        } catch (Throwable ignored) {
        }
        return new BoundingBox(
                block.getX(), block.getY(), block.getZ(),
                block.getX() + 1.0, block.getY() + 1.0, block.getZ() + 1.0
        );
    }

    private static boolean eligible(Player player) {
        if (player == null) return false;
        GameMode mode = player.getGameMode();
        return mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
