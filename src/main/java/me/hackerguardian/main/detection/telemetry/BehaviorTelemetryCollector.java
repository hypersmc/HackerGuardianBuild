package me.hackerguardian.main.detection.telemetry;

import me.hackerguardian.main.utils.Tps;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded rolling telemetry used by Detection v2.
 *
 * This collector intentionally does not know about neural networks, labels,
 * punishments, warnings, or database persistence. It only turns recent Bukkit
 * observations into a typed {@link BehaviorSnapshot}.
 */
public final class BehaviorTelemetryCollector {

    private static final double POSITION_EPSILON = 1.0E-7;

    private final long windowMs;
    private final Map<UUID, PlayerWindow> windows = new ConcurrentHashMap<>();

    public BehaviorTelemetryCollector(long windowMs) {
        this.windowMs = Math.max(1000L, Math.min(windowMs, 60_000L));
    }

    public long getWindowMs() {
        return windowMs;
    }

    public int getTrackedPlayerCount() {
        return windows.size();
    }

    public void recordMovement(PlayerMoveEvent event) {
        if (event == null || event instanceof PlayerTeleportEvent || event.getTo() == null) return;
        Player player = event.getPlayer();
        windows.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerWindow())
                .recordMovement(event);
    }

    public void recordSwing(PlayerAnimationEvent event) {
        if (event == null) return;
        Player player = event.getPlayer();
        windows.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerWindow())
                .recordSwing(System.currentTimeMillis());
    }

    public void recordCombat(EntityDamageByEntityEvent event) {
        if (event == null || !(event.getDamager() instanceof Player)) return;
        Player attacker = (Player) event.getDamager();
        windows.computeIfAbsent(attacker.getUniqueId(), ignored -> new PlayerWindow())
                .recordHit(attacker, event.getEntity(), System.currentTimeMillis());
    }

    public void recordBlockBreak(BlockBreakEvent event) {
        if (event == null) return;
        Player player = event.getPlayer();
        windows.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerWindow())
                .recordBlockBreak(player, event, System.currentTimeMillis());
    }

    public void recordBlockPlace(BlockPlaceEvent event) {
        if (event == null) return;
        Player player = event.getPlayer();
        windows.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerWindow())
                .recordBlockPlace(player, event, System.currentTimeMillis());
    }

    public BehaviorSnapshot snapshot(Player player) {
        if (player == null) return null;
        long now = System.currentTimeMillis();
        PlayerWindow window = windows.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerWindow());
        return window.snapshot(player, now, windowMs);
    }

    public void clearPlayer(UUID playerId) {
        if (playerId != null) windows.remove(playerId);
    }

    public void clear() {
        windows.clear();
    }

    private static final class PlayerWindow {
        private final Deque<MovementSample> movements = new ArrayDeque<>();
        private final Deque<Long> swings = new ArrayDeque<>();
        private final Deque<HitSample> hits = new ArrayDeque<>();
        private final Deque<BlockSample> breaks = new ArrayDeque<>();
        private final Deque<BlockSample> places = new ArrayDeque<>();

        synchronized void recordMovement(PlayerMoveEvent event) {
            long now = System.currentTimeMillis();
            Location from = event.getFrom();
            Location to = event.getTo();
            if (to == null || from.getWorld() == null || to.getWorld() == null
                    || !from.getWorld().equals(to.getWorld())) {
                return;
            }

            double dx = to.getX() - from.getX();
            double dz = to.getZ() - from.getZ();
            double horizontalDelta = Math.sqrt(dx * dx + dz * dz);
            double yawDelta = Math.abs(wrapAngle(to.getYaw() - from.getYaw()));
            double pitchDelta = Math.abs(to.getPitch() - from.getPitch());

            // Ignore events that contain no position or look change at all.
            if (horizontalDelta < POSITION_EPSILON
                    && Math.abs(to.getY() - from.getY()) < POSITION_EPSILON
                    && yawDelta < POSITION_EPSILON
                    && pitchDelta < POSITION_EPSILON) {
                return;
            }

            movements.addLast(new MovementSample(
                    now,
                    horizontalDelta,
                    horizontalDelta * 20.0,
                    yawDelta,
                    pitchDelta,
                    event.getPlayer().isOnGround()
            ));
        }

        synchronized void recordSwing(long now) {
            swings.addLast(now);
        }

        synchronized void recordHit(Player attacker, Entity victim, long now) {
            if (attacker == null || victim == null) return;
            Location attackerLocation = attacker.getLocation();
            Location victimLocation = victim.getLocation();
            if (attackerLocation.getWorld() == null || victimLocation.getWorld() == null
                    || !attackerLocation.getWorld().equals(victimLocation.getWorld())) {
                return;
            }
            hits.addLast(new HitSample(now, attackerLocation.distance(victimLocation)));
        }

        synchronized void recordBlockBreak(Player player, BlockBreakEvent event, long now) {
            breaks.addLast(new BlockSample(now, distanceToBlockCenter(player, event.getBlock().getLocation())));
        }

        synchronized void recordBlockPlace(Player player, BlockPlaceEvent event, long now) {
            places.addLast(new BlockSample(now, distanceToBlockCenter(player, event.getBlock().getLocation())));
        }

        synchronized BehaviorSnapshot snapshot(Player player, long now, long windowMs) {
            long cutoff = now - windowMs;
            trim(cutoff);

            int movementCount = movements.size();
            double totalHorizontalDistance = 0.0;
            double maxHorizontalSpeed = 0.0;
            double maxHorizontalDelta = 0.0;
            double yawSum = 0.0;
            double yawSqSum = 0.0;
            double pitchSum = 0.0;
            double pitchSqSum = 0.0;
            int groundCount = 0;

            long movementSpanMs = windowMs;
            if (!movements.isEmpty()) {
                movementSpanMs = Math.max(250L, now - movements.peekFirst().timestampMs);
                movementSpanMs = Math.min(windowMs, movementSpanMs);
            }

            for (MovementSample sample : movements) {
                totalHorizontalDistance += sample.horizontalDelta;
                maxHorizontalSpeed = Math.max(maxHorizontalSpeed, sample.horizontalSpeed);
                maxHorizontalDelta = Math.max(maxHorizontalDelta, sample.horizontalDelta);
                yawSum += sample.yawDelta;
                yawSqSum += sample.yawDelta * sample.yawDelta;
                pitchSum += sample.pitchDelta;
                pitchSqSum += sample.pitchDelta * sample.pitchDelta;
                if (sample.onGround) groundCount++;
            }

            double movementSeconds = Math.max(0.25, movementSpanMs / 1000.0);
            double averageHorizontalSpeed = totalHorizontalDistance / movementSeconds;
            double averageYaw = movementCount == 0 ? 0.0 : yawSum / movementCount;
            double averagePitch = movementCount == 0 ? 0.0 : pitchSum / movementCount;
            double yawVariance = movementCount == 0 ? 0.0
                    : Math.max(0.0, (yawSqSum / movementCount) - (averageYaw * averageYaw));
            double pitchVariance = movementCount == 0 ? 0.0
                    : Math.max(0.0, (pitchSqSum / movementCount) - (averagePitch * averagePitch));
            double groundRatio = movementCount == 0 ? 0.0 : (double) groundCount / movementCount;

            int swingCount = swings.size();
            double windowSeconds = Math.max(1.0, windowMs / 1000.0);
            double swingCps = swingCount / windowSeconds;
            int hitCount = hits.size();
            double hitRate = swingCount == 0 ? 0.0 : Math.min(1.0, (double) hitCount / swingCount);
            double hitDistanceTotal = 0.0;
            double maxHitDistance = 0.0;
            for (HitSample hit : hits) {
                hitDistanceTotal += hit.distance;
                maxHitDistance = Math.max(maxHitDistance, hit.distance);
            }
            double averageHitDistance = hitCount == 0 ? 0.0 : hitDistanceTotal / hitCount;

            double maxBreakDistance = 0.0;
            for (BlockSample sample : breaks) maxBreakDistance = Math.max(maxBreakDistance, sample.distance);
            double maxPlaceDistance = 0.0;
            for (BlockSample sample : places) maxPlaceDistance = Math.max(maxPlaceDistance, sample.distance);

            Location current = player.getLocation();
            String blockType = current.getBlock().getType().name();
            boolean inWater = current.getBlock().isLiquid();
            boolean onLadder = blockType.contains("LADDER") || blockType.contains("VINE");

            return BehaviorSnapshot.builder(player.getUniqueId(), player.getName(), now, windowMs)
                    .worldName(player.getWorld().getName())
                    .movement(
                            movementCount,
                            averageHorizontalSpeed,
                            maxHorizontalSpeed,
                            maxHorizontalDelta,
                            averageYaw,
                            Math.sqrt(yawVariance),
                            averagePitch,
                            Math.sqrt(pitchVariance),
                            groundRatio
                    )
                    .combat(
                            swingCount,
                            swingCps,
                            hitCount,
                            hitRate,
                            averageHitDistance,
                            maxHitDistance
                    )
                    .blocks(breaks.size(), maxBreakDistance, places.size(), maxPlaceDistance)
                    .context(
                            player.getPing(),
                            Tps.getTPS(),
                            player.getGameMode(),
                            player.isSprinting(),
                            player.isSneaking(),
                            inWater,
                            onLadder,
                            player.hasPotionEffect(PotionEffectType.SPEED),
                            hasPotionEffect(player, "JUMP_BOOST", "JUMP"),
                            player.isFlying(),
                            player.isGliding(),
                            player.isInsideVehicle()
                    )
                    .build();
        }

        private void trim(long cutoff) {
            while (!movements.isEmpty() && movements.peekFirst().timestampMs < cutoff) movements.removeFirst();
            while (!swings.isEmpty() && swings.peekFirst() < cutoff) swings.removeFirst();
            while (!hits.isEmpty() && hits.peekFirst().timestampMs < cutoff) hits.removeFirst();
            while (!breaks.isEmpty() && breaks.peekFirst().timestampMs < cutoff) breaks.removeFirst();
            while (!places.isEmpty() && places.peekFirst().timestampMs < cutoff) places.removeFirst();
        }

        private static double distanceToBlockCenter(Player player, Location blockLocation) {
            if (player == null || blockLocation == null || blockLocation.getWorld() == null
                    || player.getWorld() == null || !player.getWorld().equals(blockLocation.getWorld())) {
                return 0.0;
            }
            Location center = blockLocation.clone().add(0.5, 0.5, 0.5);
            return player.getEyeLocation().distance(center);
        }
    }

    private static final class MovementSample {
        final long timestampMs;
        final double horizontalDelta;
        final double horizontalSpeed;
        final double yawDelta;
        final double pitchDelta;
        final boolean onGround;

        MovementSample(long timestampMs,
                       double horizontalDelta,
                       double horizontalSpeed,
                       double yawDelta,
                       double pitchDelta,
                       boolean onGround) {
            this.timestampMs = timestampMs;
            this.horizontalDelta = horizontalDelta;
            this.horizontalSpeed = horizontalSpeed;
            this.yawDelta = yawDelta;
            this.pitchDelta = pitchDelta;
            this.onGround = onGround;
        }
    }

    private static final class HitSample {
        final long timestampMs;
        final double distance;

        HitSample(long timestampMs, double distance) {
            this.timestampMs = timestampMs;
            this.distance = distance;
        }
    }

    private static final class BlockSample {
        final long timestampMs;
        final double distance;

        BlockSample(long timestampMs, double distance) {
            this.timestampMs = timestampMs;
            this.distance = distance;
        }
    }

    private static boolean hasPotionEffect(Player player, String... fieldNames) {
        for (String fieldName : fieldNames) {
            try {
                Object value = PotionEffectType.class.getField(fieldName).get(null);
                if (value instanceof PotionEffectType
                        && player.hasPotionEffect((PotionEffectType) value)) {
                    return true;
                }
            } catch (ReflectiveOperationException | SecurityException ignored) {
                // Field names changed between Bukkit API generations; try the next alias.
            }
        }
        return false;
    }

    private static double wrapAngle(double angle) {
        angle %= 360.0;
        if (angle <= -180.0) angle += 360.0;
        if (angle > 180.0) angle -= 360.0;
        return angle;
    }
}
