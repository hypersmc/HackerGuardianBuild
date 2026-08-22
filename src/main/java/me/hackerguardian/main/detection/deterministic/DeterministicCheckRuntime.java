package me.hackerguardian.main.detection.deterministic;

import me.hackerguardian.main.HackerGuardian;
import me.hackerguardian.main.detection.DetectionFinding;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Lifecycle owner for deterministic, non-ML event checks. */
public final class DeterministicCheckRuntime implements Listener {

    private final HackerGuardian plugin;
    private final boolean enabled;
    private final DeterministicEvidenceBuffer evidenceBuffer;
    private final List<Listener> listeners = new ArrayList<>();
    private final List<String> checkIds = new ArrayList<>();
    private boolean running;

    public DeterministicCheckRuntime(HackerGuardian plugin, FileConfiguration config, long assessmentWindowMs) {
        this.plugin = plugin;
        this.enabled = config.getBoolean("DetectionV2.deterministic.enabled", true);
        long retentionMs = config.getLong(
                "DetectionV2.deterministic.evidence_retention_ms",
                Math.max(5000L, assessmentWindowMs)
        );
        int maxEvents = config.getInt("DetectionV2.deterministic.max_events_per_player", 128);
        this.evidenceBuffer = new DeterministicEvidenceBuffer(retentionMs, maxEvents);

        if (!enabled) return;

        boolean fastBreak = config.getBoolean("DetectionV2.deterministic.mining.fast_break.enabled", true);
        boolean multiBreak = config.getBoolean("DetectionV2.deterministic.mining.multi_break.enabled", true);
        if (fastBreak || multiBreak) {
            listeners.add(new MiningCheckListener(
                    evidenceBuffer,
                    fastBreak,
                    config.getLong("DetectionV2.deterministic.mining.fast_break.minimum_expected_ms", 150L),
                    config.getDouble("DetectionV2.deterministic.mining.fast_break.minimum_ratio", 0.55),
                    config.getLong("DetectionV2.deterministic.mining.fast_break.tolerance_ms", 75L),
                    multiBreak,
                    config.getLong("DetectionV2.deterministic.mining.multi_break.window_ms", 250L),
                    config.getInt("DetectionV2.deterministic.mining.multi_break.minimum_blocks", 3),
                    config.getLong("DetectionV2.deterministic.mining.multi_break.minimum_per_block_ms", 150L)
            ));
            if (fastBreak) checkIds.add(MiningCheckListener.FAST_BREAK_ID);
            if (multiBreak) checkIds.add(MiningCheckListener.MULTI_BREAK_ID);
        }

        boolean reach = config.getBoolean("DetectionV2.deterministic.block_interaction.reach.enabled", true);
        boolean lineOfSight = config.getBoolean("DetectionV2.deterministic.block_interaction.line_of_sight.enabled", true);
        if (reach || lineOfSight) {
            listeners.add(new BlockInteractionCheckListener(
                    evidenceBuffer,
                    reach,
                    config.getDouble("DetectionV2.deterministic.block_interaction.reach.max_distance", 5.75),
                    config.getDouble("DetectionV2.deterministic.block_interaction.reach.hard_excess_distance", 1.50),
                    lineOfSight,
                    config.getDouble("DetectionV2.deterministic.block_interaction.line_of_sight.minimum_distance", 1.50)
            ));
            if (reach) {
                checkIds.add(BlockInteractionCheckListener.MINING_REACH_ID);
                checkIds.add(BlockInteractionCheckListener.PLACE_REACH_ID);
            }
            if (lineOfSight) {
                checkIds.add(BlockInteractionCheckListener.MINING_WALL_ID);
                checkIds.add(BlockInteractionCheckListener.PLACE_WALL_ID);
            }
        }
    }

    public void start() {
        if (!enabled || running) return;
        running = true;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Listener listener : listeners) Bukkit.getPluginManager().registerEvents(listener, plugin);
        plugin.getLogger().info("Deterministic detection started with " + checkIds.size() + " check(s): "
                + String.join(", ", checkIds));
    }

    public void stop() {
        if (!running) return;
        running = false;
        HandlerList.unregisterAll(this);
        for (Listener listener : listeners) HandlerList.unregisterAll(listener);
        evidenceBuffer.clear();
    }

    public List<DetectionFinding> recentFindings(UUID playerId, long windowMs) {
        if (!enabled || playerId == null) return List.of();
        return evidenceBuffer.recent(playerId, System.currentTimeMillis(), windowMs);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isRunning() {
        return running;
    }

    public List<String> getCheckIds() {
        return Collections.unmodifiableList(checkIds);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        evidenceBuffer.clearPlayer(event.getPlayer().getUniqueId());
    }
}
