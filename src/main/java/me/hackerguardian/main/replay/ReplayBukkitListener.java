package me.hackerguardian.main.replay;

import me.hackerguardian.main.replay.events.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;

public final class ReplayBukkitListener implements Listener {

    private final ReplayManager rm;

    public ReplayBukkitListener(ReplayManager rm) {
        this.rm = rm;
    }

    @EventHandler
    public void onArmSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() == PlayerAnimationType.ARM_SWING) {
            rm.record(e.getPlayer(), System.currentTimeMillis(), new ArmSwingEvent());
        }
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        rm.record(e.getPlayer(), System.currentTimeMillis(), new SneakToggleEvent(e.isSneaking()));
    }

    @EventHandler
    public void onSprint(PlayerToggleSprintEvent e) {
        rm.record(e.getPlayer(), System.currentTimeMillis(), new SprintToggleEvent(e.isSprinting()));
    }

    @EventHandler
    public void onConsume(PlayerItemConsumeEvent e) {
        rm.record(e.getPlayer(), System.currentTimeMillis(), ItemConsumeEvent.from(e.getItem()));
    }

    @EventHandler
    public void onInvClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        rm.record(p, System.currentTimeMillis(),
                InventoryClickReplayEvent.from(e.getSlot(), e.getClick(), e.getAction(), e.getCurrentItem()));
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        rm.record(e.getPlayer(), System.currentTimeMillis(), ItemDropReplayEvent.from(e.getItemDrop().getItemStack()));
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        rm.record(p, System.currentTimeMillis(), ItemPickupReplayEvent.from(e.getItem().getItemStack()));
    }

    @EventHandler
    public void onProjLaunch(ProjectileLaunchEvent e) {
        if (e.getEntity().getShooter() instanceof Player p) {
            rm.record(p, System.currentTimeMillis(), ProjectileLaunchReplayEvent.from(e.getEntity()));
        }
    }

    @EventHandler
    public void onProjHit(ProjectileHitEvent e) {
        if (e.getEntity().getShooter() instanceof Player p) {
            var hitEnt = e.getHitEntity();
            var hitBlock = e.getHitBlock() != null;
            var loc = e.getEntity().getLocation();
            rm.record(p, System.currentTimeMillis(), ProjectileHitReplayEvent.from(e.getEntity(), loc, hitEnt, hitBlock));
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        rm.record(e.getPlayer(), System.currentTimeMillis(), BlockBreakReplayEvent.from(e.getBlock()));
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        rm.record(e.getPlayer(), System.currentTimeMillis(), BlockPlaceReplayEvent.from(e.getBlockPlaced()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        rm.cleanupPlayer(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onKick(PlayerKickEvent e) {
        rm.cleanupPlayer(e.getPlayer().getUniqueId());
    }
}