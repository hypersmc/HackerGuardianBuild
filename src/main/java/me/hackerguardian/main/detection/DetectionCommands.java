package me.hackerguardian.main.detection;

import me.hackerguardian.main.utils.CommandManager;
import me.hackerguardian.main.utils.textHandling;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Read-only operator surface for validating Detection v2 while it is still
 * observe-only.
 */
public final class DetectionCommands {

    private final DetectionRuntime runtime;
    private final textHandling tx = new textHandling();

    public DetectionCommands(DetectionRuntime runtime) {
        this.runtime = runtime;
    }

    public void register(CommandManager commandManager) {
        commandManager.register("detection", (sender, params) -> {
            if (!sender.hasPermission("hg.admin.detection")) {
                sender.sendMessage(tx.playerText(tx.prefix + "§cYou don't have permission to use this."));
                return;
            }

            if (params.length == 0) {
                sender.sendMessage(tx.playerText(tx.prefix + " §fDetection v2"));
                sender.sendMessage(tx.playerText(" §7Mode: §eOBSERVE-ONLY"));
                sender.sendMessage(tx.playerText(" §7Running: " + (runtime.isRunning() ? "§aYES" : "§cNO")));
                sender.sendMessage(tx.playerText(" §7Tracked players: §f" + runtime.getCollector().getTrackedPlayerCount()));
                sender.sendMessage(tx.playerText(" §7Window: §f" + runtime.getCollector().getWindowMs() + "ms"));
                sender.sendMessage(tx.playerText(" §7Detectors: §f"
                        + String.join(", ", runtime.getEngine().getDetectorIds())));
                sender.sendMessage(tx.playerText(" §7Inspect: §f/hg detection <player>"));
                return;
            }

            if (params.length != 1) {
                sender.sendMessage(tx.playerText(tx.prefix + "§cUsage: /hg detection [player]"));
                return;
            }

            Player target = Bukkit.getPlayerExact(params[0]);
            if (target == null) {
                sender.sendMessage(tx.playerText(tx.prefix + "§cPlayer not found: " + params[0]));
                return;
            }

            DetectionAssessment assessment = runtime.assessNow(target);
            if (assessment == null) {
                sender.sendMessage(tx.playerText(tx.prefix + "§cUnable to create an assessment for that player."));
                return;
            }

            sender.sendMessage(tx.playerText(tx.prefix + " §fDetection v2: §e" + target.getName()));
            sender.sendMessage(tx.playerText(String.format(
                    " §7Risk evidence: §f%.1f%% §8| §7Reliability: §f%.1f%%",
                    assessment.getRiskScore() * 100.0,
                    assessment.getReliability() * 100.0
            )));
            sender.sendMessage(tx.playerText(" §7Window: §f" + assessment.getWindowMs() + "ms"
                    + " §8| §7Findings: §f" + assessment.getFindings().size()));

            if (!assessment.hasFindings()) {
                sender.sendMessage(tx.playerText(" §aNo detector findings in the current observation window."));
                return;
            }

            int shown = 0;
            for (DetectionFinding finding : assessment.getFindings()) {
                sender.sendMessage(tx.playerText(String.format(
                        " §8- §e%s §7score §f%.0f%% §7reliability §f%.0f%%",
                        finding.getDetectorId(),
                        finding.getScore() * 100.0,
                        finding.getReliability() * 100.0
                )));
                sender.sendMessage(tx.playerText("   §7" + finding.getSummary()));
                shown++;
                if (shown >= 5) break;
            }
        });
    }
}
