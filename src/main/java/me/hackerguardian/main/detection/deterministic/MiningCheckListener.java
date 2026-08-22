package me.hackerguardian.main.detection.deterministic;

import me.hackerguardian.main.detection.DetectionCategory;
import me.hackerguardian.main.detection.DetectionFinding;
import me.hackerguardian.main.detection.EvidenceStrength;
import me.hackerguardian.main.utils.Tps;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Conservative deterministic mining checks based on server-observed timing. */
public final class MiningCheckListener implements Listener {

    public static final String FAST_BREAK_ID = "mining.fast-break";
    public static final String MULTI_BREAK_ID = "mining.multi-break";

    private static final long SESSION_MAX_AGE_MS = 15_000L;

    private final DeterministicEvidenceBuffer evidenceBuffer;
    private final boolean fastBreakEnabled;
    private final long minimumExpectedMs;
    private final double minimumRatio;
    private final long toleranceMs;
    private final boolean multiBreakEnabled;
    private final long multiBreakWindowMs;
    private final int multiBreakMinimumBlocks;
    private final long multiBreakMinimumPerBlockMs;

    private final Map<UUID, MiningSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<HardBreak>> recentHardBreaks = new ConcurrentHashMap<>();

    public MiningCheckListener(DeterministicEvidenceBuffer evidenceBuffer,
                               boolean fastBreakEnabled,
                               long minimumExpectedMs,
                               double minimumRatio,
                               long toleranceMs,
                               boolean multiBreakEnabled,
                               long multiBreakWindowMs,
                               int multiBreakMinimumBlocks,
                               long multiBreakMinimumPerBlockMs) {
        this.evidenceBuffer = evidenceBuffer;
        this.fastBreakEnabled = fastBreakEnabled;
        this.minimumExpectedMs = Math.max(50L, minimumExpectedMs);
        this.minimumRatio = Math.max(0.05, Math.min(1.0, minimumRatio));
        this.toleranceMs = Math.max(0L, Math.min(500L, toleranceMs));
        this.multiBreakEnabled = multiBreakEnabled;
        this.multiBreakWindowMs = Math.max(50L, Math.min(2000L, multiBreakWindowMs));
        this.multiBreakMinimumBlocks = Math.max(2, Math.min(12, multiBreakMinimumBlocks));
        this.multiBreakMinimumPerBlockMs = Math.max(50L, multiBreakMinimumPerBlockMs);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        Player player = event.getPlayer();
        if (!eligible(player)) return;

        Block block = event.getBlock();
        double speed = safeBreakSpeed(block, player);
        if (event.getInstaBreak() || speed >= 1.0) {
            sessions.remove(player.getUniqueId());
            return;
        }

        long now = System.currentTimeMillis();
        BlockKey key = BlockKey.of(block);
        MiningSession existing = sessions.get(player.getUniqueId());
        if (existing != null && existing.key.equals(key) && now - existing.startedAtMs <= SESSION_MAX_AGE_MS) {
            existing.fastestBreakSpeed = Math.max(existing.fastestBreakSpeed, speed);
            return;
        }
        sessions.put(player.getUniqueId(), new MiningSession(key, now, speed));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!eligible(player)) return;

        long now = System.currentTimeMillis();
        Block block = event.getBlock();
        double currentSpeed = safeBreakSpeed(block, player);

        if (fastBreakEnabled) evaluateFastBreak(player, block, currentSpeed, now);
        if (multiBreakEnabled) evaluateMultiBreak(player, block, currentSpeed, now);
    }

    private void evaluateFastBreak(Player player, Block block, double currentSpeed, long now) {
        MiningSession session = sessions.remove(player.getUniqueId());
        if (session == null || !session.key.equals(BlockKey.of(block))) return;
        if (now - session.startedAtMs > SESSION_MAX_AGE_MS) return;

        double fastestSpeed = Math.max(session.fastestBreakSpeed, currentSpeed);
        long vanillaMinimumMs = MiningTiming.conservativeMinimumBreakMs(fastestSpeed);
        if (vanillaMinimumMs < minimumExpectedMs) return;

        long observedMs = Math.max(0L, now - session.startedAtMs);
        long latencyAllowance = Math.min(75L, Math.max(0, player.getPing()) / 4L);
        long effectiveTolerance = toleranceMs + latencyAllowance;
        if (!MiningTiming.isTooFast(observedMs, vanillaMinimumMs, minimumRatio, effectiveTolerance)) return;

        Map<String, Double> evidence = new LinkedHashMap<>();
        evidence.put("observed_ms", (double) observedMs);
        evidence.put("vanilla_minimum_ms", (double) vanillaMinimumMs);
        evidence.put("fastest_break_speed", fastestSpeed);
        evidence.put("minimum_ratio", minimumRatio);
        evidence.put("tolerance_ms", (double) effectiveTolerance);
        evidence.put("ping_ms", (double) player.getPing());
        evidence.put("tps", Tps.getTPS());

        double severity = MiningTiming.severity(observedMs, vanillaMinimumMs, minimumRatio, effectiveTolerance);
        double reliability = vanillaMinimumMs >= 400L ? 0.96 : 0.90;
        evidenceBuffer.record(player.getUniqueId(), new DetectionFinding(
                FAST_BREAK_ID,
                DetectionCategory.BLOCK,
                severity,
                reliability,
                EvidenceStrength.STRONG,
                "Block completed substantially faster than Bukkit's server-side break-speed envelope allows.",
                evidence
        ));
    }

    private void evaluateMultiBreak(Player player, Block block, double currentSpeed, long now) {
        long vanillaMinimumMs = MiningTiming.conservativeMinimumBreakMs(currentSpeed);
        if (vanillaMinimumMs < multiBreakMinimumPerBlockMs) return;

        Deque<HardBreak> deque = recentHardBreaks.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayDeque<>());
        int count;
        double spatialSpan;
        synchronized (deque) {
            long cutoff = now - multiBreakWindowMs;
            while (!deque.isEmpty() && deque.peekFirst().timestampMs < cutoff) deque.removeFirst();
            deque.addLast(new HardBreak(now, block.getX(), block.getY(), block.getZ(), vanillaMinimumMs));
            while (deque.size() > 24) deque.removeFirst();
            count = deque.size();
            spatialSpan = spatialSpan(deque);
        }

        if (count < multiBreakMinimumBlocks) return;

        Map<String, Double> evidence = new LinkedHashMap<>();
        evidence.put("hard_blocks", (double) count);
        evidence.put("window_ms", (double) multiBreakWindowMs);
        evidence.put("minimum_blocks", (double) multiBreakMinimumBlocks);
        evidence.put("spatial_span", spatialSpan);
        evidence.put("latest_vanilla_minimum_ms", (double) vanillaMinimumMs);
        evidence.put("ping_ms", (double) player.getPing());
        evidence.put("tps", Tps.getTPS());

        double excess = Math.min(1.0, (count - multiBreakMinimumBlocks + 1.0) / 4.0);
        evidenceBuffer.record(player.getUniqueId(), new DetectionFinding(
                MULTI_BREAK_ID,
                DetectionCategory.BLOCK,
                0.75 + (0.25 * excess),
                0.95,
                EvidenceStrength.STRONG,
                "Multiple non-instant blocks completed inside a timing window that cannot accommodate their individual break times.",
                evidence
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        sessions.remove(playerId);
        recentHardBreaks.remove(playerId);
    }

    private static boolean eligible(Player player) {
        if (player == null) return false;
        GameMode mode = player.getGameMode();
        return mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE;
    }

    private static double safeBreakSpeed(Block block, Player player) {
        try {
            double speed = block.getBreakSpeed(player);
            return Double.isFinite(speed) && speed > 0.0 ? speed : 0.0;
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    private static double spatialSpan(Deque<HardBreak> breaks) {
        if (breaks.size() < 2) return 0.0;
        HardBreak first = breaks.peekFirst();
        double max = 0.0;
        for (HardBreak current : breaks) {
            double dx = current.x - first.x;
            double dy = current.y - first.y;
            double dz = current.z - first.z;
            max = Math.max(max, Math.sqrt((dx * dx) + (dy * dy) + (dz * dz)));
        }
        return max;
    }

    private static final class MiningSession {
        final BlockKey key;
        final long startedAtMs;
        double fastestBreakSpeed;

        MiningSession(BlockKey key, long startedAtMs, double fastestBreakSpeed) {
            this.key = key;
            this.startedAtMs = startedAtMs;
            this.fastestBreakSpeed = fastestBreakSpeed;
        }
    }

    private static final class HardBreak {
        final long timestampMs;
        final int x;
        final int y;
        final int z;
        final long vanillaMinimumMs;

        HardBreak(long timestampMs, int x, int y, int z, long vanillaMinimumMs) {
            this.timestampMs = timestampMs;
            this.x = x;
            this.y = y;
            this.z = z;
            this.vanillaMinimumMs = vanillaMinimumMs;
        }
    }

    private static final class BlockKey {
        final UUID worldId;
        final int x;
        final int y;
        final int z;

        BlockKey(UUID worldId, int x, int y, int z) {
            this.worldId = worldId;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        static BlockKey of(Block block) {
            return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof BlockKey)) return false;
            BlockKey that = (BlockKey) other;
            return x == that.x && y == that.y && z == that.z && worldId.equals(that.worldId);
        }

        @Override
        public int hashCode() {
            int result = worldId.hashCode();
            result = 31 * result + x;
            result = 31 * result + y;
            result = 31 * result + z;
            return result;
        }
    }
}
