package me.hackerguardian.main.aicore.aievents;

import me.hackerguardian.main.HackerGuardian;
import me.hackerguardian.main.aicore.AiManager;
import me.hackerguardian.main.aicore.FeatureCollector;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.UUID;

public class HGBlockPlaceListener implements Listener {

    private final HackerGuardian main;
    private final FeatureCollector featureCollector;
    private final AiManager aiManager;

    public HGBlockPlaceListener() {
        this.main = HackerGuardian.getInstance();
        this.featureCollector = main.getFeatureCollector();
        this.aiManager = main.getAiManager();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // 1) record block place
        featureCollector.recordBlockPlace(player, event);

        // 2) build features
        double[] features = featureCollector.buildSample(player);
        if (features == null) {
            return;
        }

        boolean learning = main.isLearning();
        boolean trained = aiManager.hasAnyTraining();

        // 3) LEARNING MODE: learn + update movement suspicion for debug
        if (learning) {
            aiManager.learn(features, 0.0);
            double suspicion = aiManager.evaluate(features); // debug only
            main.getSuspicionManager().updateMovement(uuid, suspicion);
            return;
        }

        // 4) LIVE MODE but no trained model yet
        if (!trained) {
            return;
        }

        // 5) LIVE MODE + trained model → evaluate + refresh movement suspicion
        double suspicion = aiManager.evaluate(features);
        main.getSuspicionManager().updateMovement(uuid, suspicion);
    }
}