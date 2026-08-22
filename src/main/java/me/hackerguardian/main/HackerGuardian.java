package me.hackerguardian.main;

import me.hackerguardian.Util.LinkErrorHandler;
import me.hackerguardian.api.HgApiServerBackend;
import me.hackerguardian.main.config.ConfigManager;
import me.hackerguardian.main.detection.DetectionCommands;
import me.hackerguardian.main.detection.DetectionRuntime;
import me.hackerguardian.main.hglink.LinkVerifier;
import me.hackerguardian.main.inv.InventoryClickListener;
import me.hackerguardian.main.inv.info;
import me.hackerguardian.main.inv.infoManager;
import me.hackerguardian.main.moderation.punish.PunishAnnouncer;
import me.hackerguardian.main.moderation.punish.PunishCommands;
import me.hackerguardian.main.moderation.punish.PunishListeners;
import me.hackerguardian.main.moderation.punish.PunishmentRepository;
import me.hackerguardian.main.moderation.punish.PunishmentService;
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
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/** Paper/Spigot entry point for HackerGuardian. */
public class HackerGuardian extends JavaPlugin {

    private static HackerGuardian instance;

    private final util util = new util();

    private ConfigManager configManager;
    private DatabaseManager database;
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

    // Evidence-first detection pipeline. ML models plug into this runtime.
    private DetectionRuntime detectionRuntime;

    @Override
    public void onEnable() {
        instance = this;

        loadConfig();
        logStartupSummary();

        database = new DatabaseManager(this);
        if (!database.init() || !isEnabled() || database.getDataSource() == null) return;

        if (!initializeCoreServices()) {
            getLogger().severe("HackerGuardian core services failed to initialize. Disabling plugin.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        registerListeners();
        initializeDetectionV2();
        registerCommands();
        initializeSecureLink();
        initializeFingerprinting();
        startPunishmentCleanupTask();

        api = new HgApiServerBackend(this);
        api.startIfEnabled();
    }

    private boolean initializeCoreServices() {
        try {
            punishRepo = new PunishmentRepository(database.getDataSource());
            punishRepo.ensureTables();
            punishService = new PunishmentService(this, punishRepo);
            announcer = new PunishAnnouncer(this);

            reportRepository = new ReportRepository(database.getDataSource());
            reportRepository.ensureTables();
            reportService = new ReportService(this, reportRepository);

            replayManager = new ReplayManager(this, database.getDataSource());
            replayViewer = new ReplayViewer(this, replayManager.getStorage());

            infoManager = new infoManager();
            return true;
        } catch (Exception e) {
            getLogger().severe("Failed to initialize HackerGuardian core services: " + e.getMessage());
            if (getConfig().getBoolean("debug")) e.printStackTrace();
            return false;
        }
    }

    private void initializeDetectionV2() {
        if (!getConfig().getBoolean("DetectionV2.enabled", true)) {
            getLogger().info("Detection v2 is disabled.");
            return;
        }

        try {
            detectionRuntime = new DetectionRuntime(this);
            detectionRuntime.start();
        } catch (Exception e) {
            detectionRuntime = null;
            getLogger().severe("Detection v2 failed to initialize; core moderation remains active: "
                    + e.getMessage());
            if (getConfig().getBoolean("debug")) e.printStackTrace();
        }
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new PunishListeners(this, punishRepo), this);
        getServer().getPluginManager().registerEvents(new InventoryClickListener(infoManager), this);
        getServer().getPluginManager().registerEvents(new ReplayBukkitListener(replayManager), this);
        getServer().getPluginManager().registerEvents(replayViewer, this);
        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, new Tps(), 100L, 1L);
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

        if (detectionRuntime != null) {
            new DetectionCommands(detectionRuntime).register(commandManager);
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

    private void logStartupSummary() {
        String detection = getConfig().getBoolean("DetectionV2.enabled", true)
                ? ChatColor.DARK_GRAY + "|      " + ChatColor.GREEN + "Detection v2: enabled\n"
                : ChatColor.DARK_GRAY + "|      " + ChatColor.RED + "Detection v2: disabled\n";

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
                        + detection
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

        if (detectionRuntime != null) {
            entries.add(new HelpEntry("/hg detection [player]", "Inspect detection evidence"));
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
        configManager = new ConfigManager(this);
        configManager.load();
    }

    @Override
    public void onDisable() {
        if (api != null) {
            try { api.stop(); }
            catch (Exception e) { getLogger().warning("Failed to stop HG API: " + e.getMessage()); }
        }

        if (detectionRuntime != null) {
            try { detectionRuntime.stop(); }
            catch (Exception e) { getLogger().warning("Failed to stop Detection v2: " + e.getMessage()); }
        }

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

        if (database != null) database.shutdown();
        instance = null;
    }

    public static HackerGuardian getInstance() {
        return instance;
    }

    public DatabaseManager getDatabase() {
        return database;
    }

    public DetectionRuntime getDetectionRuntime() {
        return detectionRuntime;
    }

    public infoManager getInfoManager() {
        return infoManager;
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
