package me.hackerguardian.main.detection;

import me.hackerguardian.main.detection.ml.LogisticRegressionModel;
import me.hackerguardian.main.detection.ml.MlBehaviorDetector;
import me.hackerguardian.main.detection.ml.MlCaptureLabel;
import me.hackerguardian.main.detection.ml.MlDatasetRecorder;
import me.hackerguardian.main.utils.CommandManager;
import me.hackerguardian.main.utils.textHandling;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;

/** Operator surface for inspecting Detection v2 and deliberately collecting ML labels. */
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
                showRuntimeStatus(sender);
                return;
            }

            if (params[0].equalsIgnoreCase("ml")) {
                handleMl(sender, params);
                return;
            }

            if (params[0].equalsIgnoreCase("capture")) {
                handleCapture(sender, params);
                return;
            }

            if (params.length != 1) {
                sendUsage(sender);
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

    private void showRuntimeStatus(org.bukkit.command.CommandSender sender) {
        sender.sendMessage(tx.playerText(tx.prefix + " §fDetection v2"));
        sender.sendMessage(tx.playerText(" §7Mode: §eOBSERVE-ONLY"));
        sender.sendMessage(tx.playerText(" §7Running: " + (runtime.isRunning() ? "§aYES" : "§cNO")));
        sender.sendMessage(tx.playerText(" §7Tracked players: §f" + runtime.getCollector().getTrackedPlayerCount()));
        sender.sendMessage(tx.playerText(" §7Window: §f" + runtime.getCollector().getWindowMs() + "ms"));
        sender.sendMessage(tx.playerText(" §7Detectors: §f" + String.join(", ", runtime.getEngine().getDetectorIds())));

        MlBehaviorDetector ml = runtime.getMlDetector();
        if (ml == null) {
            sender.sendMessage(tx.playerText(" §7ML: §8disabled in detection.yml"));
        } else if (ml.isLoaded()) {
            LogisticRegressionModel model = ml.getModel();
            sender.sendMessage(tx.playerText(" §7ML: §aloaded §f" + model.getModelId()
                    + " §8(" + model.getSchemaId() + ")"));
        } else {
            sender.sendMessage(tx.playerText(" §7ML: §cenabled, model unavailable"));
            sender.sendMessage(tx.playerText(" §8" + ml.getLastLoadError()));
        }

        MlDatasetRecorder recorder = runtime.getDatasetRecorder();
        if (recorder != null) {
            sender.sendMessage(tx.playerText(" §7Labeled captures: §f" + recorder.getActiveCaptures().size()
                    + " §8| §7Dropped rows: §f" + recorder.getDroppedRows()));
        }

        sender.sendMessage(tx.playerText(" §7Inspect: §f/hg detection <player>"));
        sender.sendMessage(tx.playerText(" §7ML status/reload: §f/hg detection ml [reload]"));
        sender.sendMessage(tx.playerText(" §7Training capture: §f/hg detection capture ..."));
    }

    private void handleMl(org.bukkit.command.CommandSender sender, String[] params) {
        MlBehaviorDetector ml = runtime.getMlDetector();
        if (ml == null) {
            sender.sendMessage(tx.playerText(tx.prefix + "§cML inference is disabled in detection.yml."));
            return;
        }

        if (params.length == 1) {
            sender.sendMessage(tx.playerText(tx.prefix + " §fML model"));
            sender.sendMessage(tx.playerText(" §7File: §f" + ml.getModelFile().getPath()));
            if (!ml.isLoaded()) {
                sender.sendMessage(tx.playerText(" §7State: §cunavailable"));
                sender.sendMessage(tx.playerText(" §8" + ml.getLastLoadError()));
                return;
            }

            LogisticRegressionModel model = ml.getModel();
            sender.sendMessage(tx.playerText(" §7State: §aloaded"));
            sender.sendMessage(tx.playerText(" §7Model: §f" + model.getModelId()));
            sender.sendMessage(tx.playerText(" §7Schema: §f" + model.getSchemaId()
                    + " §8| §7Features: §f" + model.getFeatureCount()));
            sender.sendMessage(tx.playerText(String.format(
                    " §7Review threshold: §f%.1f%% §8| §7Training rows: §f%d",
                    model.getDecisionThreshold() * 100.0,
                    model.getTrainingSamples()
            )));
            if (Double.isFinite(model.getValidationAuc())) {
                sender.sendMessage(tx.playerText(String.format(
                        " §7Validation: AUC §f%.3f §8| §7Precision §f%.3f §8| §7Recall §f%.3f",
                        model.getValidationAuc(),
                        model.getValidationPrecision(),
                        model.getValidationRecall()
                )));
            }
            return;
        }

        if (params.length == 2 && params[1].equalsIgnoreCase("reload")) {
            boolean loaded = runtime.reloadMlModel();
            if (loaded) {
                sender.sendMessage(tx.playerText(tx.prefix + "§aML model reloaded successfully."));
            } else {
                sender.sendMessage(tx.playerText(tx.prefix + "§cML model reload failed: " + ml.getLastLoadError()));
            }
            return;
        }

        sender.sendMessage(tx.playerText(tx.prefix + "§cUsage: /hg detection ml [reload]"));
    }

    private void handleCapture(org.bukkit.command.CommandSender sender, String[] params) {
        MlDatasetRecorder recorder = runtime.getDatasetRecorder();
        if (recorder == null) {
            sender.sendMessage(tx.playerText(tx.prefix + "§cML dataset capture is disabled or unavailable."));
            return;
        }

        if (params.length == 2 && params[1].equalsIgnoreCase("list")) {
            List<MlDatasetRecorder.CaptureSessionInfo> captures = recorder.getActiveCaptures();
            sender.sendMessage(tx.playerText(tx.prefix + " §fActive ML training captures: §e" + captures.size()));
            if (captures.isEmpty()) {
                sender.sendMessage(tx.playerText(" §7No deliberately labeled capture sessions are active."));
                return;
            }
            long now = System.currentTimeMillis();
            for (MlDatasetRecorder.CaptureSessionInfo capture : captures) {
                long secondsLeft = Math.max(0L, (capture.getExpiresAtMs() - now) / 1000L);
                sender.sendMessage(tx.playerText(" §8- §f" + capture.getPlayerName()
                        + " §7label §e" + capture.getLabel().name()
                        + " §8| §7" + secondsLeft + "s remaining"));
            }
            return;
        }

        if (params.length == 3 && params[1].equalsIgnoreCase("stop")) {
            boolean stopped = recorder.stopCaptureByPlayerName(params[2]);
            sender.sendMessage(tx.playerText(stopped
                    ? tx.prefix + "§aStopped ML capture for " + params[2] + "."
                    : tx.prefix + "§cNo active ML capture exists for " + params[2] + "."));
            return;
        }

        if (params.length < 3 || params.length > 4) {
            sendCaptureUsage(sender);
            return;
        }

        Player target = Bukkit.getPlayerExact(params[1]);
        if (target == null) {
            sender.sendMessage(tx.playerText(tx.prefix + "§cPlayer not found: " + params[1]));
            return;
        }

        MlCaptureLabel label = MlCaptureLabel.parse(params[2]);
        if (label == null) {
            sender.sendMessage(tx.playerText(tx.prefix + "§cLabel must be LEGIT or CHEAT."));
            return;
        }

        int minutes = runtime.getDefaultCaptureMinutes();
        if (params.length == 4) {
            try {
                minutes = Integer.parseInt(params[3]);
            } catch (NumberFormatException e) {
                sender.sendMessage(tx.playerText(tx.prefix + "§cCapture duration must be a number of minutes."));
                return;
            }
        }
        minutes = Math.max(1, Math.min(minutes, 120));

        MlDatasetRecorder.CaptureSessionInfo capture = recorder.startCapture(
                target.getUniqueId(),
                target.getName(),
                label,
                sender.getName(),
                minutes * 60_000L
        );
        sender.sendMessage(tx.playerText(tx.prefix + "§aStarted " + label.name()
                + " ML capture for " + target.getName() + " for " + minutes + " minute(s)."));
        sender.sendMessage(tx.playerText(" §7Session: §f" + capture.getSessionId()));
        sender.sendMessage(tx.playerText(" §7Dataset: §f" + recorder.getDatasetFile().getPath()));
        sender.sendMessage(tx.playerText(" §eOnly use CHEAT when this is deliberately known/tested behavior; detections never auto-label data."));
    }

    private void sendUsage(org.bukkit.command.CommandSender sender) {
        sender.sendMessage(tx.playerText(tx.prefix + "§cUsage:"));
        sender.sendMessage(tx.playerText(" §7/hg detection [player]"));
        sender.sendMessage(tx.playerText(" §7/hg detection ml [reload]"));
        sendCaptureUsage(sender);
    }

    private void sendCaptureUsage(org.bukkit.command.CommandSender sender) {
        sender.sendMessage(tx.playerText(" §7/hg detection capture <player> <LEGIT|CHEAT> [minutes]"));
        sender.sendMessage(tx.playerText(" §7/hg detection capture stop <player>"));
        sender.sendMessage(tx.playerText(" §7/hg detection capture list"));
    }
}
