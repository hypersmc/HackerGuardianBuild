package me.hackerguardian.main.aicore.aievents;

import me.hackerguardian.main.HackerGuardian;
import me.hackerguardian.main.aicore.AiManager;
import me.hackerguardian.main.aicore.FeatureCollector;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.UUID;

public class HGBlockBreakListener implements Listener {

    private final HackerGuardian main;
    private final FeatureCollector featureCollector;
    private final AiManager aiManager;

    public HGBlockBreakListener() {
        this.main = HackerGuardian.getInstance();
        this.featureCollector = main.getFeatureCollector();
        this.aiManager = main.getAiManager();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // 1) record block break
        featureCollector.recordBlockBreak(player, event);

        // 2) build features if enough behaviour
        double[] features = featureCollector.buildSample(player);
        if (features == null) {
            return;
        }

        boolean learning = main.isLearning();
        boolean trained = aiManager.hasAnyTraining();

        // 3) LEARNING MODE: learn + update suspicion for debug
        if (learning) {
            aiManager.learn(features, 0.0);
            double suspicion = aiManager.evaluate(features); // debug only
            // treat this as movement-context suspicion
            main.getSuspicionManager().updateMovement(uuid, suspicion);
            return;
        }

        // 4) LIVE MODE but no trained model yet → skip eval
        if (!trained) {
            return;
        }

        // 5) LIVE MODE + trained model → evaluate and refresh movement suspicion
        double suspicion = aiManager.evaluate(features);
        main.getSuspicionManager().updateMovement(uuid, suspicion);
        // no direct warnings yet; combat listener handles those
    }
}