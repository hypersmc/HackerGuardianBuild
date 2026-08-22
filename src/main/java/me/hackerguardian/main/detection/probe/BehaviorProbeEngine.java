package me.hackerguardian.main.detection.probe;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import me.hackerguardian.main.HackerGuardian;
import me.hackerguardian.main.detection.learning.UuidV7;
import me.hackerguardian.main.detection.telemetry.BehaviorSnapshot;
import me.hackerguardian.main.replay.view.FakeReplayPlayer;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Client-side, non-world-mutating behavioral probes.
 *
 * A probe is visible only to its target player. Reactions are recorded as
 * normality evidence; attacking or ignoring a probe is never itself a verdict.
 */
public final class BehaviorProbeEngine {

    private final HackerGuardian plugin;
    private final ProtocolManager protocolManager;
    private final ProbeResultRecorder recorder;
    private final boolean enabled;
    private final long minimumCollectedMs;
    private final long cooldownMs;
    private final long cooldownJitterMs;
    private final long durationTicks;
    private final double minDistance;
    private final double maxDistance;
    private final double minYawOffset;
    private final double maxYawOffset;
    private final double fovDegrees;
    private final double rotationEpsilonDegrees;
    private final boolean avoidCombat;

    private final Map<UUID, ActiveProbe> active = new ConcurrentHashMap<>();
    private final Map<UUID, Long> notBefore = new ConcurrentHashMap<>();
    private PacketAdapter useEntityListener;

    public BehaviorProbeEngine(HackerGuardian plugin,
                               FileConfiguration config,
                               ProbeResultRecorder recorder) {
        this.plugin = plugin;
        this.protocolManager = ProtocolLibrary.getProtocolManager();
        this.recorder = recorder;
        String root = "DetectionV2.learning.probes.";
        this.enabled = config.getBoolean(root + "enabled", true);
        this.minimumCollectedMs = hoursToMs(config.getDouble(root + "minimum_collected_hours", 10.0));
        this.cooldownMs = hoursToMs(config.getDouble(root + "cooldown_hours", 24.0));
        this.cooldownJitterMs = hoursToMs(config.getDouble(root + "cooldown_jitter_hours", 12.0));
        this.durationTicks = clamp(config.getLong(root + "duration_ticks", 40L), 10L, 200L);
        this.minDistance = clamp(config.getDouble(root + "min_distance", 3.0), 1.5, 12.0);
        this.maxDistance = Math.max(minDistance, clamp(config.getDouble(root + "max_distance", 5.0), minDistance, 16.0));
        this.minYawOffset = clamp(config.getDouble(root + "min_yaw_offset_degrees", 55.0), 20.0, 170.0);
        this.maxYawOffset = Math.max(minYawOffset, clamp(config.getDouble(root + "max_yaw_offset_degrees", 125.0), minYawOffset, 179.0));
        this.fovDegrees = clamp(config.getDouble(root + "fov_threshold_degrees", 12.0), 1.0, 90.0);
        this.rotationEpsilonDegrees = clamp(config.getDouble(root + "rotation_epsilon_degrees", 1.5), 0.1, 30.0);
        this.avoidCombat = config.getBoolean(root + "avoid_combat", true);
    }

    public void start() {
        if (!enabled) return;
        useEntityListener = new PacketAdapter(plugin, ListenerPriority.HIGHEST, PacketType.Play.Client.USE_ENTITY) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                ActiveProbe probe = active.get(event.getPlayer().getUniqueId());
                if (probe == null) return;
                try {
                    int targetId = event.getPacket().getIntegers().read(0);
                    if (targetId == probe.fake.getEntityId()) {
                        event.setCancelled(true);
                        probe.markAttack(System.currentTimeMillis());
                    }
                } catch (Throwable ignored) {
                    // A ProtocolLib mapping mismatch must never affect normal gameplay.
                }
            }
        };
        protocolManager.addPacketListener(useEntityListener);
    }

    public boolean consider(Player player,
                            BehaviorSnapshot snapshot,
                            UUID sessionId,
                            long collectedMs,
                            long lastProbeMs,
                            String trustSource,
                            long eligibleAfterMs) {
        if (!enabled || player == null || snapshot == null || sessionId == null) return false;
        if (active.containsKey(player.getUniqueId()) || collectedMs < minimumCollectedMs) return false;
        if (!isSafeContext(snapshot)) return false;

        long now = System.currentTimeMillis();
        long earliest = notBefore.computeIfAbsent(player.getUniqueId(), ignored -> {
            if (lastProbeMs <= 0L) return now;
            return safeAdd(lastProbeMs, cooldownMs + randomLong(0L, cooldownJitterMs));
        });
        if (now < earliest) return false;
        return startProbe(player, snapshot, sessionId, trustSource, eligibleAfterMs, false);
    }

    public boolean forceProbe(Player player,
                              BehaviorSnapshot snapshot,
                              UUID sessionId,
                              String trustSource,
                              long eligibleAfterMs) {
        return enabled && startProbe(player, snapshot, sessionId, trustSource, eligibleAfterMs, true);
    }

    public void onMove(PlayerMoveEvent event) {
        if (event == null || event.getTo() == null) return;
        ActiveProbe probe = active.get(event.getPlayer().getUniqueId());
        if (probe == null) return;
        probe.observeMove(event.getPlayer(), event.getFrom(), event.getTo(), fovDegrees, rotationEpsilonDegrees);
    }

    public void onSwing(PlayerAnimationEvent event) {
        if (event == null) return;
        ActiveProbe probe = active.get(event.getPlayer().getUniqueId());
        if (probe != null) probe.markSwing(System.currentTimeMillis());
    }

    public void cancelPlayer(UUID playerId, String reason) {
        if (playerId == null) return;
        ActiveProbe probe = active.remove(playerId);
        if (probe != null) finishRemoved(probe, reason == null ? "CANCELLED" : reason);
        notBefore.remove(playerId);
    }

    public int getActiveProbeCount() { return active.size(); }
    public boolean isEnabled() { return enabled; }
    public double getMinimumCollectedHours() { return minimumCollectedMs / 3_600_000.0; }
    public ProbeResultRecorder getRecorder() { return recorder; }

    public void shutdown() {
        if (useEntityListener != null) {
            protocolManager.removePacketListener(useEntityListener);
            useEntityListener = null;
        }
        for (ActiveProbe probe : active.values()) finishRemoved(probe, "SHUTDOWN");
        active.clear();
        notBefore.clear();
    }

    private boolean startProbe(Player player,
                               BehaviorSnapshot snapshot,
                               UUID sessionId,
                               String trustSource,
                               long eligibleAfterMs,
                               boolean forced) {
        if (player == null || snapshot == null || sessionId == null) return false;
        UUID playerId = player.getUniqueId();
        if (active.containsKey(playerId)) return false;

        Location probeLocation = chooseProbeLocation(player);
        if (probeLocation == null) return false;

        long now = System.currentTimeMillis();
        UUID probeId = UuidV7.next();
        UUID fakeUuid = UuidV7.next();
        String probeName = makeProbeName(probeId);
        FakeReplayPlayer fake = new FakeReplayPlayer(plugin, player, fakeUuid, probeName, null);

        ActiveProbe probe = new ActiveProbe(
                probeId,
                playerId,
                player.getName(),
                sessionId,
                fakeUuid,
                probeName,
                fake,
                probeLocation,
                now,
                Math.max(now, eligibleAfterMs),
                angleTo(player.getEyeLocation(), probeLocation.clone().add(0.0, 1.62, 0.0)),
                player.getLocation().distance(probeLocation),
                player.getPing(),
                snapshot.getTps(),
                player.getWorld().getName(),
                trustSource,
                forced
        );

        ActiveProbe previous = active.putIfAbsent(playerId, probe);
        if (previous != null) return false;

        try {
            fake.spawn(probeLocation);
        } catch (Throwable t) {
            active.remove(playerId, probe);
            plugin.getLogger().warning("Unable to spawn behavioral probe for " + player.getName() + ": " + t.getMessage());
            return false;
        }

        notBefore.put(playerId, safeAdd(now, cooldownMs + randomLong(0L, cooldownJitterMs)));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> finish(playerId, probeId, "TIMEOUT"), durationTicks);
        return true;
    }

    private void finish(UUID playerId, UUID probeId, String reason) {
        ActiveProbe probe = active.get(playerId);
        if (probe == null || !probe.probeId.equals(probeId)) return;
        if (active.remove(playerId, probe)) finishRemoved(probe, reason);
    }

    private void finishRemoved(ActiveProbe probe, String reason) {
        try { probe.fake.destroy(); }
        catch (Throwable ignored) {}
        if (recorder != null) recorder.record(probe.finish(reason, System.currentTimeMillis()));
    }

    private boolean isSafeContext(BehaviorSnapshot snapshot) {
        GameMode mode = snapshot.getGameMode();
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) return false;
        if (snapshot.isFlying() || snapshot.isGliding() || snapshot.isInVehicle()) return false;
        if (avoidCombat && (snapshot.getHitCount() > 0 || snapshot.getSwingCount() > 0)) return false;
        return snapshot.getMovementSamples() >= 5;
    }

    private Location chooseProbeLocation(Player player) {
        Location base = player.getLocation();
        World world = base.getWorld();
        if (world == null) return null;

        for (int attempt = 0; attempt < 12; attempt++) {
            double offset = randomDouble(minYawOffset, maxYawOffset);
            if (ThreadLocalRandom.current().nextBoolean()) offset = -offset;
            double distance = randomDouble(minDistance, maxDistance);
            double yaw = Math.toRadians(base.getYaw() + offset);
            double x = base.getX() - Math.sin(yaw) * distance;
            double z = base.getZ() + Math.cos(yaw) * distance;
            double y = base.getY() + randomDouble(-0.25, 0.45);
            Location candidate = new Location(world, x, y, z);
            if (!candidate.getBlock().isPassable()) continue;
            if (!candidate.clone().add(0.0, 1.0, 0.0).getBlock().isPassable()) continue;

            Vector face = player.getEyeLocation().toVector()
                    .subtract(candidate.clone().add(0.0, 1.62, 0.0).toVector());
            if (face.lengthSquared() > 1.0E-6) candidate.setDirection(face);
            return candidate;
        }
        return null;
    }

    private static String makeProbeName(UUID probeId) {
        String compact = probeId.toString().replace("-", "");
        return "P" + compact.substring(0, 12);
    }

    private static double angleTo(Location eye, Location target) {
        if (eye == null || target == null || eye.getWorld() == null || target.getWorld() == null
                || !eye.getWorld().equals(target.getWorld())) return 180.0;
        Vector targetVector = target.toVector().subtract(eye.toVector());
        if (targetVector.lengthSquared() < 1.0E-9) return 0.0;
        try { return Math.toDegrees(eye.getDirection().angle(targetVector)); }
        catch (IllegalArgumentException ignored) { return 180.0; }
    }

    private static double angularDelta(Location from, Location to) {
        double yaw = Math.abs(wrapAngle(to.getYaw() - from.getYaw()));
        double pitch = Math.abs(to.getPitch() - from.getPitch());
        return Math.hypot(yaw, pitch);
    }

    private static double wrapAngle(double angle) {
        angle %= 360.0;
        if (angle <= -180.0) angle += 360.0;
        if (angle > 180.0) angle -= 360.0;
        return angle;
    }

    private static long hoursToMs(double hours) {
        if (!Double.isFinite(hours) || hours <= 0.0) return 0L;
        double millis = hours * 3_600_000.0;
        return millis >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) millis;
    }

    private static long randomLong(long min, long max) {
        if (max <= min) return min;
        return ThreadLocalRandom.current().nextLong(min, max + 1L);
    }

    private static double randomDouble(double min, double max) {
        if (max <= min) return min;
        return ThreadLocalRandom.current().nextDouble(min, max);
    }

    private static long safeAdd(long a, long b) {
        if (b <= 0L) return a;
        return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }

    private static long clamp(long value, long min, long max) { return Math.max(min, Math.min(max, value)); }
    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) return min;
        return Math.max(min, Math.min(max, value));
    }

    private static final class ActiveProbe {
        final UUID probeId;
        final UUID playerId;
        final String playerName;
        final UUID sessionId;
        final UUID fakeUuid;
        final String probeName;
        final FakeReplayPlayer fake;
        final Location probeLocation;
        final long spawnedAtMs;
        final long eligibleAfterMs;
        final double initialAngle;
        final double distance;
        final int pingMs;
        final double tps;
        final String world;
        final String trustSource;
        final boolean forced;

        private long firstRotationMs = -1L;
        private long firstFovMs = -1L;
        private long firstSwingMs = -1L;
        private long firstAttackMs = -1L;
        private double minAngle;
        private double maxRotationRateDps;
        private long lastMoveAtMs;

        ActiveProbe(UUID probeId,
                    UUID playerId,
                    String playerName,
                    UUID sessionId,
                    UUID fakeUuid,
                    String probeName,
                    FakeReplayPlayer fake,
                    Location probeLocation,
                    long spawnedAtMs,
                    long eligibleAfterMs,
                    double initialAngle,
                    double distance,
                    int pingMs,
                    double tps,
                    String world,
                    String trustSource,
                    boolean forced) {
            this.probeId = probeId;
            this.playerId = playerId;
            this.playerName = playerName;
            this.sessionId = sessionId;
            this.fakeUuid = fakeUuid;
            this.probeName = probeName;
            this.fake = fake;
            this.probeLocation = probeLocation.clone();
            this.spawnedAtMs = spawnedAtMs;
            this.eligibleAfterMs = eligibleAfterMs;
            this.initialAngle = initialAngle;
            this.distance = distance;
            this.pingMs = pingMs;
            this.tps = tps;
            this.world = world == null ? "" : world;
            this.trustSource = trustSource == null ? "" : trustSource;
            this.forced = forced;
            this.minAngle = initialAngle;
            this.lastMoveAtMs = spawnedAtMs;
        }

        synchronized void observeMove(Player player,
                                      Location from,
                                      Location to,
                                      double fovDegrees,
                                      double rotationEpsilonDegrees) {
            long now = System.currentTimeMillis();
            double delta = angularDelta(from, to);
            if (delta >= rotationEpsilonDegrees && firstRotationMs < 0L) firstRotationMs = now - spawnedAtMs;
            long elapsed = Math.max(1L, now - lastMoveAtMs);
            maxRotationRateDps = Math.max(maxRotationRateDps, delta * 1000.0 / elapsed);
            lastMoveAtMs = now;

            Location eye = to.clone().add(0.0, player.getEyeHeight(), 0.0);
            double angle = angleTo(eye, probeLocation.clone().add(0.0, 1.62, 0.0));
            minAngle = Math.min(minAngle, angle);
            if (angle <= fovDegrees && firstFovMs < 0L) firstFovMs = now - spawnedAtMs;
        }

        synchronized void markSwing(long nowMs) {
            if (firstSwingMs < 0L) firstSwingMs = Math.max(0L, nowMs - spawnedAtMs);
        }

        synchronized void markAttack(long nowMs) {
            if (firstAttackMs < 0L) firstAttackMs = Math.max(0L, nowMs - spawnedAtMs);
        }

        synchronized ProbeResult finish(String reason, long completedAtMs) {
            return new ProbeResult(
                    probeId, playerId, playerName, sessionId, fakeUuid, probeName,
                    spawnedAtMs, completedAtMs, reason, world, eligibleAfterMs,
                    initialAngle, distance, firstRotationMs, firstFovMs,
                    firstSwingMs, firstAttackMs, minAngle, maxRotationRateDps,
                    pingMs, tps, trustSource, forced
            );
        }
    }

    public static final class ProbeResult {
        private final UUID probeId;
        private final UUID playerId;
        private final String playerName;
        private final UUID sessionId;
        private final UUID fakeUuid;
        private final String probeName;
        private final long spawnedAtMs;
        private final long completedAtMs;
        private final String completionReason;
        private final String world;
        private final long eligibleAfterMs;
        private final double initialAngle;
        private final double distance;
        private final long firstRotationMs;
        private final long firstFovMs;
        private final long firstSwingMs;
        private final long firstAttackMs;
        private final double minAngle;
        private final double maxRotationRateDps;
        private final int pingMs;
        private final double tps;
        private final String trustSource;
        private final boolean forced;

        ProbeResult(UUID probeId, UUID playerId, String playerName, UUID sessionId,
                    UUID fakeUuid, String probeName, long spawnedAtMs, long completedAtMs,
                    String completionReason, String world, long eligibleAfterMs,
                    double initialAngle, double distance, long firstRotationMs,
                    long firstFovMs, long firstSwingMs, long firstAttackMs,
                    double minAngle, double maxRotationRateDps, int pingMs, double tps,
                    String trustSource, boolean forced) {
            this.probeId = probeId;
            this.playerId = playerId;
            this.playerName = playerName;
            this.sessionId = sessionId;
            this.fakeUuid = fakeUuid;
            this.probeName = probeName;
            this.spawnedAtMs = spawnedAtMs;
            this.completedAtMs = completedAtMs;
            this.completionReason = completionReason;
            this.world = world;
            this.eligibleAfterMs = eligibleAfterMs;
            this.initialAngle = initialAngle;
            this.distance = distance;
            this.firstRotationMs = firstRotationMs;
            this.firstFovMs = firstFovMs;
            this.firstSwingMs = firstSwingMs;
            this.firstAttackMs = firstAttackMs;
            this.minAngle = minAngle;
            this.maxRotationRateDps = maxRotationRateDps;
            this.pingMs = pingMs;
            this.tps = tps;
            this.trustSource = trustSource;
            this.forced = forced;
        }

        public String toCsvRow() {
            StringBuilder row = new StringBuilder();
            csv(row, probeId.toString()); row.append(',');
            csv(row, playerId.toString()); row.append(',');
            csv(row, playerName); row.append(',');
            csv(row, sessionId.toString()); row.append(',');
            csv(row, fakeUuid.toString()); row.append(',');
            csv(row, probeName); row.append(',');
            row.append(spawnedAtMs).append(',').append(completedAtMs).append(',');
            csv(row, completionReason); row.append(',');
            csv(row, world); row.append(',').append(eligibleAfterMs).append(',');
            csv(row, trustSource); row.append(',').append(forced).append(',');
            row.append(initialAngle).append(',').append(distance).append(',')
                    .append(firstRotationMs).append(',').append(firstFovMs).append(',')
                    .append(firstSwingMs).append(',').append(firstAttackMs).append(',')
                    .append(minAngle).append(',').append(maxRotationRateDps).append(',')
                    .append(pingMs).append(',').append(tps);
            return row.toString();
        }

        private static void csv(StringBuilder out, String raw) {
            String value = raw == null ? "" : raw;
            out.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
    }
}
