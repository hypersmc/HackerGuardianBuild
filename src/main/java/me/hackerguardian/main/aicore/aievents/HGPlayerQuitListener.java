package me.hackerguardian.main.aicore.aievents;

import me.hackerguardian.main.HackerGuardian;
import me.hackerguardian.main.aicore.FeatureCollector;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

public class HGPlayerQuitListener implements Listener {
    private final HackerGuardian main;
    private final FeatureCollector featureCollector;

    public HGPlayerQuitListener() {
        main = HackerGuardian.getInstance();
        featureCollector = new FeatureCollector();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // 1) Clear player data from featureCollector to free up memory
        featureCollector.clearPlayer(uuid);
    }
}
