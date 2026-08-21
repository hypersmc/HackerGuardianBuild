package me.hackerguardian.main.aicore;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketEvent;
import me.hackerguardian.main.utils.Tps;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central place to collect per-player raw signals (movement, combat, packets)
 * and aggregate them into fixed-size feature vectors for the AI.
 *
 * The idea:
 *   - Keep a small rolling window of stats per player.
 *   - Every so often, call buildSample(player) to get a double[].
 */
public class FeatureCollector {

    public static final int FEATURE_COUNT = 40;
    public static final String[] FEATURE_NAMES = new String[]{
            // Movement
            "avgSpeed",
            "maxHorizontalSpeed",
            "acceleration",
            "yawDeltaAvg",
            "yawDeltaStd",
            "pitchDeltaAvg",
            "pitchDeltaStd",
            "groundRatio",
            "airRatio",
            "jumpCount",

            // Combat
            "cps",
            "hitRate",
            "avgHitDistance",
            "maxHitDistance",
            "yawDeltaOnHitAvg",
            "pitchDeltaOnHitAvg",

            // Packets
            "flyingPacketsPerSecond",
            "lookPacketsPerSecond",
            "positionPacketsPerSecond",
            "keepAlivePacketsPerSecond",
            "keepAliveIntervalAvgMs",
            "keepAliveIntervalStdMs",

            // Context
            "inWater",
            "onLadder",
            "hasSpeedEffect",
            "hasJumpBoostEffect",
            "isSprinting",
            "isSneaking",
            "gmSurvival",
            "gmCreative",
            "gmAdventure",
            "gmSpectator",

            // Networking / server
            "pingMs",
            "tps",

            // NEW: Block stats
            "blocksBroken",
            "avgBreakDistance",
            "maxBreakDistance",
            "blocksPlaced",
            "avgPlaceDistance",
            "maxPlaceDistance"
    };
    // How long a window spans in ms (e.g. last 2 seconds)
    private static final long WINDOW_MS = 2000L;
    // Minimum number of movement ticks before we consider a sample usable
    private static final int MIN_MOVEMENT_TICKS = 5;

    // Per-player rolling state
    private final Map<UUID, PlayerWindow> windows = new ConcurrentHashMap<>();

    /**
     * Must be called from PlayerMoveEvent handler.
     * <br> e.g. HGPlayerMoveListener
     */
    public void recordMovement(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        PlayerWindow window = windows.computeIfAbsent(uuid, k -> new PlayerWindow());

        window.recordMovement(player, event);
    }

    /**
     * Must be called from EntityDamageByEntityEvent (combat).
     * <br> e.g. HGEntityDamageByEntityListener
     */
    public void recordCombat(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) {
            return;
        }
        Player attacker = (Player) event.getDamager();
        UUID uuid = attacker.getUniqueId();
        PlayerWindow window = windows.computeIfAbsent(uuid, k -> new PlayerWindow());

        window.recordCombat(attacker, event);
    }

    /**
     * Must be called from custom ProtocolLib packet listener.
     * <br> e.g. HGPacketListener
     */
    public void recordPacket(PacketEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        PlayerWindow window = windows.computeIfAbsent(uuid, k -> new PlayerWindow());

        window.recordPacket(player, event);
    }

    /**
     * Must be called from BlockBreakEvent
     * <br> e.g. HGBlockBreakListener
     */
    public void recordBlockBreak(Player player, BlockBreakEvent event) {
        UUID uuid = player.getUniqueId();
        PlayerWindow window = windows.computeIfAbsent(uuid, k -> new PlayerWindow());
        window.recordBlockBreak(player, event);
    }

    /**
     * Must be called from BlockPlaceEvent
     * <br> e.g. HGBlockPlaceListener
     */
    public void recordBlockPlace(Player player, BlockPlaceEvent event) {
        UUID uuid = player.getUniqueId();
        PlayerWindow window = windows.computeIfAbsent(uuid, k -> new PlayerWindow());
        window.recordBlockPlace(player, event);
    }

    /**
     * Builds a feature vector for the given player based on the current window.
     * Returns null if we don't have enough data yet.
     */
    public double[] buildSample(Player player) {
        PlayerWindow window = windows.get(player.getUniqueId());
        if (window == null) {
            return null;
        }
        return window.buildFeatures(player);
    }

    /**
     * Optionally call this when a player quits to free memory.
     */
    public void clearPlayer(UUID uuid) {
        windows.remove(uuid);
    }

    // ---------------------- INTERNAL STATE PER PLAYER ----------------------

    private static class PlayerWindow {
        // Time range
        private long windowStart = -1L;
        private long lastUpdate = -1L;

        // Movement stats
        private int moveTicks = 0;
        private double totalHorizontalDistance = 0.0;
        private double maxHorizontalSpeed = 0.0;
        private double lastHorizontalSpeed = 0.0;

        private double yawDeltaSum = 0.0;
        private double yawDeltaSqSum = 0.0;
        private double pitchDeltaSum = 0.0;
        private double pitchDeltaSqSum = 0.0;
        private double lastYaw = Double.NaN;
        private double lastPitch = Double.NaN;

        private int groundTicks = 0;
        private int airTicks = 0;
        private int jumpCount = 0;

        // Combat stats
        private int swingCount = 0;
        private int hitCount = 0;
        private double totalHitDistance = 0.0;
        private double maxHitDistance = 0.0;
        private double yawDeltaOnHitSum = 0.0;
        private double pitchDeltaOnHitSum = 0.0;

        // Packet stats
        private int flyingPackets = 0;
        private int lookPackets = 0;
        private int positionPackets = 0;
        private int keepAlivePackets = 0;
        private final List<Long> keepAliveIntervals = new ArrayList<>();
        private long lastKeepAliveTime = -1L;

        // Context (latest snapshot)
        private boolean inWater = false;
        private boolean onLadder = false;
        private boolean hasSpeed = false;
        private boolean hasJumpBoost = false;
        private boolean isSprinting = false;
        private boolean isSneaking = false;
        private GameMode gameMode = GameMode.SURVIVAL;

        // Block stats
        private int blocksBroken = 0;
        private double totalBreakDistance = 0.0;
        private double maxBreakDistance = 0.0;

        private int blocksPlaced = 0;
        private double totalPlaceDistance = 0.0;
        private double maxPlaceDistance = 0.0;

        private void ensureWindowStart(long now) {
            if (windowStart < 0L) {
                windowStart = now;
            }
            lastUpdate = now;
        }

        private void maybeResetWindow(long now) {
            if (windowStart < 0L) {
                return;
            }
            long span = now - windowStart;
            if (span > WINDOW_MS) {
                // hard reset; we could also slide the window instead
                reset(now);
            }
        }

        private void reset(long now) {
            windowStart = now;
            lastUpdate = now;

            moveTicks = 0;
            totalHorizontalDistance = 0.0;
            maxHorizontalSpeed = 0.0;
            lastHorizontalSpeed = 0.0;

            yawDeltaSum = 0.0;
            yawDeltaSqSum = 0.0;
            pitchDeltaSum = 0.0;
            pitchDeltaSqSum = 0.0;
            lastYaw = Double.NaN;
            lastPitch = Double.NaN;

            groundTicks = 0;
            airTicks = 0;
            jumpCount = 0;

            swingCount = 0;
            hitCount = 0;
            totalHitDistance = 0.0;
            maxHitDistance = 0.0;
            yawDeltaOnHitSum = 0.0;
            pitchDeltaOnHitSum = 0.0;

            flyingPackets = 0;
            lookPackets = 0;
            positionPackets = 0;
            keepAlivePackets = 0;
            keepAliveIntervals.clear();
            lastKeepAliveTime = -1L;

            blocksBroken = 0;
            totalBreakDistance = 0.0;
            maxBreakDistance = 0.0;

            blocksPlaced = 0;
            totalPlaceDistance = 0.0;
            maxPlaceDistance = 0.0;
        }

        public void recordMovement(Player player, PlayerMoveEvent event) {
            long now = System.currentTimeMillis();
            ensureWindowStart(now);
            maybeResetWindow(now);

            Location from = event.getFrom();
            Location to = event.getTo();
            if (to == null) {
                return;
            }

            double dx = to.getX() - from.getX();
            double dz = to.getZ() - from.getZ();
            double horizontalDistance = Math.sqrt(dx * dx + dz * dz);

            // Spigot/Paper runs at ~20 ticks/sec
            double horizontalSpeed = horizontalDistance * 20.0;

            totalHorizontalDistance += horizontalDistance;
            maxHorizontalSpeed = Math.max(maxHorizontalSpeed, horizontalSpeed);
            lastHorizontalSpeed = horizontalSpeed;

            // Yaw/pitch changes
            float yaw = to.getYaw();
            float pitch = to.getPitch();

            if (!Double.isNaN(lastYaw)) {
                double dyaw = wrapAngle(yaw - lastYaw);
                double dpitch = pitch - lastPitch;

                yawDeltaSum += Math.abs(dyaw);
                yawDeltaSqSum += dyaw * dyaw;
                pitchDeltaSum += Math.abs(dpitch);
                pitchDeltaSqSum += dpitch * dpitch;
            }
            lastYaw = yaw;
            lastPitch = pitch;

            // Ground / air ratio, naive jump detection
            boolean onGround = player.isOnGround();
            if (onGround) {
                groundTicks++;
            } else {
                airTicks++;
            }

            // Quick&dirty jump approximation: moved up while previously on ground
            if (onGround && to.getY() > from.getY() + 0.3) {
                jumpCount++;
            }

            // Context snapshot
            updateContext(player);

            moveTicks++;
        }

        public void recordCombat(Player attacker, EntityDamageByEntityEvent event) {
            long now = System.currentTimeMillis();
            ensureWindowStart(now);
            maybeResetWindow(now);

            Entity victim = event.getEntity();
            swingCount++; // any attack implies at least a swing (we could refine this with animation packets)

            if (event.getFinalDamage() > 0.0) {
                hitCount++;
                double distance = attacker.getLocation().distance(victim.getLocation());
                totalHitDistance += distance;
                maxHitDistance = Math.max(maxHitDistance, distance);

                // Aim deltas at hit moment (relative to victim)
                Location aLoc = attacker.getLocation();
                Location vLoc = victim.getLocation();
                double yawToVictim = aLoc.getYaw() - aLoc.clone().setDirection(vLoc.toVector().subtract(aLoc.toVector())).getYaw();
                double pitchToVictim = aLoc.getPitch() - aLoc.clone().setDirection(vLoc.toVector().subtract(aLoc.toVector())).getPitch();

                yawDeltaOnHitSum += Math.abs(wrapAngle(yawToVictim));
                pitchDeltaOnHitSum += Math.abs(pitchToVictim);
            }

            updateContext(attacker);
        }

        public void recordPacket(Player player, PacketEvent event) {
            long now = System.currentTimeMillis();
            ensureWindowStart(now);
            maybeResetWindow(now);

            PacketType type = event.getPacketType();

            if (type == PacketType.Play.Client.FLYING) {
                flyingPackets++;
            } else if (type == PacketType.Play.Client.LOOK) {
                lookPackets++;
            } else if (type == PacketType.Play.Client.POSITION || type == PacketType.Play.Client.POSITION_LOOK) {
                positionPackets++;
            } else if (type == PacketType.Play.Client.KEEP_ALIVE) {
                keepAlivePackets++;
                if (lastKeepAliveTime > 0L) {
                    long interval = now - lastKeepAliveTime;
                    keepAliveIntervals.add(interval);
                }
                lastKeepAliveTime = now;
            }

            // Context might change based on packets too
            updateContext(player);
        }

        public void recordBlockBreak(Player player, BlockBreakEvent event) {
            long now = System.currentTimeMillis();
            ensureWindowStart(now);
            maybeResetWindow(now);

            Location playerLoc = player.getLocation();
            Location blockLoc = event.getBlock().getLocation().add(0.5, 0.5, 0.5); // center of block

            double distance = playerLoc.distance(blockLoc);

            blocksBroken++;
            totalBreakDistance += distance;
            maxBreakDistance = Math.max(maxBreakDistance, distance);

            updateContext(player);
        }

        public void recordBlockPlace(Player player, BlockPlaceEvent event) {
            long now = System.currentTimeMillis();
            ensureWindowStart(now);
            maybeResetWindow(now);

            Location playerLoc = player.getLocation();
            Location blockLoc = event.getBlock().getLocation().add(0.5, 0.5, 0.5);

            double distance = playerLoc.distance(blockLoc);

            blocksPlaced++;
            totalPlaceDistance += distance;
            maxPlaceDistance = Math.max(maxPlaceDistance, distance);

            updateContext(player);
        }

        private void updateContext(Player player) {
            inWater = player.getLocation().getBlock().isLiquid();
            // Ladder check is simplified here; adapt to your server version as needed
            onLadder = player.getLocation().getBlock().getType().name().contains("LADDER");

            hasSpeed = player.hasPotionEffect(PotionEffectType.SPEED);
            hasJumpBoost = player.hasPotionEffect(PotionEffectType.JUMP);

            isSprinting = player.isSprinting();
            isSneaking = player.isSneaking();
            gameMode = player.getGameMode();
        }

        /**
         * Build the final feature vector.
         * Adjust the order – just keep size consistent with the AI.
         */
        public double[] buildFeatures(Player player) {
            if (moveTicks < MIN_MOVEMENT_TICKS) {
                return null;
            }
            long now = System.currentTimeMillis();
            long spanMs = Math.max(1L, now - windowStart);
            double spanSeconds = spanMs / 1000.0;

            // Movement aggregates
            double avgSpeed = spanSeconds > 0 ? (totalHorizontalDistance / spanSeconds) : 0.0;
            double accel = spanSeconds > 0 ? (lastHorizontalSpeed - (avgSpeed)) / spanSeconds : 0.0;

            double yawAvg = moveTicks > 0 ? yawDeltaSum / moveTicks : 0.0;
            double yawVar = moveTicks > 0 ? (yawDeltaSqSum / moveTicks) - (yawAvg * yawAvg) : 0.0;
            double yawStd = yawVar > 0 ? Math.sqrt(yawVar) : 0.0;

            double pitchAvg = moveTicks > 0 ? pitchDeltaSum / moveTicks : 0.0;
            double pitchVar = moveTicks > 0 ? (pitchDeltaSqSum / moveTicks) - (pitchAvg * pitchAvg) : 0.0;
            double pitchStd = pitchVar > 0 ? Math.sqrt(pitchVar) : 0.0;

            int totalAirGroundTicks = groundTicks + airTicks;
            double groundRatio = totalAirGroundTicks > 0 ? (double) groundTicks / totalAirGroundTicks : 0.0;
            double airRatio = totalAirGroundTicks > 0 ? (double) airTicks / totalAirGroundTicks : 0.0;

            // Combat aggregates
            double cps = spanSeconds > 0 ? swingCount / spanSeconds : 0.0;
            double hitRate = swingCount > 0 ? (double) hitCount / swingCount : 0.0;
            double avgHitDistance = hitCount > 0 ? totalHitDistance / hitCount : 0.0;
            double yawOnHitAvg = hitCount > 0 ? yawDeltaOnHitSum / hitCount : 0.0;
            double pitchOnHitAvg = hitCount > 0 ? pitchDeltaOnHitSum / hitCount : 0.0;

            // Packet aggregates
            double flyingRate = spanSeconds > 0 ? flyingPackets / spanSeconds : 0.0;
            double lookRate = spanSeconds > 0 ? lookPackets / spanSeconds : 0.0;
            double positionRate = spanSeconds > 0 ? positionPackets / spanSeconds : 0.0;
            double keepAliveRate = spanSeconds > 0 ? keepAlivePackets / spanSeconds : 0.0;

            double keepAliveAvg = 0.0;
            double keepAliveStd = 0.0;
            if (!keepAliveIntervals.isEmpty()) {
                double sum = 0.0;
                for (long v : keepAliveIntervals) sum += v;
                keepAliveAvg = sum / keepAliveIntervals.size();
                double var = 0.0;
                for (long v : keepAliveIntervals) {
                    double d = v - keepAliveAvg;
                    var += d * d;
                }
                var /= keepAliveIntervals.size();
                keepAliveStd = Math.sqrt(var);
            }

            // Context / meta
            double ping = player.getPing();
            double tps = Tps.getTPS();

            // Encode context booleans / enums as 0/1
            double gmSurvival = gameMode == GameMode.SURVIVAL ? 1.0 : 0.0;
            double gmCreative = gameMode == GameMode.CREATIVE ? 1.0 : 0.0;
            double gmSpectator = gameMode == GameMode.SPECTATOR ? 1.0 : 0.0;
            double gmAdventure = gameMode == GameMode.ADVENTURE ? 1.0 : 0.0;

            // compute block stats
            double avgBreakDistance = blocksBroken > 0 ? totalBreakDistance / blocksBroken : 0.0;
            double avgPlaceDistance = blocksPlaced > 0 ? totalPlaceDistance / blocksPlaced : 0.0;

            // ----- FINAL FEATURE VECTOR -----
            // If we change size/order here, make sure the AI model matches.
            // e.g. FEATURE_NAMES (from the top of the file)

            double[] features = new double[]{
                    // Movement
                    avgSpeed,
                    maxHorizontalSpeed,
                    accel,
                    yawAvg,
                    yawStd,
                    pitchAvg,
                    pitchStd,
                    groundRatio,
                    airRatio,
                    jumpCount,

                    // Combat
                    cps,
                    hitRate,
                    avgHitDistance,
                    maxHitDistance,
                    yawOnHitAvg,
                    pitchOnHitAvg,

                    // Packets
                    flyingRate,
                    lookRate,
                    positionRate,
                    keepAliveRate,
                    keepAliveAvg,
                    keepAliveStd,

                    // Context
                    inWater ? 1.0 : 0.0,
                    onLadder ? 1.0 : 0.0,
                    hasSpeed ? 1.0 : 0.0,
                    hasJumpBoost ? 1.0 : 0.0,
                    isSprinting ? 1.0 : 0.0,
                    isSneaking ? 1.0 : 0.0,
                    gmSurvival,
                    gmCreative,
                    gmAdventure,
                    gmSpectator,

                    // Networking / server state
                    ping,
                    tps,

                    // NEW: Block stats
                    blocksBroken,
                    avgBreakDistance,
                    maxBreakDistance,
                    blocksPlaced,
                    avgPlaceDistance,
                    maxPlaceDistance
            };
            // sanity check so I notice if I've added/removed features and forget to update FEATURE_COUNT
            if (features.length != FEATURE_COUNT || FEATURE_NAMES.length != FEATURE_COUNT) {
                throw new IllegalStateException("Feature configuration mismatch: " +
                        "FEATURE_COUNT=" + FEATURE_COUNT +
                        ", vectorLength=" + features.length +
                        ", namesLength=" + FEATURE_NAMES.length);
            }
            return features;
        }

        private static double wrapAngle(double angle) {
            angle %= 360.0;
            if (angle <= -180.0) angle += 360.0;
            if (angle > 180.0) angle -= 360.0;
            return angle;
        }

    }
}