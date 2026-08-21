package me.hackerguardian.main.aicore.aievents;

import me.hackerguardian.main.HackerGuardian;
import me.hackerguardian.main.aicore.AIService;
import me.hackerguardian.main.aicore.AiManager;
import me.hackerguardian.main.aicore.FeatureCollector;
import me.hackerguardian.main.utils.Tps;
import me.hackerguardian.main.utils.textHandling;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author JumpWatch on 01-05-2024
 * @Project HackerGuardian
 * v1.0.0
 */
public class HGEntityDamageByEntityListener implements Listener {

    private final HackerGuardian main;
    private final FeatureCollector featureCollector;
    private final AiManager aiManager;
    private final AIService service;
    textHandling tx = new textHandling();

    // simple per-player smoothing + anti-spam
    private final Map<UUID, Double> suspicionEma = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastWarnTime = new ConcurrentHashMap<>();

    // tweak or move to config
    private static final double EMA_ALPHA = 0.3;              // smoothing factor
    private static final long WARN_COOLDOWN_MS = 30_000L;     // 30 seconds

    public HGEntityDamageByEntityListener(AIService service) {
        this.main = HackerGuardian.getInstance();
        this.featureCollector = main.getFeatureCollector();
        this.aiManager = main.getAiManager();
        this.service = service;

    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageByEntityEvent(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }

        UUID uuid = attacker.getUniqueId();

        // 1) always record combat into the feature window
        featureCollector.recordCombat(event);

        // 2) build a feature vector for this attacker (movement+combat+packets+blocks)
        double[] features = featureCollector.buildSample(attacker);
        if (features == null) {
            return;
        }

        boolean learning = main.isLearning();
        boolean trained = aiManager.hasAnyTraining();

        // 3) LEARNING MODE: learn + update combat suspicion, but DO NOT warn admins
        if (learning) {
            aiManager.learn(features, 0.0);
            AiManager.EvaluationDetails details = aiManager.evaluateWithDetails(features);
            double suspicion = details.getFinalOutput();// debug only
            double smoothed = updateEma(uuid, suspicion);
            main.getSuspicionManager().updateCombat(uuid, smoothed);
            return;
        }

        // 4) LIVE MODE but no trained model yet → don't do anything
        if (!trained) {
            return;
        }

        // 5) LIVE MODE + trained model → evaluate, update suspicion, maybe warn
        AiManager.EvaluationDetails details = aiManager.evaluateWithDetails(features);
        double suspicion = details.getFinalOutput();
        double smoothed = updateEma(uuid, suspicion);

        // update suspicion manager used by /hg inspect
        main.getSuspicionManager().updateCombat(uuid, smoothed);

        double threshold = main.getSuspicionThreshold();



        if (smoothed >= threshold) {
            if (main.getConfig().getBoolean("Settings.UseWebsiteFunction")) {
                service.PData(attacker, details)
                        .whenComplete((PDataID, ex) -> Bukkit.getScheduler().runTask(main, () -> {
                            if (ex != null) {
                                for (Player online : Bukkit.getOnlinePlayers()) {
                                    if (online.hasPermission("hg.dberror")) {
                                        online.sendMessage(tx.playerText(tx.prefix + "Failed to add PData (DB error)."));
                                        if (main.getConfig().getBoolean("debug")) ex.printStackTrace();
                                        return;
                                    }
                                }
                            }
                        }));
            }
            long now = System.currentTimeMillis();
            long last = lastWarnTime.getOrDefault(uuid, 0L);
            if (now - last >= WARN_COOLDOWN_MS) {
                lastWarnTime.put(uuid, now);

                String msg = String.format(
                        "%s §e%s §7might be cheating in combat. Suspicion: §c%.0f%%",
                        tx.prefix,
                        attacker.getName(),
                        smoothed * 100.0
                );

                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.hasPermission("hg.admin.warn")) {
                        p.sendMessage(msg);
                    }
                }
            }
        }
    }

    private double updateEma(UUID uuid, double suspicion) {
        double prev = suspicionEma.getOrDefault(uuid, suspicion);
        double ema = prev + EMA_ALPHA * (suspicion - prev);
        suspicionEma.put(uuid, ema);
        return ema;
    }
}