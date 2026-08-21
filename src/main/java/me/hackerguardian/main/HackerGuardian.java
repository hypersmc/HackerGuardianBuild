package me.hackerguardian.main;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;

import me.hackerguardian.Util.LinkErrorHandler;
import me.hackerguardian.api.HgApiServerBackend;
import me.hackerguardian.main.aicore.*;
import me.hackerguardian.main.aicore.aievents.*;
import me.hackerguardian.main.hglink.LinkVerifier;
import me.hackerguardian.main.inv.InventoryClickListener;
import me.hackerguardian.main.inv.info;
import me.hackerguardian.main.inv.infoManager;
import me.hackerguardian.main.moderation.punish.*;
import me.hackerguardian.main.modsys.HGModFingerprintListener;
import me.hackerguardian.main.modsys.ModFingerprint;
import me.hackerguardian.main.modsys.ModFingerprintManager;
import me.hackerguardian.main.replay.ReplayBukkitListener;
import me.hackerguardian.main.replay.ReplayCommand;
import me.hackerguardian.main.replay.ReplayManager;
import me.hackerguardian.main.replay.view.ReplayViewer;
import me.hackerguardian.main.report.ReportCommands;
import me.hackerguardian.main.report.ReportRepository;
import me.hackerguardian.main.report.ReportService;
import me.hackerguardian.main.utils.*;
import me.hackerguardian.main.webserver.WebSQL;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * @author JumpWatch on 27-03-2023
 * @Project HackerGuardianV2
 * v1.0.0
 */
public class HackerGuardian extends JavaPlugin {
    private static HackerGuardian instance;
    private MySQL mysql;
    private ModFingerprintManager fpManager;
    private HGModFingerprintListener fpListener;
    private ReportRepository repo;
    private ReportService reportService;
    private infoManager infoManager;
    final util util = new util();
    private CommandManager commandManager;
    private FeatureCollector featureCollector;
    private AiManager aiManager;
    public boolean learning;
    private File modelFile;
    private double suspicionThreshold = 0.80; // default 80%
    private final HGSuspicionManager suspicionManager = new HGSuspicionManager();
    private static final int HELP_PAGE_SIZE = 7;
    public PunishmentRepository punishRepo;
    private PunishmentService punishService;
    private PunishAnnouncer announcer = new PunishAnnouncer(this);
    public WebSQL webSQL;
    private AIService aiservice;
    private ReplayManager replayManager;
    private ReplayViewer replayViewer;
    private HgApiServerBackend api;

    @Override
    public void onEnable() {
        instance = this;
        mysql = new MySQL(this);
        mysql.init();
        initializeFirstSetup();
        initializeSettings();
        initializeAI();
        webSQL = new WebSQL(getMySQL().getDataSource());
        aiservice = new AIService(this, webSQL);
        punishRepo = new PunishmentRepository(getMySQL().getDataSource());
        punishService = new PunishmentService(this, punishRepo);
        replayManager = new ReplayManager(this, getMySQL().getDataSource());
        replayViewer = new ReplayViewer(this, replayManager.getStorage());
        registerAllEvents();

        commandManager = new CommandManager(this, "HackerGuardian");
        commandManager = new CommandManager(this, "HG");
        regiserCommands();
        //INIT LINKER
        if (getConfig().getBoolean("Settings.hg_secure_link")) {
            String channel = "hg:playerchannel";
            LinkVerifier verifier = new LinkVerifier(this, new LinkErrorHandler(this));

            getServer().getMessenger().registerIncomingPluginChannel(this, channel, verifier);
            getServer().getMessenger().registerOutgoingPluginChannel(this, channel);

            getServer().getPluginManager().registerEvents(verifier, this);
            getLogger().info("HG Link backend enabled.");
        }
        fpManager = new ModFingerprintManager();
        fpListener = new HGModFingerprintListener(this, fpManager);
        fpListener.enable();


        repo = new ReportRepository(getMySQL().getDataSource());
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try { repo.ensureTables(); } catch (Exception e) { e.printStackTrace(); }
        });
        reportService = new ReportService(this, repo);
        new ReportCommands(this, reportService).register(commandManager);

        infoManager = new infoManager();

        // Cleanup task for Punishment
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            try {
                int cleaned = punishRepo.cleanupExpired(System.currentTimeMillis());
                if (cleaned > 0 && getConfig().getBoolean("debug")) {
                    getLogger().info("[HG] Cleaned " + cleaned + " expired punishments.");
                }
            } catch (Exception ignored) {}
        }, 20L * 60L, 20L * 600L); // start after 60s, repeat every 10 min
        api = new HgApiServerBackend(this);
        api.startIfEnabled();
    }
    private void initializeFirstSetup() {
        loadConfig();
        getServer().getConsoleSender().sendMessage(
                "\n \n" + ChatColor.DARK_GRAY + "[]=====["
                        + ChatColor.GRAY +"Enabling "+ getDescription().getName()  + ChatColor.RESET
                        + ChatColor.DARK_GRAY + "]=====[]" + ChatColor.RESET + "\n"
                        + ChatColor.DARK_GRAY + "| " + ChatColor.RESET
                        + ChatColor.RED + "Logged info:"  + ChatColor.RESET + "\n"
                        + ChatColor.DARK_GRAY +"|   " + ChatColor.RESET
                        + ChatColor.RED +"Name: " + ChatColor.RESET
                        + ChatColor.GRAY + getDescription().getName()  + ChatColor.RESET + "\n"
                        + ChatColor.DARK_GRAY +"|   " + ChatColor.RESET
                        + ChatColor.RED +"Developer: " + ChatColor.RESET
                        + ChatColor.GRAY + getDescription().getAuthors().toString().replace("[", "").replace("]", "") + ChatColor.RESET +"\n"
                        + ChatColor.DARK_GRAY +"|   " + ChatColor.RESET
                        + ChatColor.RED +"Version: " + ChatColor.RESET
                        + ChatColor.GRAY +"v" + getDescription().getVersion() + ChatColor.RESET + "\n"
                        + ChatColor.DARK_GRAY +"|   " + ChatColor.RESET
                        + ChatColor.RED +"Dependencies: " + ChatColor.RESET + "\n"
                        + util.detectPluginProtocollib()
                        + util.detectPluginSkulls()
                        + ChatColor.DARK_GRAY +"|   " + ChatColor.RESET
                        + ChatColor.RED +"Soft Dependencies: " + ChatColor.RESET + "\n"
                        + util.detectPluginProtocolsupport()
                        + util.detectPluginViaversion()
                        + ChatColor.DARK_GRAY +"|   " + ChatColor.RESET
                        + ChatColor.RED +"Features enabled: " + ChatColor.RESET + "\n"
                        + util.detectSettingAI(this)
                        + util.detectSettingWebsite(this)
                        + util.detectSettingsSecureLink(this)
                        + ChatColor.DARK_GRAY + "[]=====["
                        + ChatColor.GRAY +"Enabling "+ getDescription().getName()  + ChatColor.RESET
                        + ChatColor.DARK_GRAY + "]=====[]" + ChatColor.RESET + "\n\n");
        AIPermissions.setLearningFilesPermissions(this);

    }

    public void loadConfig() {
        saveDefaultConfig();
        reloadConfig();
    }
    @Override
    public void onDisable() {
        if (mysql != null) mysql.shutdown();
        if (aiManager != null) {
            aiManager.trainFromBuffer();
            aiManager.saveToFile(modelFile);
        }
        fpListener.disable();
    }

    public static HackerGuardian getInstance() {
        return instance;
    }
    public MySQL getMySQL() { return mysql; }




    public void initializeAI() {
        if (this.getConfig().getBoolean("Settings.EnableAI")) {
            this.featureCollector = new FeatureCollector();
            this.aiManager = new AiManager(FeatureCollector.FEATURE_COUNT, learning);
            this.modelFile = new File(getDataFolder(), "ai_model.nnet");
            aiManager.loadFromFile(modelFile);
            startAiTrainingTask();
        }
    }
    private void registerAllEvents() {
        if (this.getConfig().getBoolean("Settings.EnableAI")) { //AI events
            getServer().getPluginManager().registerEvents(new onPlayerJoin(), this); //special

            getServer().getPluginManager().registerEvents(new HGPlayerInteractListener(), this);
            getServer().getPluginManager().registerEvents(new HGPlayerItemConsumeListener(), this);
            getServer().getPluginManager().registerEvents(new HGPlayerMoveListener(), this);
            getServer().getPluginManager().registerEvents(new HGPlayerToggleFlightListener(), this);
            getServer().getPluginManager().registerEvents(new HGPlayerToggleStateListener(), this);
            getServer().getPluginManager().registerEvents(new HGEntityDamageByEntityListener(aiservice), this);
            getServer().getPluginManager().registerEvents(new HGBlockPlaceListener(), this);
            getServer().getPluginManager().registerEvents(new HGBlockBreakListener(), this);
            getServer().getPluginManager().registerEvents(new HGPlayerQuitListener(), this);
            getServer().getPluginManager().registerEvents(new HGPlayerKickListener(), this);
            ProtocolManager pm = ProtocolLibrary.getProtocolManager();
            pm.addPacketListener(new me.hackerguardian.main.aicore.aievents.HGPacketListener(this));
            getServer().getPluginManager().registerEvents(new PunishListeners(this, punishRepo), this);
            getServer().getPluginManager().registerEvents(new InventoryClickListener(infoManager), this);

        }
        Bukkit.getServer().getScheduler().scheduleSyncRepeatingTask(this, new Tps(), 100L, 1L);
        Bukkit.getPluginManager().registerEvents(new ReplayBukkitListener(replayManager), this);
        Bukkit.getPluginManager().registerEvents(new ReplayViewer(this, replayManager.getStorage()), this);

    }



    private void initializeSettings() {
        try {
            FileUtil.saveResourceIfAbsent(this, "config.yml", "config.yml");
        } catch (IOException e) {
            ErrorHandler.handleIOException(e, "Error saving config.yml");
        }
        learning = this.getConfig().getBoolean("Settings.LearningMode");

    }


    private final List<HelpEntry> HELP_ENTRIES = List.of(
            new HelpEntry("/hg help", "Show this help"),
            new HelpEntry("/hg stats", "Show AI status"),
            new HelpEntry("/hg learning <on|off>", "Toggle learning mode"),
            new HelpEntry("/hg model <save|load>", "Save/load AI model"),
            new HelpEntry("/hg inspect <player>", "Inspect a player's suspicion"),
            new HelpEntry("/hg label <player> <legit|cheat>", "Add a training label"),
            new HelpEntry("/hg threshold [value]", "Get/set suspicion threshold"),
            new HelpEntry("/hg view", "Checks every statistic HG have on a player"),
            new HelpEntry("/hg ban <player> <arg> <reason>", "Ban a player w/wo a reason"),
            new HelpEntry("/hg unban <player>", "Unban a player who is banned"),
            new HelpEntry("/hg mute <player> <reason>", "Mute a player w/wo a reason"),
            new HelpEntry("/hg unmute <player>", "Unmute a player who is muted"),
            new HelpEntry("/hg kick <player> <reason>", "Kick a player w/wo a reason"),
            new HelpEntry("/hg tps", "Get servers current TPS"),
            new HelpEntry("/hg checkbannedip <ip>", "Check banned IPs and their player counts")
            // add more later
    );
    private void sendHelpPage(CommandSender sender, int page) {
        textHandling tx = new textHandling();
        int totalPages = (int) Math.ceil(HELP_ENTRIES.size() / (double) HELP_PAGE_SIZE);

        if (page < 1 || page > totalPages) {
            sender.sendMessage(tx.playerText(tx.prefix + "Sorry but &c" + page + "&r is not a valid page. (1-" + totalPages + ")"));
            return;
        }

        sender.sendMessage(tx.playerText("&8&l<&7&m-------------]&r&l&4Help&7&m[-------------&r&8&l>"));
        sender.sendMessage(tx.playerText(tx.prefix + "Command list:"));

        int from = (page - 1) * HELP_PAGE_SIZE;
        int to = Math.min(from + HELP_PAGE_SIZE, HELP_ENTRIES.size());

        for (int i = from; i < to; i++) {
            HelpEntry e = HELP_ENTRIES.get(i);
            sender.sendMessage(tx.playerText(e.usage + " §7- " + e.desc));
        }

        sender.sendMessage(tx.playerText("&8&l<&7&m---------]&r&l&4Menu " + page + "/" + totalPages + "&7&m[---------&r&8&l>"));
    }
    private void regiserCommands() {
        textHandling tx = new textHandling();


        commandManager.register("test", (sender, params) ->{
            Player psender = (Player) sender;
            Player target = Bukkit.getPlayerExact(params[0]);
            if (target == null) {
                sender.sendMessage(tx.playerText("§cPlayer not found: " + params[0]));
                return;
            }
            if (params.length == 1) {
                me.hackerguardian.main.inv.infoManager infomanager = getInfoManager();
                infomanager.resetInfo(psender, params[0]);

                info infoInstance = getInfoManager().getInfo(psender, target.getName());
                infoInstance.open(psender);
            }


        });
        /*
         * init command /hg
         */
        commandManager.register("", (sender, params) ->{
            if (CommandValidate.notPlayer(sender)) return;
            if (!CommandValidate.noPerm(sender, "main"));
            sender.sendMessage(tx.playerText("&8&l<&7&m-------------]&r&l&4Info&7&m[-------------&r&8&l>"));
            sender.sendMessage(tx.playerText(tx.shortprefix + "Hello " + sender.getName() + ". This server is running " + tx.prefix + "version: " + this.getDescription().getVersion()));
            sender.sendMessage(tx.playerText(tx.shortprefix + "My maker(s) are '" + ChatColor.RED + this.getDescription().getAuthors().toString().replace("[", "").replace("]", "") + ChatColor.RESET + "'"));
        });
        /*
          Help command /hg help <page>
              page number is optional
         */
        commandManager.register("help", (sender, params) -> {
            if (!CommandValidate.noPerm(sender, "help")) return;

            if (params.length > 1) {
                sender.sendMessage(tx.playerText(tx.prefix + "Usage: /hg help [page]"));
                return;
            }

            int page = 1;
            if (params.length == 1) {
                try {
                    page = Integer.parseInt(params[0]);
                } catch (NumberFormatException ex) {
                    sender.sendMessage(tx.playerText(tx.prefix + "Sorry but &c" + params[0] + "&r is not a number."));
                    return;
                }
            }

            sendHelpPage(sender, page);
        });
        commandManager.register("learning", (sender, params) -> {
            if (!sender.hasPermission("hg.admin.learning")) return;

            if (params.length == 0) {
                sender.sendMessage(tx.playerText("§eLearning mode is currently: " + (learning ? "§aON" : "§cOFF")));
                return;
            }

            boolean enable = params[0].equalsIgnoreCase("on");
            learning = enable;
            getConfig().set("Settings.LearningMode", enable ? "true" : "false");
            saveConfig();
            sender.sendMessage(tx.playerText("§eLearning mode set to: " + (enable ? "§aON" : "§cOFF")));
        });
        commandManager.register("inspect", (sender, params) -> {
            if (!sender.hasPermission("hg.admin.inspect")) {
                sender.sendMessage(tx.playerText("§cYou don't have permission to use this."));
                return;
            }

            if (params.length == 0) {
                sender.sendMessage(tx.playerText("§cUsage: /hg inspect <player>"));
                return;
            }

            Player target = Bukkit.getPlayerExact(params[0]);
            if (target == null) {
                sender.sendMessage(tx.playerText("§cPlayer not found: " + params[0]));
                return;
            }

            AiManager ai = getAiManager();

            // Global training check
            if (!ai.hasAnyTraining() && !isLearning()) {
                sender.sendMessage(tx.playerText(tx.prefix));
                sender.sendMessage(tx.playerText("§cThe AI model does not have enough global training data yet."));
                sender.sendMessage(tx.playerText("§7Run a training phase with §f/hg learning on §7on a trusted server,"));
                sender.sendMessage(tx.playerText("§7then save the model with §f/hg model save §7and load it here."));
                return;
            }

            FeatureCollector fc = getFeatureCollector();
            HGSuspicionManager sm = getSuspicionManager();

            UUID uuid = target.getUniqueId();

            long lastUpdate = sm.getLastUpdate(uuid);

            // If we've never seen this player in the suspicion manager, try to build a fresh sample once
            if (lastUpdate == 0L) {
                double[] features = fc.buildSample(target);
                if (features == null) {
                    sender.sendMessage(tx.playerText(
                            tx.prefix + " §ehas not observed enough behaviour from §f" +
                                    target.getName() + " §eyet to inspect them reliably."
                    ));
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

            // Format "last updated" as Xh Ym Zs
            long diffMs = System.currentTimeMillis() - lastUpdate;
            if (diffMs < 0) diffMs = 0;

            long totalSeconds = diffMs / 1000;
            long hours = totalSeconds / 3600;
            long minutes = (totalSeconds % 3600) / 60;
            long seconds = totalSeconds % 60;

            String formattedTime;
            if (hours > 0) {
                formattedTime = String.format("%dh %dm %ds", hours, minutes, seconds);
            } else if (minutes > 0) {
                formattedTime = String.format("%dm %ds", minutes, seconds);
            } else {
                formattedTime = String.format("%ds", seconds);
            }

            sender.sendMessage(tx.playerText(
                    tx.prefix + " §fInspecting §e" + target.getName()
            ));
            sender.sendMessage(tx.playerText(String.format(
                    " §7Movement suspicion: §f%.1f%%", movement * 100.0
            )));
            sender.sendMessage(tx.playerText(String.format(
                    " §7Combat suspicion:   §f%.1f%%", combat * 100.0
            )));
            sender.sendMessage(tx.playerText(String.format(
                    " §7Overall suspicion:  §f%.1f%%", overall * 100.0
            )));
            sender.sendMessage(tx.playerText(
                    " §7Last updated: §f" + formattedTime + " ago"
            ));
        });
        commandManager.register("stats", (sender, params) -> {
            if (!sender.hasPermission("hg.admin.stats")) {
                sender.sendMessage("§cYou don't have permission to use this.");
                return;
            }

            AiManager ai = getAiManager();
            boolean learning = isLearning();
            double threshold = getSuspicionThreshold();

            sender.sendMessage(tx.playerText(tx.prefix + " Stats:"));
            sender.sendMessage(tx.playerText(" §7Learning mode: " + (learning ? "§aON" : "§cOFF")));
            sender.sendMessage(tx.playerText(" §7Suspicion threshold: §f" + String.format("%.2f", threshold)));

            if (modelFile.exists()) {
                sender.sendMessage(tx.playerText(" §7Model file: §a" + modelFile.getName() +
                        " §7(" + modelFile.length() + " bytes)"));
            } else {
                sender.sendMessage(tx.playerText(" §7Model file: §cnot found"));
            }

            sender.sendMessage(tx.playerText(" §7Training samples seen: §f" + ai.getTotalLearnSamples()));
            sender.sendMessage(tx.playerText(" §7Samples used in training: §f" + ai.getTotalTrainedSamples()));
            sender.sendMessage(tx.playerText(" §7Training runs: §f" + ai.getTotalTrainCalls()));
            sender.sendMessage(tx.playerText(" §7Has any training: " +
                    (ai.hasAnyTraining() ? "§aYES" : "§cNO")));
        });
        commandManager.register("model", (sender, params) -> {
            if (!sender.hasPermission("hg.admin.model")) {
                sender.sendMessage(tx.playerText("§cYou don't have permission to use this."));
                return;
            }

            if (params.length == 0) {
                sender.sendMessage(tx.playerText(tx.shortprefix + "§cUsage: /hg model <save|load>"));
                return;
            }

            String sub = params[0].toLowerCase();
            AiManager ai = getAiManager();

            switch (sub) {
                case "save":
                    ai.trainFromBuffer(); // make sure we include pending samples
                    ai.saveToFile(modelFile);
                    sender.sendMessage(tx.playerText(tx.shortprefix + "§7AI model saved to §c" + modelFile.getName()));
                    break;

                case "load":
                    if (!modelFile.exists()) {
                        sender.sendMessage(tx.playerText(tx.shortprefix + "§cModel file not found: " + modelFile.getName()));
                        return;
                    }
                    ai.loadFromFile(modelFile);
                    sender.sendMessage(tx.playerText(tx.shortprefix + "§7AI model loaded from §c" + modelFile.getName()));
                    break;

                default:
                    sender.sendMessage(tx.playerText(tx.shortprefix + "§cUsage: /hg model <save|load>"));
                    break;
            }
        });
        commandManager.register("label", (sender, params) -> {
            if (!sender.hasPermission("hg.admin.label")) {
                sender.sendMessage(tx.playerText("§cYou don't have permission to use this."));
                return;
            }

            if (params.length < 2) {
                sender.sendMessage(tx.playerText("§cUsage: /hg label <player> <legit|cheat>"));
                return;
            }

            Player target = Bukkit.getPlayerExact(params[0]);
            if (target == null) {
                sender.sendMessage(tx.playerText("§cPlayer not found: " + params[0]));
                return;
            }

            String labelArg = params[1].toLowerCase();
            boolean cheat;
            if (labelArg.equals("legit")) {
                cheat = false;
            } else if (labelArg.equals("cheat") || labelArg.equals("hack")) {
                cheat = true;
            } else {
                sender.sendMessage(tx.playerText("§cUsage: /hg label <player> <legit|cheat>"));
                return;
            }

            FeatureCollector fc = getFeatureCollector();
            AiManager ai = getAiManager();

            double[] features = fc.buildSample(target);
            if (features == null) {
                sender.sendMessage(tx.playerText("§eNot enough data for §f" + target.getName() + "§e yet."));
                return;
            }

            ai.learn(features, cheat ? 1.0 : 0.0);

            sender.sendMessage(tx.playerText("§eLabeled §f" + target.getName() + " §eas " +
                    (cheat ? "§cCHEAT" : "§aLEGIT") + " §efor training."));
        });
        commandManager.register("threshold", (sender, params) -> {
            if (!sender.hasPermission("hg.admin.threshold")) {
                sender.sendMessage(tx.playerText("§cYou don't have permission to use this."));
                return;
            }

            if (params.length == 0) {
                double t = getSuspicionThreshold();
                sender.sendMessage(tx.playerText("§eCurrent suspicion threshold: §f" + String.format("%.2f", t)));
                sender.sendMessage(tx.playerText("§7Example: §f/hg threshold 0.85"));
                return;
            }

            try {
                double value = Double.parseDouble(params[0]);
                if (value < 0.0 || value > 1.0) {
                    sender.sendMessage(tx.playerText("§cValue must be between 0.0 and 1.0"));
                    return;
                }

                setSuspicionThreshold(value);
                sender.sendMessage(tx.playerText("§eSuspicion threshold set to §f" + String.format("%.2f", value)));
            } catch (NumberFormatException e) {
                sender.sendMessage(tx.playerText("§cInvalid number: " + params[0]));
            }
        });
        commandManager.register("output", (sender, params) -> {
            if (!sender.hasPermission("hg.admin.output")) {
                sender.sendMessage(tx.playerText("§cYou don't have permission to use this."));
                return;
            }

            AiManager ai = getAiManager();
            if (ai == null || ai.getNetwork() == null) {
                sender.sendMessage(tx.playerText("§cAI network is not initialized."));
                return;
            }

            File outDir = new File(getDataFolder(), "nn_output");
            File outFile = new File(outDir, "index.html");

            try {
                NetworkHtmlExporter.exportHtml(ai.getNetwork(), outFile);
                sender.sendMessage(tx.playerText("§aNeural network visualization exported to:"));
                sender.sendMessage(tx.playerText("§f" + outFile.getAbsolutePath()));
                sender.sendMessage(tx.playerText("§7Open that index.html in your browser."));
            } catch (Exception e) {
                sender.sendMessage(tx.playerText("§cFailed to export network HTML. Check console for details."));
                e.printStackTrace();
            }
        });
        new ReportCommands(this, reportService).register(commandManager);
        new PunishCommands(this, punishService, announcer).register(commandManager);
        new ReplayCommand(replayManager, tx, replayViewer).register(commandManager);

    }

    public FeatureCollector getFeatureCollector() {
        return featureCollector;
    }

    public AiManager getAiManager() {
        return aiManager;
    }
    public infoManager getInfoManager() {
        return infoManager;
    }
    private void startAiTrainingTask() {
        // every 5 minutes (6000 ticks) as an example
        long period = 20L * 60L * 5L;

        new BukkitRunnable() {
            @Override
            public void run() {
                if (aiManager == null) return;

                // 1) train on any collected samples
                aiManager.trainFromBuffer();

                // 2) save updated model to disk
                aiManager.saveToFile(modelFile);
            }
        }.runTaskTimerAsynchronously(this, period, period);
    }
    public boolean isLearning() {
        return learning;
    }
    public double getSuspicionThreshold() {
        return suspicionThreshold;
    }

    public void setSuspicionThreshold(double suspicionThreshold) {
        this.suspicionThreshold = suspicionThreshold;
    }
    public HGSuspicionManager getSuspicionManager() {
        return suspicionManager;
    }

    public static final class CommandValidate {

        public static boolean notPlayer(CommandSender sender) {
            textHandling tx = new textHandling();
            if (!(sender instanceof Player))
                sender.sendMessage(tx.playerText(tx.prefix + "This command can only be executed by a player."));
            return !(sender instanceof Player);
        }

        private static boolean console(CommandSender sender) {
            textHandling tx = new textHandling();
            if (sender instanceof Player)
                sender.sendMessage(tx.playerText(tx.prefix + "This command can only be executed in console."));
            return (sender instanceof Player);
        }

        public static boolean noPerm(CommandSender sender, String perm1) {
            textHandling tx = new textHandling();
            String permline = "hg." + perm1;
            if (!sender.hasPermission(permline)) {
                sender.sendMessage(tx.playerText(tx.prefix + "Sorry but it seems you are missing the right privileges to run this command!"));
                sender.sendMessage(tx.playerText(tx.shortprefix + "If you believe this is an error please report to an Administrator!"));
                return false;
            }
            return true;
        }
    }
    private static class HelpEntry {
        final String usage;
        final String desc;
        HelpEntry(String usage, String desc) {
            this.usage = usage;
            this.desc = desc;
        }
    }
}
