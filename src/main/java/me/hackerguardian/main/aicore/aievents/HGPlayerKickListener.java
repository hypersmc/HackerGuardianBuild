package me.hackerguardian.main.aicore.aievents;

import me.hackerguardian.main.HackerGuardian;
import me.hackerguardian.main.aicore.FeatureCollector;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;

import java.util.UUID;

public class HGPlayerKickListener implements Listener {
    private final HackerGuardian main;
    private final FeatureCollector featureCollector;

    public HGPlayerKickListener() {
        this.main = HackerGuardian.getInstance();
        this.featureCollector = main.getFeatureCollector();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerKick(PlayerKickEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // 1) Clear player data from featureCollector to free up memory
        featureCollector.clearPlayer(uuid);
    }
}
