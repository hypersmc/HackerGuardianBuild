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
import me.hackerguardian.main.modsys.ModFingerprintManager;
import me.hackerguardian.main.replay.ReplayBukkitListener;
import me.hackerguardian.main.replay.ReplayCommand;
import me.hackerguardian.main.replay.ReplayManager;
import me.hackerguardian.main.replay.view.ReplayViewer;
import me.hackerguardian.main.report.ReportCommands;
import me.hackerguardian.main.report.ReportRepository;
import me.hackerguardian.main.report.ReportService;
import me.hackerguardian.main.utils.CommandManager;
import me.hackerguardian.main.utils.Tps;
import me.hackerguardian.main.utils.textHandling;
import me.hackerguardian.main.utils.util;
import me.hackerguardian.main.webserver.WebSQL;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Paper/Spigot entry point for HackerGuardian.
 *
 * Core moderation, reports and replays intentionally do not depend on the
 * optional AI subsystem. Startup is ordered so configuration and the database
 * exist before repositories/listeners/commands are constructed, and shutdown
 * drains component-owned work before closing Hikari.
 */
public class HackerGuardian extends JavaPlugin {

    private static HackerGuardian instance;

    private final util util = new util();
    private final HGSuspicionManager suspicionManager = new HGSuspicionManager();

    private MySQL mysql;
    private CommandManager commandManager;

    private PunishmentRepository punishRepo;
    private PunishmentService punishService;
    private PunishAnnouncer announcer;

    private ReportRepository reportRepository;
    private ReportService reportService;

    private ReplayManager replayManager;
    private ReplayViewer replayViewer;

    private infoManager infoManager;
    private ModFingerprintManager fpManager;
    private HGModFingerprintListener fpListener;
    private HgApiServerBackend api;

    // Current/legacy AI implementation. Kept isolated so it can be replaced.
    private FeatureCollector featureCollector;
    private AiManager aiManager;
    private AIService aiService;
    private WebSQL webSQL;
    private File modelFile;
    private boolean learning;
    private double suspicionThreshold = 0.80;

    @Override
    public void onEnable() {
        instance = this;

        loadConfig();
        learning = getConfig().getBoolean("Settings.LearningMode", false);
        logStartupSummary();

        mysql = new MySQL(this);
        mysql.init();
        if (!isEnabled() || mysql.getDataSource() == null) return;

        if (!initializeCoreServices()) {
            getLogger().severe("HackerGuardian core services failed to initialize. Disabling plugin.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        initializeAiIfEnabled();
        registerListeners();
        registerCommands();
        initializeSecureLink();
        initializeFingerprinting();
        startPunishmentCleanupTask();

        api = new HgApiServerBackend(this);
        api.startIfEnabled();
    }

    private boolean initializeCoreServices() {
        try {
            punishRepo = new PunishmentRepository(mysql.getDataSource());
            punishRepo.ensureTables();
            punishService = new PunishmentService(this, punishRepo);
            announcer = new PunishAnnouncer(this);

            reportRepository = new ReportRepository(mysql.getDataSource());
            reportRepository.ensureTables();
            reportService = new ReportService(this, reportRepository);

            replayManager = new ReplayManager(this, mysql.getDataSource());
            replayViewer = new ReplayViewer(this, replayManager.getStorage());

            infoManager = new infoManager();
            return true;
        } catch (Exception e) {
            getLogger().severe("Failed to initialize HackerGuardian core services: " + e.getMessage());
            if (getConfig().getBoolean("debug")) e.printStackTrace();
            return false;
        }
    }

    private void initializeAiIfEnabled() {
        if (!getConfig().getBoolean("Settings.EnableAI", false)) {
            getLogger().info("HackerGuardian AI is disabled; moderation, reports and replays remain active.");
            return;
        }

        try {
            webSQL = new WebSQL(mysql.getDataSource());
            webSQL.ensureTables();
            aiService = new AIService(this, webSQL);

            featureCollector = new FeatureCollector();
            aiManager = new AiManager(FeatureCollector.FEATURE_COUNT, learning);
            modelFile = new File(getDataFolder(), "ai_model.nnet");
            aiManager.loadFromFile(modelFile);

            AIPermissions.setLearningFilesPermissions(this);
            startAiTrainingTask();
            getLogger().info("HackerGuardian legacy AI subsystem initialized.");
        } catch (Exception e) {
            getLogger().severe("AI initialization failed; continuing with core HackerGuardian features only: "
                    + e.getMessage());
            if (getConfig().getBoolean("debug")) e.printStackTrace();

            featureCollector = null;
            aiManager = null;
            aiService = null;
            webSQL = null;
            modelFile = null;
        }
    }

    private void registerListeners() {
        // Core listeners must never depend on Settings.EnableAI.
        getServer().getPluginManager().registerEvents(new PunishListeners(this, punishRepo), this);
        getServer().getPluginManager().registerEvents(new InventoryClickListener(infoManager), this);
        getServer().getPluginManager().registerEvents(new ReplayBukkitListener(replayManager), this);
        getServer().getPluginManager().registerEvents(replayViewer, this);
        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, new Tps(), 100L, 1L);

        if (aiManager == null || featureCollector == null || aiService == null) return;

        getServer().getPluginManager().registerEvents(new onPlayerJoin(), this);
        getServer().getPluginManager().registerEvents(new HGPlayerInteractListener(), this);
        getServer().getPluginManager().registerEvents(new HGPlayerItemConsumeListener(), this);
        getServer().getPluginManager().registerEvents(new HGPlayerMoveListener(), this);
        getServer().getPluginManager().registerEvents(new HGPlayerToggleFlightListener(), this);
        getServer().getPluginManager().registerEvents(new HGPlayerToggleStateListener(), this);
        getServer().getPluginManager().registerEvents(new HGEntityDamageByEntityListener(aiService), this);
        getServer().getPluginManager().registerEvents(new HGBlockPlaceListener(), this);
        getServer().getPluginManager().registerEvents(new HGBlockBreakListener(), this);
        getServer().getPluginManager().registerEvents(new HGPlayerQuitListener(), this);
        getServer().getPluginManager().registerEvents(new HGPlayerKickListener(), this);

        ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();
        protocolManager.addPacketListener(new me.hackerguardian.main.aicore.aievents.HGPacketListener(this));
    }

    private void registerCommands() {
        textHandling tx = new textHandling();
        commandManager = new CommandManager(this, "HackerGuardian", "HG");

        commandManager.register("", (sender, params) -> {
            if (CommandValidate.notPlayer(sender)) return;
            if (!CommandValidate.noPerm(sender, "main")) return;

            sender.sendMessage(tx.playerText("&8&l<&7&m-------------]&r&l&4Info&7&m[-------------&r&8&l>"));
            sender.sendMessage(tx.playerText(tx.shortprefix + "Hello " + sender.getName()
                    + ". This server is running " + tx.prefix + "version: " + getDescription().getVersion()));
            sender.sendMessage(tx.playerText(tx.shortprefix + "My maker(s) are '" + ChatColor.RED
                    + getDescription().getAuthors().toString().replace("[", "").replace("]", "")
                    + ChatColor.RESET + "'"));
        });

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
                } catch (NumberFormatException e) {
                    sender.sendMessage(tx.playerText(tx.prefix + "Sorry but &c" + params[0] + "&r is not a number."));
                    return;
                }
            }
            sendHelpPage(sender, page);
        });

        // Development player-info command retained from the current build.
        commandManager.register("test", (sender, params) -> {
            if (CommandValidate.notPlayer(sender)) return;
            if (params.length != 1) {
                sender.sendMessage(tx.playerText(tx.prefix + "Usage: /hg test <player>"));
                return;
            }

            Player staff = (Player) sender;
            Player target = Bukkit.getPlayerExact(params[0]);
            if (target == null) {
                sender.sendMessage(tx.playerText("§cPlayer not found: " + params[0]));
                return;
            }

            infoManager.resetInfo(staff, target.getName());
            info infoInstance = infoManager.getInfo(staff, target.getName());
            infoInstance.open(staff);
        });

        if (aiManager != null) {
            new LegacyAiCommands(this).register(commandManager);
        }

        new ReportCommands(this, reportService).register(commandManager);
        new PunishCommands(this, punishService, announcer).register(commandManager);
        new ReplayCommand(replayManager, tx, replayViewer).register(commandManager);
    }

    private void initializeSecureLink() {
        if (!getConfig().getBoolean("Settings.hg_secure_link", false)) return;

        String channel = "hg:playerchannel";
        LinkVerifier verifier = new LinkVerifier(this, new LinkErrorHandler(this));
        getServer().getMessenger().registerIncomingPluginChannel(this, channel, verifier);
        getServer().getMessenger().registerOutgoingPluginChannel(this, channel);
        getServer().getPluginManager().registerEvents(verifier, this);
        getLogger().info("HG Link backend enabled.");
    }

    private void initializeFingerprinting() {
        try {
            fpManager = new ModFingerprintManager();
            fpListener = new HGModFingerprintListener(this, fpManager);
            fpListener.enable();
        } catch (Exception e) {
            getLogger().warning("Failed to initialize mod fingerprinting: " + e.getMessage());
            if (getConfig().getBoolean("debug")) e.printStackTrace();
            fpListener = null;
        }
    }

    private void startPunishmentCleanupTask() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            try {
                int cleaned = punishRepo.cleanupExpired(System.currentTimeMillis());
                if (cleaned > 0 && getConfig().getBoolean("debug")) {
                    getLogger().info("[HG] Cleaned " + cleaned + " expired punishments.");
                }
            } catch (Exception e) {
                getLogger().warning("[HG] Failed to clean expired punishments: " + e.getMessage());
                if (getConfig().getBoolean("debug")) e.printStackTrace();
            }
        }, 20L * 60L, 20L * 600L);
    }

    private void startAiTrainingTask() {
        long period = 20L * 60L * 5L;
        new BukkitRunnable() {
            @Override
            public void run() {
                if (aiManager == null || modelFile == null) return;
                aiManager.trainFromBuffer();
                aiManager.saveToFile(modelFile);
            }
        }.runTaskTimerAsynchronously(this, period, period);
    }

    private void logStartupSummary() {
        getServer().getConsoleSender().sendMessage(
                "\n" + ChatColor.DARK_GRAY + "[]=====[" + ChatColor.GRAY + "Enabling "
                        + getDescription().getName() + ChatColor.DARK_GRAY + "]=====[]" + ChatColor.RESET + "\n"
                        + ChatColor.DARK_GRAY + "| " + ChatColor.RED + "Version: " + ChatColor.GRAY
                        + "v" + getDescription().getVersion() + ChatColor.RESET + "\n"
                        + ChatColor.DARK_GRAY + "| " + ChatColor.RED + "Dependencies:" + ChatColor.RESET + "\n"
                        + util.detectPluginProtocollib()
                        + util.detectPluginSkulls()
                        + ChatColor.DARK_GRAY + "| " + ChatColor.RED + "Soft Dependencies:" + ChatColor.RESET + "\n"
                        + util.detectPluginProtocolsupport()
                        + util.detectPluginViaversion()
                        + ChatColor.DARK_GRAY + "| " + ChatColor.RED + "Features:" + ChatColor.RESET + "\n"
                        + util.detectSettingAI(this)
                        + util.detectSettingWebsite(this)
                        + util.detectSettingsSecureLink(this)
                        + ChatColor.DARK_GRAY + "[]=================================[]" + ChatColor.RESET + "\n"
        );
    }

    private List<HelpEntry> helpEntries() {
        List<HelpEntry> entries = new ArrayList<>();
        entries.add(new HelpEntry("/hg help", "Show this help"));
        entries.add(new HelpEntry("/hg report <player> <reason>", "Report a player"));
        entries.add(new HelpEntry("/hg reports", "Open staff reports"));
        entries.add(new HelpEntry("/hg replay <start|stop|view|info|exit>", "Replay controls"));
        entries.add(new HelpEntry("/hg ban <player> ...", "Ban a player"));
        entries.add(new HelpEntry("/hg unban <player>", "Unban a player"));
        entries.add(new HelpEntry("/hg mute <player> ...", "Mute a player"));
        entries.add(new HelpEntry("/hg unmute <player>", "Unmute a player"));
        entries.add(new HelpEntry("/hg kick <player> <reason>", "Kick a player"));
        entries.add(new HelpEntry("/hg banip <player|ip> ...", "Ban an IP"));
        entries.add(new HelpEntry("/hg unbanip <ip>", "Unban an IP"));

        if (aiManager != null) {
            entries.add(new HelpEntry("/hg stats", "Show legacy AI status"));
            entries.add(new HelpEntry("/hg learning <on|off>", "Toggle legacy AI learning mode"));
            entries.add(new HelpEntry("/hg model <save|load>", "Save/load the legacy AI model"));
            entries.add(new HelpEntry("/hg inspect <player>", "Inspect AI suspicion"));
            entries.add(new HelpEntry("/hg label <player> <legit|cheat>", "Add a training label"));
            entries.add(new HelpEntry("/hg threshold [value]", "Get/set AI suspicion threshold"));
        }
        return entries;
    }

    private void sendHelpPage(CommandSender sender, int page) {
        textHandling tx = new textHandling();
        List<HelpEntry> entries = helpEntries();
        int pageSize = 7;
        int totalPages = Math.max(1, (int) Math.ceil(entries.size() / (double) pageSize));

        if (page < 1 || page > totalPages) {
            sender.sendMessage(tx.playerText(tx.prefix + "Sorry but &c" + page
                    + "&r is not a valid page. (1-" + totalPages + ")"));
            return;
        }

        sender.sendMessage(tx.playerText("&8&l<&7&m-------------]&r&l&4Help&7&m[-------------&r&8&l>"));
        int from = (page - 1) * pageSize;
        int to = Math.min(from + pageSize, entries.size());
        for (int i = from; i < to; i++) {
            HelpEntry entry = entries.get(i);
            sender.sendMessage(tx.playerText(entry.usage + " §7- " + entry.description));
        }
        sender.sendMessage(tx.playerText("&8&l<&7&m---------]&r&l&4Menu " + page + "/" + totalPages
                + "&7&m[---------&r&8&l>"));
    }

    public void loadConfig() {
        saveDefaultConfig();
        reloadConfig();
    }

    @Override
    public void onDisable() {
        if (api != null) {
            try { api.stop(); }
            catch (Exception e) { getLogger().warning("Failed to stop HG API: " + e.getMessage()); }
        }

        // Stop Bukkit-owned producers before draining component-owned I/O.
        Bukkit.getScheduler().cancelTasks(this);

        if (replayViewer != null) {
            try { replayViewer.shutdown(); }
            catch (Exception e) { getLogger().warning("Failed to stop replay viewers: " + e.getMessage()); }
        }
        if (replayManager != null) {
            try { replayManager.shutdown(); }
            catch (Exception e) { getLogger().warning("Failed to stop replay manager: " + e.getMessage()); }
        }

        if (fpListener != null) {
            try { fpListener.disable(); }
            catch (Exception e) { getLogger().warning("Failed to stop mod fingerprint listener: " + e.getMessage()); }
        }

        if (aiManager != null && modelFile != null) {
            try {
                aiManager.trainFromBuffer();
                aiManager.saveToFile(modelFile);
            } catch (Exception e) {
                getLogger().warning("Failed to persist AI model during shutdown: " + e.getMessage());
            }
        }

        if (mysql != null) mysql.shutdown();
        instance = null;
    }

    public static HackerGuardian getInstance() {
        return instance;
    }

    public MySQL getMySQL() {
        return mysql;
    }

    public FeatureCollector getFeatureCollector() {
        return featureCollector;
    }

    public AiManager getAiManager() {
        return aiManager;
    }

    public AIService getAiService() {
        return aiService;
    }

    public File getAiModelFile() {
        return modelFile;
    }

    public infoManager getInfoManager() {
        return infoManager;
    }

    public boolean isLearning() {
        return learning;
    }

    public void setLearning(boolean learning) {
        this.learning = learning;
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
        private CommandValidate() {}

        public static boolean notPlayer(CommandSender sender) {
            if (sender instanceof Player) return false;
            textHandling tx = new textHandling();
            sender.sendMessage(tx.playerText(tx.prefix + "This command can only be executed by a player."));
            return true;
        }

        public static boolean noPerm(CommandSender sender, String permissionSuffix) {
            String permission = "hg." + permissionSuffix;
            if (sender.hasPermission(permission)) return true;

            textHandling tx = new textHandling();
            sender.sendMessage(tx.playerText(tx.prefix
                    + "Sorry but it seems you are missing the right privileges to run this command!"));
            sender.sendMessage(tx.playerText(tx.shortprefix
                    + "If you believe this is an error please report to an Administrator!"));
            return false;
        }
    }

    private static final class HelpEntry {
        final String usage;
        final String description;

        HelpEntry(String usage, String description) {
            this.usage = usage;
            this.description = description;
        }
    }
}
