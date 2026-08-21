package me.hackerguardian.main.detection;

import me.hackerguardian.main.detection.telemetry.BehaviorTelemetryCollector;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Thin Bukkit adapter for the v2 telemetry collector.
 */
public final class DetectionTelemetryListener implements Listener {

    private final BehaviorTelemetryCollector collector;
    private final DetectionEngine engine;

    public DetectionTelemetryListener(BehaviorTelemetryCollector collector, DetectionEngine engine) {
        this.collector = collector;
        this.engine = engine;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        collector.recordMovement(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnimation(PlayerAnimationEvent event) {
        collector.recordSwing(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        collector.recordCombat(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        collector.recordBlockBreak(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        collector.recordBlockPlace(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        collector.clearPlayer(event.getPlayer().getUniqueId());
        engine.clearPlayer(event.getPlayer().getUniqueId());
    }
}
