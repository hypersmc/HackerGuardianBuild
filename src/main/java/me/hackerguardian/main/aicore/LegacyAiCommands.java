package me.hackerguardian.main.aicore;

import me.hackerguardian.main.HackerGuardian;
import me.hackerguardian.main.utils.CommandManager;
import me.hackerguardian.main.utils.textHandling;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.UUID;

/**
 * Temporary command surface for the current Neuroph-based AI implementation.
 *
 * The AI architecture is intentionally isolated from HackerGuardian's core
 * lifecycle so it can be replaced without touching moderation, reports,
 * replays, or database startup.
 */
public final class LegacyAiCommands {

    private final HackerGuardian plugin;
    private final textHandling tx = new textHandling();

    public LegacyAiCommands(HackerGuardian plugin) {
        this.plugin = plugin;
    }

    public void register(CommandManager commandManager) {
        registerLearning(commandManager);
        registerInspect(commandManager);
        registerStats(commandManager);
        registerModel(commandManager);
        registerLabel(commandManager);
        registerThreshold(commandManager);
        registerOutput(commandManager);
    }

    private void registerLearning(CommandManager commandManager) {
        commandManager.register("learning", (sender, params) -> {
            if (!sender.hasPermission("hg.admin.learning")) return;

            if (params.length == 0) {
                sender.sendMessage(tx.playerText("§eLearning mode is currently: "
                        + (plugin.isLearning() ? "§aON" : "§cOFF")));
                return;
            }

            if (params.length != 1
                    || (!params[0].equalsIgnoreCase("on") && !params[0].equalsIgnoreCase("off"))) {
                sender.sendMessage(tx.playerText("§cUsage: /hg learning <on|off>"));
                return;
            }

            boolean enable = params[0].equalsIgnoreCase("on");
            plugin.setLearning(enable);
            plugin.getConfig().set("Settings.LearningMode", enable);
            plugin.saveConfig();
            sender.sendMessage(tx.playerText("§eLearning mode set to: " + (enable ? "§aON" : "§cOFF")));
        });
    }

    private void registerInspect(CommandManager commandManager) {
        commandManager.register("inspect", (sender, params) -> {
            if (!sender.hasPermission("hg.admin.inspect")) {
                sender.sendMessage(tx.playerText("§cYou don't have permission to use this."));
                return;
            }

            if (params.length != 1) {
                sender.sendMessage(tx.playerText("§cUsage: /hg inspect <player>"));
                return;
            }

            Player target = Bukkit.getPlayerExact(params[0]);
            if (target == null) {
                sender.sendMessage(tx.playerText("§cPlayer not found: " + params[0]));
                return;
            }

            AiManager ai = plugin.getAiManager();
            if (!ai.hasAnyTraining() && !plugin.isLearning()) {
                sender.sendMessage(tx.playerText(tx.prefix));
                sender.sendMessage(tx.playerText("§cThe AI model does not have enough global training data yet."));
                sender.sendMessage(tx.playerText("§7Run a training phase with §f/hg learning on §7on a trusted server,"));
                sender.sendMessage(tx.playerText("§7then save the model with §f/hg model save §7and load it here."));
                return;
            }

            FeatureCollector fc = plugin.getFeatureCollector();
            HGSuspicionManager sm = plugin.getSuspicionManager();
            UUID uuid = target.getUniqueId();
            long lastUpdate = sm.getLastUpdate(uuid);

            if (lastUpdate == 0L) {
                double[] features = fc.buildSample(target);
                if (features == null) {
                    sender.sendMessage(tx.playerText(tx.prefix + " §ehas not observed enough behaviour from §f"
                            + target.getName() + " §eyet to inspect them reliably."));
                    sender.sendMessage(tx.playerText("§7(They need to move/fight a bit first.)"));
                    return;
                }

                double suspicion = ai.evaluate(features);
                sm.updateMovement(uuid, suspicion);
                lastUpdate = sm.getLastUpdate(uuid);
            }

            double movement = sm.getMovementSuspicion(uuid);
            double combat = sm.getCombatSuspicion(uuid);
            double overall = sm.getOverallSuspicion(uuid);

            long diffMs = Math.max(0L, System.currentTimeMillis() - lastUpdate);
            long totalSeconds = diffMs / 1000;
            long hours = totalSeconds / 3600;
            long minutes = (totalSeconds % 3600) / 60;
            long seconds = totalSeconds % 60;

            String formattedTime;
            if (hours > 0) formattedTime = String.format("%dh %dm %ds", hours, minutes, seconds);
            else if (minutes > 0) formattedTime = String.format("%dm %ds", minutes, seconds);
            else formattedTime = String.format("%ds", seconds);

            sender.sendMessage(tx.playerText(tx.prefix + " §fInspecting §e" + target.getName()));
            sender.sendMessage(tx.playerText(String.format(" §7Movement suspicion: §f%.1f%%", movement * 100.0)));
            sender.sendMessage(tx.playerText(String.format(" §7Combat suspicion:   §f%.1f%%", combat * 100.0)));
            sender.sendMessage(tx.playerText(String.format(" §7Overall suspicion:  §f%.1f%%", overall * 100.0)));
            sender.sendMessage(tx.playerText(" §7Last updated: §f" + formattedTime + " ago"));
        });
    }

    private void registerStats(CommandManager commandManager) {
        commandManager.register("stats", (sender, params) -> {
            if (!sender.hasPermission("hg.admin.stats")) {
                sender.sendMessage("§cYou don't have permission to use this.");
                return;
            }

            AiManager ai = plugin.getAiManager();
            File modelFile = plugin.getAiModelFile();

            sender.sendMessage(tx.playerText(tx.prefix + " Stats:"));
            sender.sendMessage(tx.playerText(" §7Learning mode: " + (plugin.isLearning() ? "§aON" : "§cOFF")));
            sender.sendMessage(tx.playerText(" §7Suspicion threshold: §f"
                    + String.format("%.2f", plugin.getSuspicionThreshold())));

            if (modelFile != null && modelFile.exists()) {
                sender.sendMessage(tx.playerText(" §7Model file: §a" + modelFile.getName()
                        + " §7(" + modelFile.length() + " bytes)"));
            } else {
                sender.sendMessage(tx.playerText(" §7Model file: §cnot found"));
            }

            sender.sendMessage(tx.playerText(" §7Training samples seen: §f" + ai.getTotalLearnSamples()));
            sender.sendMessage(tx.playerText(" §7Samples used in training: §f" + ai.getTotalTrainedSamples()));
            sender.sendMessage(tx.playerText(" §7Training runs: §f" + ai.getTotalTrainCalls()));
            sender.sendMessage(tx.playerText(" §7Has any training: "
                    + (ai.hasAnyTraining() ? "§aYES" : "§cNO")));
        });
    }

    private void registerModel(CommandManager commandManager) {
        commandManager.register("model", (sender, params) -> {
            if (!sender.hasPermission("hg.admin.model")) {
                sender.sendMessage(tx.playerText("§cYou don't have permission to use this."));
                return;
            }

            if (params.length != 1) {
                sender.sendMessage(tx.playerText(tx.shortprefix + "§cUsage: /hg model <save|load>"));
                return;
            }

            AiManager ai = plugin.getAiManager();
            File modelFile = plugin.getAiModelFile();
            if (modelFile == null) {
                sender.sendMessage(tx.playerText("§cAI model path is not initialized."));
                return;
            }

            switch (params[0].toLowerCase()) {
                case "save" -> {
                    ai.trainFromBuffer();
                    ai.saveToFile(modelFile);
                    sender.sendMessage(tx.playerText(tx.shortprefix + "§7AI model saved to §c" + modelFile.getName()));
                }
                case "load" -> {
                    if (!modelFile.exists()) {
                        sender.sendMessage(tx.playerText(tx.shortprefix + "§cModel file not found: " + modelFile.getName()));
                        return;
                    }
                    ai.loadFromFile(modelFile);
                    sender.sendMessage(tx.playerText(tx.shortprefix + "§7AI model loaded from §c" + modelFile.getName()));
                }
                default -> sender.sendMessage(tx.playerText(tx.shortprefix + "§cUsage: /hg model <save|load>"));
            }
        });
    }

    private void registerLabel(CommandManager commandManager) {
        commandManager.register("label", (sender, params) -> {
            if (!sender.hasPermission("hg.admin.label")) {
                sender.sendMessage(tx.playerText("§cYou don't have permission to use this."));
                return;
            }

            if (params.length != 2) {
                sender.sendMessage(tx.playerText("§cUsage: /hg label <player> <legit|cheat>"));
                return;
            }

            Player target = Bukkit.getPlayerExact(params[0]);
            if (target == null) {
                sender.sendMessage(tx.playerText("§cPlayer not found: " + params[0]));
                return;
            }

            boolean cheat;
            if (params[1].equalsIgnoreCase("legit")) cheat = false;
            else if (params[1].equalsIgnoreCase("cheat") || params[1].equalsIgnoreCase("hack")) cheat = true;
            else {
                sender.sendMessage(tx.playerText("§cUsage: /hg label <player> <legit|cheat>"));
                return;
            }

            double[] features = plugin.getFeatureCollector().buildSample(target);
            if (features == null) {
                sender.sendMessage(tx.playerText("§eNot enough data for §f" + target.getName() + "§e yet."));
                return;
            }

            plugin.getAiManager().learn(features, cheat ? 1.0 : 0.0);
            sender.sendMessage(tx.playerText("§eLabeled §f" + target.getName() + " §eas "
                    + (cheat ? "§cCHEAT" : "§aLEGIT") + " §efor training."));
        });
    }

    private void registerThreshold(CommandManager commandManager) {
        commandManager.register("threshold", (sender, params) -> {
            if (!sender.hasPermission("hg.admin.threshold")) {
                sender.sendMessage(tx.playerText("§cYou don't have permission to use this."));
                return;
            }

            if (params.length == 0) {
                sender.sendMessage(tx.playerText("§eCurrent suspicion threshold: §f"
                        + String.format("%.2f", plugin.getSuspicionThreshold())));
                sender.sendMessage(tx.playerText("§7Example: §f/hg threshold 0.85"));
                return;
            }

            if (params.length != 1) {
                sender.sendMessage(tx.playerText("§cUsage: /hg threshold [0.0-1.0]"));
                return;
            }

            try {
                double value = Double.parseDouble(params[0]);
                if (value < 0.0 || value > 1.0) {
                    sender.sendMessage(tx.playerText("§cValue must be between 0.0 and 1.0"));
                    return;
                }
                plugin.setSuspicionThreshold(value);
                sender.sendMessage(tx.playerText("§eSuspicion threshold set to §f" + String.format("%.2f", value)));
            } catch (NumberFormatException e) {
                sender.sendMessage(tx.playerText("§cInvalid number: " + params[0]));
            }
        });
    }

    private void registerOutput(CommandManager commandManager) {
        commandManager.register("output", (sender, params) -> {
            if (!sender.hasPermission("hg.admin.output")) {
                sender.sendMessage(tx.playerText("§cYou don't have permission to use this."));
                return;
            }

            AiManager ai = plugin.getAiManager();
            if (ai.getNetwork() == null) {
                sender.sendMessage(tx.playerText("§cAI network is not initialized."));
                return;
            }

            File outDir = new File(plugin.getDataFolder(), "nn_output");
            File outFile = new File(outDir, "index.html");

            try {
                NetworkHtmlExporter.exportHtml(ai.getNetwork(), outFile);
                sender.sendMessage(tx.playerText("§aNeural network visualization exported to:"));
                sender.sendMessage(tx.playerText("§f" + outFile.getAbsolutePath()));
                sender.sendMessage(tx.playerText("§7Open that index.html in your browser."));
            } catch (Exception e) {
                sender.sendMessage(tx.playerText("§cFailed to export network HTML. Check console for details."));
                plugin.getLogger().warning("Failed to export AI network HTML: " + e.getMessage());
                if (plugin.getConfig().getBoolean("debug")) e.printStackTrace();
            }
        });
    }
}
