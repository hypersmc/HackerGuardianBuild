package me.hackerguardian.main.aicore.aievents;

import me.hackerguardian.main.HackerGuardian;
import me.hackerguardian.main.aicore.AiManager;
import me.hackerguardian.main.aicore.FeatureCollector;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.UUID;

/**
 * @author JumpWatch on 04-04-2023
 * @Project HackerGuardian
 * v1.0.0
 */
public class HGPlayerMoveListener implements Listener {

    private final HackerGuardian main;
    private final FeatureCollector featureCollector;
    private final AiManager aiManager;

    public HGPlayerMoveListener() {
        this.main = HackerGuardian.getInstance();
        this.featureCollector = main.getFeatureCollector();
        this.aiManager = main.getAiManager();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // 1) Always record movement into the window
        featureCollector.recordMovement(event);

        // 2) Build features if we have enough behaviour
        double[] features = featureCollector.buildSample(player);
        if (features == null) {
            return;
        }

        boolean learning = main.isLearning();
        boolean trained = aiManager.hasAnyTraining();

        // 3) LEARNING MODE: learn + still update suspicion for debug (/hg inspect)
        if (learning) {
            aiManager.learn(features, 0.0); // treat as legit
            double suspicion = aiManager.evaluate(features); // debug only
            main.getSuspicionManager().updateMovement(uuid, suspicion);
            return;
        }

        // 4) LIVE MODE but no trained model yet → nothing useful to evaluate
        if (!trained) {
            return;
        }

        // 5) LIVE MODE + trained model → evaluate and update suspicion
        double suspicion = aiManager.evaluate(features);
        main.getSuspicionManager().updateMovement(uuid, suspicion);
    }
}
