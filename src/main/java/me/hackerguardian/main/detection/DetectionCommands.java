package me.hackerguardian.main.detection;

import me.hackerguardian.main.detection.learning.LearningRuntime;
import me.hackerguardian.main.detection.ml.LogisticRegressionModel;
import me.hackerguardian.main.detection.ml.MlBehaviorDetector;
import me.hackerguardian.main.detection.ml.MlCaptureLabel;
import me.hackerguardian.main.detection.ml.MlDatasetRecorder;
import me.hackerguardian.main.detection.normality.IsolationForestModel;
import me.hackerguardian.main.detection.normality.NormalityDetector;
import me.hackerguardian.main.detection.telemetry.BehaviorSnapshot;
import me.hackerguardian.main.utils.CommandManager;
import me.hackerguardian.main.utils.textHandling;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/** Operator surface for Detection v2, learning, probes and ML model inspection. */
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
            if (params[0].equalsIgnoreCase("ml")) { handleMl(sender, params); return; }
            if (params[0].equalsIgnoreCase("normality")) { handleNormality(sender, params); return; }
            if (params[0].equalsIgnoreCase("learning")) { handleLearning(sender, params); return; }
            if (params[0].equalsIgnoreCase("probe")) { handleProbe(sender, params); return; }
            if (params[0].equalsIgnoreCase("capture")) { handleCapture(sender, params); return; }

            if (params.length != 1) {
                sendUsage(sender);
                return;
            }

            Player target = Bukkit.getPlayerExact(params[0]);
            if (target == null) {
                sender.sendMessage(tx.playerText(tx.prefix + "§cPlayer not found: " + params[0]));
                return;
            }
            showAssessment(sender, target);
        });
    }

    private void showAssessment(CommandSender sender, Player target) {
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
            if (++shown >= 8) break;
        }
    }

    private void showRuntimeStatus(CommandSender sender) {
        sender.sendMessage(tx.playerText(tx.prefix + " §fDetection v2"));
        sender.sendMessage(tx.playerText(" §7Mode: §eOBSERVE-ONLY"));
        sender.sendMessage(tx.playerText(" §7Running: " + (runtime.isRunning() ? "§aYES" : "§cNO")));
        sender.sendMessage(tx.playerText(" §7Tracked players: §f" + runtime.getCollector().getTrackedPlayerCount()));
        sender.sendMessage(tx.playerText(" §7Window: §f" + runtime.getCollector().getWindowMs() + "ms"));
        sender.sendMessage(tx.playerText(" §7Detectors: §f" + String.join(", ", runtime.getEngine().getDetectorIds())));

        MlBehaviorDetector ml = runtime.getMlDetector();
        if (ml == null) {
            sender.sendMessage(tx.playerText(" §7Supervised ML: §8disabled"));
        } else if (ml.isLoaded()) {
            sender.sendMessage(tx.playerText(" §7Supervised ML: §aloaded §f" + ml.getModel().getModelId()));
        } else {
            sender.sendMessage(tx.playerText(" §7Supervised ML: §cenabled, model unavailable"));
        }

        NormalityDetector normality = runtime.getNormalityDetector();
        if (normality == null) {
            sender.sendMessage(tx.playerText(" §7Population normality: §8disabled"));
        } else if (normality.isLoaded()) {
            sender.sendMessage(tx.playerText(" §7Population normality: §aloaded §f" + normality.getModel().getModelId()));
        } else {
            sender.sendMessage(tx.playerText(" §7Population normality: §cenabled, model unavailable"));
        }

        LearningRuntime learning = runtime.getLearningRuntime();
        sender.sendMessage(tx.playerText(" §7Learning Mode: " + (learning.isEnabled() ? "§aenabled" : "§8disabled")));
        if (learning.isEnabled() && learning.getProbeEngine() != null) {
            sender.sendMessage(tx.playerText(" §7Active probes: §f" + learning.getProbeEngine().getActiveProbeCount()));
        }

        MlDatasetRecorder recorder = runtime.getDatasetRecorder();
        if (recorder != null) {
            sender.sendMessage(tx.playerText(" §7Manual labeled captures: §f" + recorder.getActiveCaptures().size()
                    + " §8| §7Dropped rows: §f" + recorder.getDroppedRows()));
        }
        sender.sendMessage(tx.playerText(" §7Inspect: §f/hg detection <player>"));
        sender.sendMessage(tx.playerText(" §7Learning: §f/hg detection learning [player]"));
        sender.sendMessage(tx.playerText(" §7Models: §f/hg detection ml §8| §f/hg detection normality"));
    }

    private void handleMl(CommandSender sender, String[] params) {
        MlBehaviorDetector ml = runtime.getMlDetector();
        if (ml == null) {
            sender.sendMessage(tx.playerText(tx.prefix + "§cSupervised ML inference is disabled in detection.yml."));
            return;
        }
        if (params.length == 1) {
            sender.sendMessage(tx.playerText(tx.prefix + " §fSupervised ML model"));
            sender.sendMessage(tx.playerText(" §7File: §f" + ml.getModelFile().getPath()));
            if (!ml.isLoaded()) {
                sender.sendMessage(tx.playerText(" §7State: §cunavailable"));
                sender.sendMessage(tx.playerText(" §8" + ml.getLastLoadError()));
                return;
            }
            LogisticRegressionModel model = ml.getModel();
            sender.sendMessage(tx.playerText(" §7State: §aloaded §8| §7Model: §f" + model.getModelId()));
            sender.sendMessage(tx.playerText(" §7Schema: §f" + model.getSchemaId()
                    + " §8| §7Features: §f" + model.getFeatureCount()));
            sender.sendMessage(tx.playerText(String.format(
                    " §7Review threshold: §f%.1f%% §8| §7Training rows: §f%d",
                    model.getDecisionThreshold() * 100.0, model.getTrainingSamples())));
            if (Double.isFinite(model.getValidationAuc())) {
                sender.sendMessage(tx.playerText(String.format(
                        " §7Validation: AUC §f%.3f §8| §7Precision §f%.3f §8| §7Recall §f%.3f",
                        model.getValidationAuc(), model.getValidationPrecision(), model.getValidationRecall())));
            }
            return;
        }
        if (params.length == 2 && params[1].equalsIgnoreCase("reload")) {
            boolean loaded = runtime.reloadMlModel();
            sender.sendMessage(tx.playerText(loaded
                    ? tx.prefix + "§aSupervised ML model reloaded successfully."
                    : tx.prefix + "§cML model reload failed: " + ml.getLastLoadError()));
            return;
        }
        sender.sendMessage(tx.playerText(tx.prefix + "§cUsage: /hg detection ml [reload]"));
    }

    private void handleNormality(CommandSender sender, String[] params) {
        NormalityDetector detector = runtime.getNormalityDetector();
        if (detector == null) {
            sender.sendMessage(tx.playerText(tx.prefix + "§cPopulation-normality inference is disabled in detection.yml."));
            return;
        }
        if (params.length == 1) {
            sender.sendMessage(tx.playerText(tx.prefix + " §fPopulation normality model"));
            sender.sendMessage(tx.playerText(" §7File: §f" + detector.getModelFile().getPath()));
            if (!detector.isLoaded()) {
                sender.sendMessage(tx.playerText(" §7State: §cunavailable"));
                sender.sendMessage(tx.playerText(" §8" + detector.getLastLoadError()));
                return;
            }
            IsolationForestModel model = detector.getModel();
            sender.sendMessage(tx.playerText(" §7State: §aloaded §8| §7Model: §f" + model.getModelId()));
            sender.sendMessage(tx.playerText(" §7Trees: §f" + model.getTreeCount()
                    + " §8| §7Players: §f" + model.getTrainingPlayers()
                    + " §8| §7Samples: §f" + model.getTrainingSamples()));
            sender.sendMessage(tx.playerText(String.format(
                    " §7Anomaly review threshold: §f%.1f%% §8| §7Normal p99: §f%.1f%%",
                    model.getDecisionThreshold() * 100.0,
                    Double.isFinite(model.getValidationP99()) ? model.getValidationP99() * 100.0 : 0.0)));
            return;
        }
        if (params.length == 2 && params[1].equalsIgnoreCase("reload")) {
            boolean loaded = runtime.reloadNormalityModel();
            sender.sendMessage(tx.playerText(loaded
                    ? tx.prefix + "§aPopulation-normality model reloaded successfully."
                    : tx.prefix + "§cNormality model reload failed: " + detector.getLastLoadError()));
            return;
        }
        sender.sendMessage(tx.playerText(tx.prefix + "§cUsage: /hg detection normality [reload]"));
    }

    private void handleLearning(CommandSender sender, String[] params) {
        LearningRuntime learning = runtime.getLearningRuntime();
        if (!learning.isEnabled()) {
            sender.sendMessage(tx.playerText(tx.prefix + "§cLearning Mode v2 is disabled in detection.yml."));
            sender.sendMessage(tx.playerText(" §7Enable §fDetectionV2.learning.enabled §7to collect trusted-population normality data."));
            return;
        }

        if (params.length == 1) {
            sender.sendMessage(tx.playerText(tx.prefix + " §fLearning Mode v2"));
            sender.sendMessage(tx.playerText(" §7Trusted permission: §f" + learning.getTrustedPermission()));
            sender.sendMessage(tx.playerText(String.format(
                    " §7Quarantine: §f%.1f days §8| §7Minimum baseline: §f%.1f hours",
                    learning.getQuarantineDays(), learning.getMinimumBaselineHours())));
            if (learning.getDatasetRecorder() != null) {
                sender.sendMessage(tx.playerText(" §7Candidate dataset: §f" + learning.getDatasetRecorder().getDatasetFile().getPath()));
                sender.sendMessage(tx.playerText(" §7Rows written: §f" + learning.getDatasetRecorder().getWrittenRows()
                        + " §8| §7Dropped: §f" + learning.getDatasetRecorder().getDroppedRows()));
            }
            sender.sendMessage(tx.playerText(" §7Trust manifest: §f" + learning.getStateStore().getManifestPath()));
            if (learning.getProbeEngine() != null) {
                sender.sendMessage(tx.playerText(String.format(
                        " §7Probes: §a%s §8| §7minimum %.1fh §8| §7active §f%d",
                        learning.getProbeEngine().isEnabled() ? "enabled" : "disabled",
                        learning.getProbeEngine().getMinimumCollectedHours(),
                        learning.getProbeEngine().getActiveProbeCount())));
            }
            sender.sendMessage(tx.playerText(" §7Inspect player: §f/hg detection learning <player>"));
            sender.sendMessage(tx.playerText(" §7Force probe: §f/hg detection probe <player>"));
            return;
        }

        if (params.length == 2) {
            Player target = Bukkit.getPlayerExact(params[1]);
            if (target == null) {
                sender.sendMessage(tx.playerText(tx.prefix + "§cPlayer must be online to inspect current trust: " + params[1]));
                return;
            }
            LearningRuntime.PlayerStatus status = learning.getPlayerStatus(target);
            sender.sendMessage(tx.playerText(tx.prefix + " §fLearning: §e" + target.getName()));
            sender.sendMessage(tx.playerText(" §7Trusted now: " + (status.isTrusted() ? "§aYES" : "§cNO")
                    + " §8| §7Source: §f" + status.getTrustSource()));
            sender.sendMessage(tx.playerText(String.format(
                    " §7Collected: §f%.2f h §8| §7minimum reached: %s",
                    status.getCollectedHours(), status.isBaselineHoursMet() ? "§aYES" : "§eNO")));
            sender.sendMessage(tx.playerText(" §7Session: §f" + (status.getSessionId() == null ? "none" : status.getSessionId())));
            sender.sendMessage(tx.playerText(" §7Last probe: §f" + (status.getLastProbeMs() <= 0L ? "never" : status.getLastProbeMs())));
            return;
        }
        sender.sendMessage(tx.playerText(tx.prefix + "§cUsage: /hg detection learning [player]"));
    }

    private void handleProbe(CommandSender sender, String[] params) {
        if (params.length != 2) {
            sender.sendMessage(tx.playerText(tx.prefix + "§cUsage: /hg detection probe <player>"));
            return;
        }
        LearningRuntime learning = runtime.getLearningRuntime();
        if (!learning.isEnabled() || learning.getProbeEngine() == null || !learning.getProbeEngine().isEnabled()) {
            sender.sendMessage(tx.playerText(tx.prefix + "§cLearning probes are disabled or unavailable."));
            return;
        }
        Player target = Bukkit.getPlayerExact(params[1]);
        if (target == null) {
            sender.sendMessage(tx.playerText(tx.prefix + "§cPlayer not found: " + params[1]));
            return;
        }
        BehaviorSnapshot snapshot = runtime.snapshotNow(target);
        boolean started = learning.forceProbe(target, snapshot);
        sender.sendMessage(tx.playerText(started
                ? tx.prefix + "§aStarted a client-side behavior probe for " + target.getName() + "."
                : tx.prefix + "§cProbe could not start (already active or no safe spawn position)."));
    }

    private void handleCapture(CommandSender sender, String[] params) {
        MlDatasetRecorder recorder = runtime.getDatasetRecorder();
        if (recorder == null) {
            sender.sendMessage(tx.playerText(tx.prefix + "§cManual supervised ML dataset capture is disabled or unavailable."));
            return;
        }

        if (params.length == 2 && params[1].equalsIgnoreCase("list")) {
            List<MlDatasetRecorder.CaptureSessionInfo> captures = recorder.getActiveCaptures();
            sender.sendMessage(tx.playerText(tx.prefix + " §fActive supervised captures: §e" + captures.size()));
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
                    ? tx.prefix + "§aStopped supervised ML capture for " + params[2] + "."
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
            try { minutes = Integer.parseInt(params[3]); }
            catch (NumberFormatException e) {
                sender.sendMessage(tx.playerText(tx.prefix + "§cCapture duration must be a number of minutes."));
                return;
            }
        }
        minutes = Math.max(1, Math.min(minutes, 120));
        MlDatasetRecorder.CaptureSessionInfo capture = recorder.startCapture(
                target.getUniqueId(), target.getName(), label, sender.getName(), minutes * 60_000L);
        sender.sendMessage(tx.playerText(tx.prefix + "§aStarted " + label.name()
                + " supervised capture for " + target.getName() + " for " + minutes + " minute(s)."));
        sender.sendMessage(tx.playerText(" §7Session: §f" + capture.getSessionId()));
        sender.sendMessage(tx.playerText(" §7Dataset: §f" + recorder.getDatasetFile().getPath()));
        sender.sendMessage(tx.playerText(" §eManual labels remain separate from Learning Mode candidate-normal data."));
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(tx.playerText(tx.prefix + "§cUsage:"));
        sender.sendMessage(tx.playerText(" §7/hg detection [player]"));
        sender.sendMessage(tx.playerText(" §7/hg detection learning [player]"));
        sender.sendMessage(tx.playerText(" §7/hg detection probe <player>"));
        sender.sendMessage(tx.playerText(" §7/hg detection normality [reload]"));
        sender.sendMessage(tx.playerText(" §7/hg detection ml [reload]"));
        sendCaptureUsage(sender);
    }

    private void sendCaptureUsage(CommandSender sender) {
        sender.sendMessage(tx.playerText(" §7/hg detection capture <player> <LEGIT|CHEAT> [minutes]"));
        sender.sendMessage(tx.playerText(" §7/hg detection capture stop <player>"));
        sender.sendMessage(tx.playerText(" §7/hg detection capture list"));
    }
}
