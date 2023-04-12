package me.hackerguardian.main;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import me.hackerguardian.main.Recording.API.ReplayAPI;
import me.hackerguardian.main.Recording.Replay;
import me.hackerguardian.main.Recording.data.ReplayInfo;
import me.hackerguardian.main.Recording.filesys.ConfigManager;
import me.hackerguardian.main.Recording.filesys.saving.DatabaseReplaySaver;
import me.hackerguardian.main.Recording.filesys.saving.DefaultReplaySaver;
import me.hackerguardian.main.Recording.filesys.saving.IReplaySaver;
import me.hackerguardian.main.Recording.filesys.saving.ReplaySaver;
import me.hackerguardian.main.Recording.recording.optimization.ReplayOptimizer;
import me.hackerguardian.main.Recording.recording.optimization.ReplayQuality;
import me.hackerguardian.main.Recording.recording.optimization.ReplayStats;
import me.hackerguardian.main.Recording.replaying.ReplayHelper;
import me.hackerguardian.main.Recording.replaying.Replayer;
import me.hackerguardian.main.Recording.utils.ReplayCleanup;
import me.hackerguardian.main.aicore.DataIO;
import me.hackerguardian.main.aicore.HackerguardianAI;
import me.hackerguardian.main.aicore.NeuralNetworkVisualizer;
import me.hackerguardian.main.aicore.TrainingData;
import me.hackerguardian.main.events.*;
import me.hackerguardian.main.utils.*;
import me.hackerguardian.main.utils.recordinghandler.LogUtils;
import me.hackerguardian.main.utils.recordinghandler.MathUtils;
import me.hackerguardian.main.utils.recordinghandler.ReplayManager;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.validator.routines.InetAddressValidator;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;


/**
 * @author JumpWatch on 27-03-2023
 * @Project HackerGuardian
 * v1.0.0
 */
public class hackerguardian extends JavaPlugin {
    ConcurrentHashMap<UUID, Integer> average;
    ConcurrentHashMap<UUID, Integer> current;
    private final Map<InetSocketAddress, Integer> playerVersions = new ConcurrentHashMap<InetSocketAddress, Integer>();
    public String prefix = "&4&l[&r&l&8HackerGuardian&r&4&l]&r ";
    public String shortprefix = "&4&l[&r&l&8HG&r&4&l]&r ";
    private static hackerguardian instance;
    private CommandManager commandManager;
    public HackerguardianAI ai;
    private util util;
    /**
     *
     * @param text
     * @return will return the text argument with colors if (&) and color codes are present.
     */
    public String playertext(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
    @Override
    public void onEnable() {
        instance = this;
        Logger logger = this.getLogger();

        //File & folder management
        if (new File("plugins/HackerGuardian/output/").exists()){
            logger.info("output folder exists!");
        }else if (!new File(getDataFolder() + "/output/", "dummyfile.txt").exists()){
            saveResource("output/dummyfile.txt", false);
            File removefile = new File(getDataFolder() + "/output/dummyfile.txt");
            try {
                removefile.delete();
            } catch (Exception ignored) {}
        }
        if (new File("plugins/HackerGuardian/datacore/").exists()){
            logger.info("datacore folder exists!");
        }else if (!new File(getDataFolder() + "/datacore/", "dummyfile.txt").exists()){
            saveResource("datacore/dummyfile.txt", false);
            File removefile = new File(getDataFolder() + "/datacore/dummyfile.txt");
            try {
                removefile.delete();
            } catch (Exception ignored) {}
        }
        try {
            FileUtil.saveResourceIfAbsent(this, "config.yml", "config.yml");
        } catch (IOException ignored) {}
        ConfigManager.loadConfigs();
        ReplayManager.register();
        ReplaySaver.register(ConfigManager.USE_DATABASE ? new DatabaseReplaySaver() : new DatabaseReplaySaver());
        if (ConfigManager.CLEANUP_REPLAYS > 0) {
            ReplayCleanup.cleanupReplays();
        }
        MySQL sql = new MySQL();
        sql.setupCoreSystem();
        if (getServer().getPluginManager().isPluginEnabled("ProtocolLib")) {
            ProtocolManager manager = ProtocolLibrary.getProtocolManager();
            manager.addPacketListener(new PacketAdapter(this,
                    ListenerPriority.HIGH,
                    PacketType.Handshake.Client.SET_PROTOCOL, PacketType.Login.Server.DISCONNECT) {

                @Override
                public void onPacketReceiving(final PacketEvent event) {
                    final PacketContainer packet = event.getPacket();

                    if (event.getPacketType() == PacketType.Handshake.Client.SET_PROTOCOL) {
                        if (packet.getProtocols().read(0) == PacketType.Protocol.LOGIN) {
                            playerVersions.put(event.getPlayer().getAddress(), packet.getIntegers().read(0));
                        }
                    } else {
                        playerVersions.remove(event.getPlayer().getAddress());
                    }
                }
            });
        }
        //General AI data loading
        File dir = new File(getDataFolder() + "/datacore/");
        if (dir.exists() && dir.isDirectory()){
            File[] files = dir.listFiles();
            if (files.length > 0 ){
                for (File file : files){
                    String fileName = file.getName();
                    String fileNameWithoutExtension = fileName;
                    int lastDotIndex = fileName.lastIndexOf(".");
                    if (lastDotIndex > 0) {
                        fileNameWithoutExtension = fileName.substring(0, lastDotIndex);
                    }
                    File fileToLoad = new File(dir.getPath() + "/" + fileNameWithoutExtension + ".bin");
                    if (fileToLoad.exists()) {
                        TrainingData dataSet = DataIO.loadTrainingData(fileToLoad.getName());
                        logger.info(file.getAbsolutePath());
                        try {
                            Class<?> clazz = Class.forName(file.getName());
                            Object ins = clazz.newInstance();
                            clazz.getMethod("setTrainingData", TrainingData.class, String.class).invoke(ins, dataSet, fileNameWithoutExtension);
                        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException |
                                 InvocationTargetException | NoSuchMethodException ignored) {}
                        logger.info("Training data for " + fileName + " have been added!");
                    } else {
                        logger.info("File " + fileToLoad.getName() + " does not exist!");
                    }
                }
            } else {
                logger.info("Data-core folder empty! No data added to AI!");
            }
        } else {
            logger.info("Data-core directory does not exist!");
        }



        Bukkit.getServer().getScheduler().scheduleSyncRepeatingTask(this, new Tps(), 100L, 1L);
        commandManager = new CommandManager(this, "hackerguardian");
        ai = new HackerguardianAI(5,1,10);
        getServer().getPluginManager().registerEvents(new onPlayerChat(), this);
        getServer().getPluginManager().registerEvents(new onPlayerInteract(), this);
        getServer().getPluginManager().registerEvents(new onPlayerItemConsume(), this);
        getServer().getPluginManager().registerEvents(new onPlayerMove(), this);
        getServer().getPluginManager().registerEvents(new onPlayerToggleFlight(), this);
        getServer().getPluginManager().registerEvents(new onPlayerToggleSneak(), this);
        registerCommand();

    }

    @Override
    public void onDisable() {
        TrainingData onplayerChat = onPlayerChat.getTrainingData();
        DataIO.saveTrainingData(onplayerChat.getDataSet(), getDataFolder() + "/datacore/" + "onPlayerChat.bin");

        TrainingData onplayerInteract = onPlayerInteract.getTrainingData();
        DataIO.saveTrainingData(onplayerInteract.getDataSet(), getDataFolder() + "/datacore/" + "onPlayerInteract.bin");

        TrainingData onplayerItemConsume = onPlayerItemConsume.getTrainingData();
        DataIO.saveTrainingData(onplayerItemConsume.getDataSet(), getDataFolder() + "/datacore/" + "onPlayerItemConsume.bin");

        TrainingData onplayerMove = onPlayerMove.getTrainingData();
        DataIO.saveTrainingData(onplayerMove.getDataSet(),  getDataFolder() + "/datacore/" + "onPlayerMove.bin");

        TrainingData onplayerToggleFlight = onPlayerToggleFlight.getTrainingData();
        DataIO.saveTrainingData(onplayerToggleFlight.getDataSet(), getDataFolder() + "/datacore/" + "onPlayerToggleFlight.bin");

        TrainingData onplayerToggleSneak = onPlayerToggleSneak.getTrainingData();
        DataIO.saveTrainingData(onplayerToggleSneak.getDataSet(), getDataFolder() + "/datacore/" + "onPlayerToggleSneak.bin");
    }
    private void registerCommand() {
        commandManager.register("", (sender, params) -> {
            if (CommandValidate.notPlayer(sender)) return;
            if (!CommandValidate.noPerm(sender, "main")) return;

            sender.sendMessage(playertext("&8&l<&7&m-------------]&r&l&4Help&7&m[-------------&r&8&l>"));
            sender.sendMessage(playertext(shortprefix + "Hello " + sender.getName() + ". This server is running " + prefix + "version: " + this.getDescription().getVersion()));
            sender.sendMessage(playertext(shortprefix + "My maker(s) are '" + ChatColor.RED + this.getDescription().getAuthors().toString().replace("[", "").replace("]", "") + ChatColor.RESET + "'"));
            return;

        });

        commandManager.register("help", (sender, params) -> {
            if (!CommandValidate.noPerm(sender, "help")) return;

            if (params.length != 1) {
                sender.sendMessage(playertext("&8&l<&7&m-------------]&r&l&4Help&7&m[-------------&r&8&l>"));
                sender.sendMessage(playertext(prefix + "Command list:"));
                sender.sendMessage(playertext(shortprefix + "/hg" + ChatColor.GRAY + " This is the main command of HackerGuardian."));
//                sender.sendMessage(playertext(shortprefix + "/hg mob" + ChatColor.GRAY + " Spawns a Zombie to train the Neural Network"));
                sender.sendMessage(playertext(shortprefix + "/hg ban" + ChatColor.GRAY + " Ban x Player with reason."));
                sender.sendMessage(playertext(shortprefix + "/hg view" + ChatColor.GRAY + " Checks every statistic HG have on x player."));
                sender.sendMessage(playertext(shortprefix + "/hg reload" + ChatColor.GRAY + " Reloads the plugin"));
                sender.sendMessage(playertext(shortprefix + "/hg mute" + ChatColor.GRAY + " Mute x player with reason."));
                sender.sendMessage(playertext(shortprefix + "/hg unmute" + ChatColor.GRAY + " Unmute x player."));
                sender.sendMessage(playertext("&8&l<&7&m---------]&r&l&4Menu 1/4&7&m[---------&r&8&l>"));
                return;
            }
            if (params.length == 1) {
                if (!StringUtils.isNumeric(params[0])) {
                    sender.sendMessage(playertext(prefix + "Sorry but " + params[0] + " is not a number."));
                    return;
                }
                int helpnumber = Integer.valueOf(params[0]);
                if (helpnumber == 1) {
                    sender.sendMessage(playertext("&8&l<&7&m-------------]&r&l&4Help&7&m[-------------&r&8&l>"));
                    sender.sendMessage(playertext(prefix + "Command list:"));
                    sender.sendMessage(playertext(shortprefix + "/hg" + ChatColor.GRAY + " This is the main command of HackerGuardian."));
//                    sender.sendMessage(playertext(shortprefix + "/hg mob" + ChatColor.GRAY + " Spawns a Zombie to train the Neural Network."));
                    sender.sendMessage(playertext(shortprefix + "/hg ban" + ChatColor.GRAY + " Ban x Player with reason."));
                    sender.sendMessage(playertext(shortprefix + "/hg view" + ChatColor.GRAY + " Checks every statistic HG have on x player."));
                    sender.sendMessage(playertext(shortprefix + "/hg reload" + ChatColor.GRAY + " Reloads the plugin."));
                    sender.sendMessage(playertext(shortprefix + "/hg mute" + ChatColor.GRAY + " Mute x player with reason."));
                    sender.sendMessage(playertext(shortprefix + "/hg unmute" + ChatColor.GRAY + " Unmute x player."));
                    sender.sendMessage(playertext("&8&l<&7&m---------]&r&l&4Menu 1/4&7&m[---------&r&8&l>"));
                    return;
                } else if (helpnumber == 2) {
                    sender.sendMessage(playertext("&8&l<&7&m-------------]&r&l&4Help&7&m[-------------&r&8&l>"));
                    sender.sendMessage(playertext(prefix + "Command list:"));
                    sender.sendMessage(playertext(shortprefix + "/hg info" + ChatColor.GRAY + " Statistics of the Neural Network."));
//                    sender.sendMessage(playertext(shortprefix + "/hg train" + ChatColor.GRAY + " Train the network."));
//                    sender.sendMessage(playertext(shortprefix + "/hg rebuild" + ChatColor.GRAY + " Rebuild the dataset that currently exist."));
                    sender.sendMessage(playertext(shortprefix + "/hg set" + ChatColor.GRAY + " Only use this command if database check fails."));
                    sender.sendMessage(playertext(shortprefix + "/hg nnpng" + ChatColor.GRAY + " Prints the Neural Network to the console."));
//                    sender.sendMessage(playertext(shortprefix + "/hg test" + ChatColor.GRAY + " Test x player of pvp hacks."));
//                    sender.sendMessage(playertext(shortprefix + "/hg start" + ChatColor.GRAY + " Starts logging angles of x player."));
                    sender.sendMessage(playertext("&8&l<&7&m---------]&r&l&4Menu 2/4&7&m[---------&r&8&l>"));
                    return;
                } else if (helpnumber == 3) {
                    sender.sendMessage(playertext("&8&l<&7&m-------------]&r&l&4Help&7&m[-------------&r&8&l>"));
                    sender.sendMessage(playertext(prefix + "Command list:"));
//                    sender.sendMessage(playertext(shortprefix + "/hg stop" + ChatColor.GRAY + " Stops logging of x player."));
                    sender.sendMessage(playertext(shortprefix + "/hg resetdb" + ChatColor.GRAY + " resets the db. (Only works in console)"));
                    sender.sendMessage(playertext(shortprefix + "/hg kick" + ChatColor.GRAY + " kick x Player with reason."));
                    sender.sendMessage(playertext(shortprefix + "/hg tps" + ChatColor.GRAY + " Returns servers current TPS"));
                    sender.sendMessage(playertext(shortprefix + "/hg checkbannedip" + ChatColor.GRAY + " Check banned ip and how many players was on x ip."));
                    sender.sendMessage(playertext(shortprefix + "/hg unban" + ChatColor.GRAY + " unban x player."));
                    //sender.sendMessage(playertext(shortprefix + "/hg unmute" + ChatColor.GRAY + " Unmute x player."));
                    sender.sendMessage(playertext("&8&l<&7&m---------]&r&l&4Menu 3/4&7&m[---------&r&8&l>"));
                    return;
                } else if (helpnumber == 4) {
                    sender.sendMessage(playertext("&8&l<&7&m-------------]&r&l&4Help&7&m[-------------&r&8&l>"));
                    sender.sendMessage(playertext(prefix + "Command list:"));
                    //sender.sendMessage(playertext(shortprefix + "/hg" + ChatColor.GRAY + " This is the main command of HackerGuardian."));
                    //sender.sendMessage(playertext(shortprefix + "/hg mob" + ChatColor.GRAY + " Spawns a Zombie to train the Neural Network"));
                    //sender.sendMessage(playertext(shortprefix + "/hg ban" + ChatColor.GRAY + " Ban x Player with reason."));
                    //sender.sendMessage(playertext(shortprefix + "/hg view" + ChatColor.GRAY + " Checks every statistic HG have on x player."));
                    //sender.sendMessage(playertext(shortprefix + "/hg reload" + ChatColor.GRAY + " Reloads the plugin"));
                    //sender.sendMessage(playertext(shortprefix + "/hg mute" + ChatColor.GRAY + " Mute x player with reason."));
                    //sender.sendMessage(playertext(shortprefix + "/hg unmute" + ChatColor.GRAY + " Unmute x player."));
                    sender.sendMessage(playertext("&8&l<&7&m---------]&r&l&4Menu 4/4&7&m[---------&r&8&l>"));
                    return;
                } else {
                    sender.sendMessage(playertext(prefix + "Sorry but " + params[0].toString() + " is not a valid number."));
                    return;
                }
            }

        });

        //Recording\\
        commandManager.register("replayinfo", ((sender, params) -> {
            if (CommandValidate.notPlayer(sender)) return;
            if (!CommandValidate.noPerm(sender, "replayinfo")) return;
            if (params.length != 1){
                sender.sendMessage(playertext(prefix + "Wrong parameters! /hg replayinfo <name>"));
                return;
            }
            String name = params[0];
            if (ReplaySaver.exists(name)) {
                sender.sendMessage(playertext(prefix + "Loading replay '" + ChatColor.RED + name + ChatColor.RESET + "'"));
                ReplaySaver.load(name, replay -> {
                    ReplayInfo info = replay.getReplayInfo() != null ? replay.getReplayInfo() : new ReplayInfo(replay.getId(), replay.getData().getCreator(), null, replay.getData().getDuration());
                    ReplayStats stats = ReplayOptimizer.analyzeReplay(replay);
                    sender.sendMessage(playertext(prefix + "Information about '" + ChatColor.RED + replay.getId() + ChatColor.RESET + "'" ));
                    if (info.getCreator() != null) {
                        sender.sendMessage(playertext(shortprefix + "Created by '" + ChatColor.RED + info.getCreator() + ChatColor.RESET + "'"));
                    }
                    sender.sendMessage(playertext(shortprefix + "Duration '" + ChatColor.RED + info.getDuration() + ChatColor.RESET + "'"));
                    sender.sendMessage(playertext(shortprefix + "Players '" + ChatColor.RED + stats.getPlayers().stream().collect(Collectors.joining(ChatColor.RESET + "," + ChatColor.RED +" ")) + ChatColor.RESET + "'"));
                    sender.sendMessage(playertext(shortprefix + "Quality '" + ChatColor.RED + (replay.getData().getQuality() != null ? replay.getData().getQuality().getQualityName() : ReplayQuality.HIGH.getQualityName()) + ChatColor.RESET + "'"));
                    if (sender instanceof Player) {
                        ((Player) sender).spigot().sendMessage(buildMessage(stats));
                    }
                    if (stats.getEntityCount() > 0){
                        sender.sendMessage(playertext(shortprefix + "Entities '" + ChatColor.RED + stats.getEntityCount() + ChatColor.RESET + "'"));
                    }
                });
            }else {
                sender.sendMessage(playertext(shortprefix + "Replay '" + ChatColor.RED + name + ChatColor.RESET + "' does not exist."));
            }
        }));
        commandManager.register("replaylist", ((sender, params) -> {
            if (CommandValidate.notPlayer(sender)) return;
            if (!CommandValidate.noPerm(sender, "replaylist")) return;
            if (params.length != 1) {
                sender.sendMessage(playertext(prefix + "Wrong parameters! /hg replaylist <page>"));
                return;
            }
            if (ReplaySaver.getReplays().size() > 0 ){
                int page = 1;
                SimpleDateFormat format = new SimpleDateFormat("dd-MM-yyyy hh:mm:ss");
                if (params.length == 1 && MathUtils.isInt(params[0])) page = Integer.valueOf(params[0]);
                List<String> replays = ReplaySaver.getReplays();
                replays.sort(dateComparator());
                CommandPagination<String> pagination = new CommandPagination<>(replays, 9);
                sender.sendMessage(playertext(shortprefix + "Available replays: '" + ChatColor.RED + ReplaySaver.getReplays().size() + ChatColor.RESET + "' | Page '" + ChatColor.RED + page + ChatColor.RESET + "' " + pagination.getPages()));
                pagination.printPage(page, new IPaginationExecutor<String>() {
                    @Override
                    public void print(String element) {
                        String message = String.format(" §6§o%s    §e%s", (getCreationDate(element) != null ? format.format(getCreationDate(element)) : ""), element);
                        if (sender instanceof Player) {
                            BaseComponent[] comps;
                            if (DatabaseReplaySaver.getInfo(element) != null && DatabaseReplaySaver.getInfo(element).getCreator() != null) {
                                ReplayInfo info = DatabaseReplaySaver.getInfo(element);
                                comps = new ComponentBuilder(message)
                                        .event(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder("Replay " + info.getID() + "\n\nCreated By '" + ChatColor.RED + info.getCreator() + ChatColor.RESET + "\nDuration '" + ChatColor.RED + (info.getDuration() / 20 ) + ChatColor.RESET + "sec" + "\n\nClick to play!").create()))
                                        .event(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/hg replayplay " + info.getID()))
                                        .create();
                            }else {
                                comps = new ComponentBuilder(message)
                                        .event(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder("Replay '" + ChatColor.RED + element + ChatColor.RESET + "'\n\nClick to play!").create()))
                                        .event(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/hg replayplay " + element))
                                        .create();
                            }
                            ((Player) sender).spigot().sendMessage(comps);
                        }
                        else {
                            sender.sendMessage(message);
                        }
                    }
                });
            }else {
                sender.sendMessage(playertext(shortprefix + "No replays exist!"));
            }
        }));
        commandManager.register("replayjump", ((sender, params) -> {
            if (CommandValidate.notPlayer(sender)) return;
            if (!CommandValidate.noPerm(sender, "replayjump")){
                sender.sendMessage(playertext(prefix + "Wrong parameters! /hg replayjump <Time in seconds>"));
                return;
            }
            if (params.length != 1) return;
            Player p = (Player) sender;
            if (ReplayHelper.replaySessions.containsKey(p.getName())) {
                Replayer replayer = ReplayHelper.replaySessions.get(p.getName());
                int duration = replayer.getReplay().getData().getDuration() / 20;
                if (MathUtils.isInt(params[0]) && Integer.valueOf(params[0]) > 0 && Integer.valueOf(params[0]) < duration) {
                    int seconds = Integer.valueOf(params[0]);
                    p.sendMessage(playertext(shortprefix + "Jumping to '" + ChatColor.RED + seconds + ChatColor.RESET + "' seconds..."));
                    ReplayAPI.getInstance().jumpToReplayTime(p, seconds);
                }else {
                    p.sendMessage(playertext(shortprefix + "Time has to be between 1 and " + (duration -1)));
                }
            }else {
                p.sendMessage(playertext(shortprefix + "You need to play a replay first!"));
            }
        }));
        commandManager.register("replaydelete", ((sender, params) -> {
            if (CommandValidate.notPlayer(sender)) return;
            if (!CommandValidate.noPerm(sender, "replaydelete")) return;
            if (params.length != 1){
                sender.sendMessage(playertext(prefix + "Wrong parameters! /hg replaydelete <name>"));
                return;
            }
            String name = params[0];
            if (ReplaySaver.exists(name)){
                ReplaySaver.delete(name);
                sender.sendMessage(playertext(shortprefix + "Deleted '" + ChatColor.RED + name + ChatColor.RESET + "'"));
            }else {
                sender.sendMessage(playertext(shortprefix + "Replay '" + ChatColor.RED + name + ChatColor.RESET + "' doesn't exist"));
            }
        }));
        commandManager.register("replayleave", ((sender, params) -> {
            if (CommandValidate.notPlayer(sender)) return;
            if (!CommandValidate.noPerm(sender, "replayleave")) return;
            Player p = (Player) sender;
            if (ReplayHelper.replaySessions.containsKey(p.getName())){
                Replayer replayer = ReplayHelper.replaySessions.get(p.getName());
                replayer.stop();
                p.sendMessage(playertext(shortprefix + "left current replay!"));
            }else {
                p.sendMessage(playertext(shortprefix + "You need to be playing a replay!"));
            }
        }));
        commandManager.register("replaymigrate", ((sender, params) -> {
            if (CommandValidate.notPlayer(sender)) return;
            if (!CommandValidate.noPerm(sender, "replaymigrate")) return;
            if (params.length != 1){
                sender.sendMessage(playertext(prefix + "Wrong parameters! /hg replaymigrate <File|Database>"));
                return;
            }
            List<String> options = Arrays.asList("file", "database");
            String option = params[0].toLowerCase();
            if (options.contains(option)){
                IReplaySaver migrationSaver = null;
                if (option.equalsIgnoreCase("file") && ReplaySaver.replaySaver instanceof  DatabaseReplaySaver) {
                    migrationSaver = new DefaultReplaySaver();
                }else if (option.equalsIgnoreCase("database") && ReplaySaver.replaySaver instanceof DatabaseReplaySaver) {
                    ConfigManager.USE_DATABASE = true;
                    ConfigManager.loadData(false);
                    migrationSaver = new DatabaseReplaySaver();
                }else {
                    sender.sendMessage(playertext(shortprefix + "You cannot migrate to the same system type!"));
                }
                sender.sendMessage(playertext(shortprefix + "Migrated replays to '" + ChatColor.RED + option + ChatColor.RESET + "'"));
                for (String replayname : ReplaySaver.getReplays()) {
                    this.migrate(replayname, migrationSaver);
                }
            }else {
                sender.sendMessage(playertext(shortprefix + "'" + ChatColor.RED + option + ChatColor.RESET + "' is an invalid arugment"));
            }
        }));
        commandManager.register("replayplay", ((sender, params) -> {
            if (CommandValidate.notPlayer(sender)) return;
            if (!CommandValidate.noPerm(sender, "replayplay")) return;
            if (params.length != 1){
                sender.sendMessage(playertext(prefix + "Wrong parameters! /hg replayplay <Name>"));
                return;
            }
            String name = params[0];
            final Player p = (Player) sender;
            if (ReplaySaver.exists(name) && !ReplayHelper.replaySessions.containsKey(p.getName())) {
                p.sendMessage(playertext(shortprefix + "Loading replay '" + ChatColor.RED + name + ChatColor.RESET + "' ..."));
                try {
                    ReplaySaver.load(params[0], new me.hackerguardian.main.utils.recordinghandler.fetcher.Consumer<Replay>() {
                        @Override
                        public void accept(Replay replay) {
                            if (replay == null){
                                p.sendMessage(playertext(shortprefix + "Replay '" + ChatColor.RED + name + ChatColor.RESET + "' could not be loaded!"));
                                return;
                            }
                            p.sendMessage(playertext(shortprefix + "Replay '" + ChatColor.RED + name + ChatColor.RESET + "' loaded with duration '" + ChatColor.RED + (replay.getData().getDuration() / 20) + ChatColor.RESET + "' seconds"));
                            replay.play(p);
                        }
                    });
                } catch (Exception e) {
                    if (hackerguardian.getInstance().getConfig().getBoolean("debug")) e.printStackTrace();
                    p.sendMessage(playertext(shortprefix + "Error loading '" + ChatColor.RED + name + ChatColor.RESET + "' Please contact an administrator!"));
                }
            }else {
                p.sendMessage(playertext(shortprefix + "Replay '" + ChatColor.RED + name + ChatColor.RESET + "' doesn't exist!"));
            }
        }));
        commandManager.register("replayreformat", ((sender, params) -> {
            if (CommandValidate.notPlayer(sender)) return;
            if (!CommandValidate.noPerm(sender, "replayreformat")) return;
            sender.sendMessage(playertext(shortprefix + "Reformatting all replay files!"));
            try {
                ((DefaultReplaySaver)ReplaySaver.replaySaver).reformatAll();
            } catch (Exception e) {
                if (hackerguardian.getInstance().getConfig().getBoolean("debug")) e.printStackTrace();
                sender.sendMessage(playertext(shortprefix + "Error reformatting files! Please contact an administrator!"));
            }

        }));
        commandManager.register("replaystart", ((sender, params) -> {
            if (CommandValidate.notPlayer(sender)) return;
            if (!CommandValidate.noPerm(sender, "replaystart")) return;
            if (params.length < 1){
                sender.sendMessage(playertext(prefix + "Wrong parameters! /hg replaystart <Name>:<Duration> [<Players ...]"));
                return;
            }
            String name = params[0];
            int duration = Integer.parseInt(params[1]);
            if (name == null || duration < 0) return;
            if (name.length() > 40) {
                sender.sendMessage(playertext(shortprefix + "Replay name too long!"));
                return;
            }
            if (ReplaySaver.exists(name) || ReplayManager.activeReplays.containsKey(name)) {
                sender.sendMessage(playertext(shortprefix + "Replay '" + ChatColor.RED + name + ChatColor.RESET + "' already exist"));
                return;
            }
            List<Player> toRecord = new ArrayList<>();
            if (params.length <= 2) {
                toRecord.addAll(Bukkit.getOnlinePlayers());
            }else {
                for (int i = 2; i < params.length; i++)  {
                    if (Bukkit.getPlayer(params[i]) != null) {
                        toRecord.add(Bukkit.getPlayer(params[i]));
                    }
                }
            }
            ReplayAPI.getInstance().recordReplay(name, sender, toRecord);
            if (duration <= 0) {
                sender.sendMessage(playertext(shortprefix + "Started recording '" + ChatColor.RED + name + ChatColor.RESET + "' \nuse /hg replaystop " + ChatColor.RED + name + ChatColor.RESET + " to save it"));
            }else {
                sender.sendMessage(playertext(shortprefix + "Started recording '" + ChatColor.RED + name + ChatColor.RESET + "' \nThe replay will be automatically saved after '" + ChatColor.RED + duration + ChatColor.RESET + "' seconds"));
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        BaseComponent[] comps;
                        ReplayAPI.getInstance().stopReplay(name, true, true);
                        String message = String.format("§4§l[§r§l§8HG§r§4§l]§r Hover for more info on '" + ChatColor.RED + name + ChatColor.RESET + "'");
                        sender.sendMessage(playertext(shortprefix + "Saving replay '" + ChatColor.RED + name + ChatColor.RESET + "' ...."));
                        comps = new ComponentBuilder(message)
                                .event(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder("Replay '" + ChatColor.RED + name + ChatColor.RESET + "'\n\nClick to play!").create()))
                                .event(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/hg replayplay " + name))
                                .create();
                        //sender.sendMessage(playertext();
                        ((Player) sender).spigot().sendMessage(comps);
                    }
                }.runTaskLater(this, duration * 20);
            }
            if (params.length <= 2) {
                sender.sendMessage(playertext(shortprefix + ChatColor.RED + "WARNING" + ChatColor.RESET + " You are recording all online players now!"));
            }
        }));
        commandManager.register("replaystop", ((sender, params) -> {
            if (CommandValidate.notPlayer(sender)) return;
            if (!CommandValidate.noPerm(sender, "replaystop")) return;
            if (params.length > 2 || params.length < 1) {
                sender.sendMessage(playertext(prefix + "Wrong parameters! /hg replaystop <name> [-nosave]"));
                return;
            }
            String name = params[0];
            if (ReplayManager.activeReplays.containsKey(name) && ReplayManager.activeReplays.get(name).isRecording()) {
                Replay replay = ReplayManager.activeReplays.get(name);
                if (params.length == 2 || replay.getRecorder().getData().getActions().size() == 0) {
                    replay.getRecorder().stop(false);
                    sender.sendMessage(playertext(shortprefix + "Stopped recording for replay '" + ChatColor.RED + name + ChatColor.RESET + "'"));
                } else {
                    sender.sendMessage(playertext(shortprefix + "Saving replay '" + ChatColor.RED + name + ChatColor.RESET + "' ...."));
                    replay.getRecorder().stop(true);

                    String path = ReplaySaver.replaySaver instanceof DefaultReplaySaver ? this.getDataFolder() + "/replays/" + name + ".replay" : null;
                    sender.sendMessage(playertext(shortprefix + "Saved replay '" + ChatColor.RED + (path != null ? path : "") + ChatColor.RESET + "'"));
                }
            }else {
                sender.sendMessage(playertext(shortprefix + "Replay '" + ChatColor.RED + name + ChatColor.RESET + "' not found!"));
            }
        }));

        commandManager.register("checkbannedip", ((sender, params) -> {
            if (CommandValidate.notPlayer(sender)) return;
            if (!CommandValidate.noPerm(sender, "chip")) return;
            MySQL sql = new MySQL();
            InetAddressValidator validator = InetAddressValidator.getInstance();
            if (params.length == 0) {
                sender.sendMessage(playertext(prefix + "Wrong parameters! /hg checkbannedip <ip>"));
                sender.sendMessage(playertext(shortprefix + "This will check how many players have been banned on the IP."));
                return;
            }
            if (params.length == 1) {
                if (sql.getbannedip(params[0]) == null) {
                    sender.sendMessage(playertext(prefix + "Error trying to check banned ip! Please note an Administrator!"));
                    return;
                }
                if (validator.isValidInet4Address(params[0])) {
                    String ip = params[0];
                    sender.sendMessage(playertext(prefix + "There is: " + sql.getbannedip(ip) + " Players banned on this IP."));
                    return;
                } else if (validator.isValidInet6Address(params[0])) {
                    String ip = params[0];
                    sender.sendMessage(playertext(prefix + "There is: " + sql.getbannedip(ip) + " Players banned on this IP."));
                    return;
                } else {
                    sender.sendMessage(playertext(prefix + "Please use a valid IP address!"));
                    sender.sendMessage(playertext(shortprefix + "Example: IPv4 127.0.0.1, \n IPv6 2001:0db8:0001:0000:0000:0ab9:C0A8:0102"));
                }


            }

        }));
        commandManager.register("set", ((sender, params) -> {
            if (!CommandValidate.noPerm(sender, "set")) return;
            MySQL sql = new MySQL();

            if (params.length != 1) {
                sender.sendMessage(playertext(prefix + "Wrong parameters! /hg set <player-name>"));
            } else {
                Player p = getServer().getPlayer(params[0]);
                sql.setplayerstatsban(p.getUniqueId(), "false", "false");
            }
        }));
        commandManager.register("view", ((sender, params) -> {
            if (!CommandValidate.noPerm(sender, "view")) return;
            MySQL sql = new MySQL();

            if (params.length != 1 && params.length != 2) {
                sender.sendMessage(playertext(prefix + "Wrong parameters! /hg view <player-name> [page 1-4]"));
                return;
            }
            Player p = getServer().getPlayer(params[0]);
            if (!p.isOnline()) {
                sender.sendMessage(playertext(prefix + ChatColor.RED + params[0] + ChatColor.RESET + " is not online!"));
                return;
            }
            if (!getServer().getOfflinePlayer(params[0]).hasPlayedBefore()) {
                sender.sendMessage(playertext(prefix + ChatColor.RED + params[0] + ChatColor.RESET + " have never joined this server."));
                return;
            }
            if (params.length == 1) {
                sender.sendMessage(playertext(prefix + "Statistics collected about '" + ChatColor.RED + params[0] + ChatColor.RESET + "'"));
                if (getServer().getPluginManager().isPluginEnabled("ProtocolLib")) {
                    sender.sendMessage(playertext(shortprefix + "Player last client version: '" + ChatColor.RED + sql.getplayerclientver(p.getUniqueId()) /*getVersion(p)*/ + ChatColor.RESET + "'"));
                } else {
                    sender.sendMessage(playertext(shortprefix + "Player last client version: '" + ChatColor.RED + "Unknown" + ChatColor.RESET + "'"));
                }
                sender.sendMessage(playertext(shortprefix + "Player last used client: '" + ChatColor.RED + sql.getuser(p.getUniqueId()) + ChatColor.RESET + "'"));
                sender.sendMessage(playertext(shortprefix + "Player last login: '" + ChatColor.RED + sql.getplayerjointime(p.getUniqueId()) + ChatColor.RESET + "'"));
                sender.sendMessage(playertext(shortprefix + "Player average CPS: '" + ChatColor.RED + cpsval(p.getPlayer()) + ChatColor.RESET + "'"));
                sender.sendMessage(playertext(shortprefix + "Player in banwave queue: '" + ChatColor.RED + sql.getplayerbwstatus(p.getUniqueId()) + ChatColor.RESET + "'"));
                sender.sendMessage(playertext(shortprefix + "------------- Page 1/4 -------------"));
                return;
            }

            if (params.length >= 2) {
                if (!StringUtils.isNumeric(params[1])) {
                    sender.sendMessage(playertext(prefix + ChatColor.RED + params[1] + ChatColor.RESET + " is not a valid number."));
                    return;
                }
                int viewnumber = Integer.valueOf(params[1]);

                if (viewnumber == 1) {
                    sender.sendMessage(playertext(prefix + "Statistics collected about '" + ChatColor.RED + params[0] + ChatColor.RESET + "'"));
                    if (getServer().getPluginManager().isPluginEnabled("ProtocolLib")) {
                        sender.sendMessage(playertext(shortprefix + "Player last client version: '" + ChatColor.RED + sql.getplayerclientver(p.getUniqueId()) + ChatColor.RESET + "'"));
                    } else {
                        sender.sendMessage(playertext(shortprefix + "Player last client version: '" + ChatColor.RED + "Unknown" + ChatColor.RESET + "'"));
                    }
                    sender.sendMessage(playertext(shortprefix + "Player last used client: '" + ChatColor.RED + sql.getuser(p.getUniqueId()) + ChatColor.RESET + "'"));
                    sender.sendMessage(playertext(shortprefix + "Player last login: '" + ChatColor.RED + sql.getplayerjointime(p.getUniqueId()) + ChatColor.RESET + "'"));
                    sender.sendMessage(playertext(shortprefix + "Player average CPS: '" + ChatColor.RED + cpsval(p.getPlayer()) + ChatColor.RESET + "'"));
                    sender.sendMessage(playertext(shortprefix + "Player in banwave queue: '" + ChatColor.RED + sql.getplayerbwstatus(p.getUniqueId()) + ChatColor.RESET + "'"));
                    sender.sendMessage(playertext(shortprefix + "------------- Page 1/5 -------------"));
                    return;
                } else if (viewnumber == 2) {
                    sender.sendMessage(playertext(prefix + "Statistics collected about '" + ChatColor.RED + params[0] + ChatColor.RESET + "'"));
                    sender.sendMessage(playertext(shortprefix + "Is Player banned: '" + ChatColor.RED + sql.getplayerban(p.getUniqueId()) + ChatColor.RESET + "'"));
                    sender.sendMessage(playertext(shortprefix + "Is Player muted: '" + ChatColor.RED + sql.getisplayermuted(p.getUniqueId()) + ChatColor.RESET + "'"));
                    sender.sendMessage(playertext(shortprefix + "Is Player migrated: '" + ChatColor.RED + sql.getisplayermigrated(p.getUniqueId()) + ChatColor.RESET + "'"));
                    sender.sendMessage(playertext(shortprefix + "Player mute amount: '" + ChatColor.RED + sql.getplayermute(p.getUniqueId()) + ChatColor.RESET + "'"));
                    sender.sendMessage(playertext(shortprefix + "Player kick amount: '" + ChatColor.RED + sql.getplayerkick(p.getUniqueId()) + ChatColor.RESET + "'"));
                    if (getConfig().getBoolean("Settings.logplayerip")) {
                        sender.sendMessage(playertext(shortprefix + "Player's last (1-" + this.getConfig().getInt("Settings.MaxIPListCount") + ") login IP's:"));
                        try {
                            sql.getPlayerIp(p.getUniqueId()).forEach((IP) -> sender.sendMessage("  - " + IP.toString().replaceAll("/", "")));
                        } catch (Exception e) {
                            if (hackerguardian.getInstance().getConfig().getBoolean("debug")) e.printStackTrace();
                        }
                    } else {
                        sender.sendMessage(playertext(shortprefix + "Player's last (1 - " + this.getConfig().getInt("Settings.MaxIPListCount") + ") login IP's:"));
                        sender.sendMessage(playertext(shortprefix + "Due to safety reasons we keep this safe."));
                    }
                    sender.sendMessage(playertext(shortprefix + "------------- Page 2/5 -------------"));
                    return;
                } else if (viewnumber == 3) {
                    sender.sendMessage(playertext(prefix + "Statistics collected about '" + ChatColor.RED + params[0] + ChatColor.RESET + "'"));
                    sender.sendMessage(playertext(shortprefix + "Player's last (1 - " + this.getConfig().getInt("Settings.MaxReasonListCount") + ") system triggers:"));
                    try {
                        sql.getPlayerTriggers(p.getUniqueId()).forEach((Reason) -> sender.sendMessage("  - " + Reason.toString()));
                    } catch (Exception e) {
                        if (hackerguardian.getInstance().getConfig().getBoolean("debug")) e.printStackTrace();
                    }
                    sender.sendMessage(playertext(shortprefix + "------------- Page 3/5 -------------"));
                    return;
                } else if (viewnumber == 4) {
                    sender.sendMessage(playertext(prefix + "Statistics collected about '" + ChatColor.RED + params[0] + ChatColor.RESET + "'"));
                    sender.sendMessage(playertext(shortprefix + "Player's last (1 - " + this.getConfig().getInt("Settings.MaxHandlerListCount") + ") kick/mute/ban/ip-ban/temp-ban:"));
                    sender.sendMessage(playertext(shortprefix + "[Handler]: [Reason]"));
                    try {

                        //sql.getPlayerhandler(p.getUniqueId()).forEach((Handler) -> sender.sendMessage(Handler.toString()));
                        sql.getPlayerhandlerReasons(p.getUniqueId()).forEach((Reason) -> sender.sendMessage(" - " + Reason.toString()));
                    } catch (Exception e) {
                        if (hackerguardian.getInstance().getConfig().getBoolean("debug")) e.printStackTrace();
                    }
                    sender.sendMessage(playertext(shortprefix + "------------- Page 4/5 -------------"));
                    return;
                } else if (viewnumber == 5) {
                    sender.sendMessage(playertext(prefix + "Statistics collected about '" + ChatColor.RED + params[0] + ChatColor.RESET + "'"));
                    sender.sendMessage(playertext(shortprefix + "Player's mods (if Forge is used):"));
                    try {

                        //sql.getPlayerhandler(p.getUniqueId()).forEach((Handler) -> sender.sendMessage(Handler.toString()));
                        sql.getPlayerMods(p.getUniqueId()).forEach((mods) -> sender.sendMessage(mods.toString()));
                    } catch (Exception e) {
                        if (hackerguardian.getInstance().getConfig().getBoolean("debug")) e.printStackTrace();
                    }
                    sender.sendMessage(playertext(shortprefix + "------------- Page 5/5 -------------"));
                    return;
                } else {
                    sender.sendMessage(playertext(prefix + ChatColor.RED + params[1] + ChatColor.RESET + " is not a valid list number."));
                    return;
                }
            }

        }));
        commandManager.register("report", ((sender, params) -> {
            if (CommandValidate.notPlayer(sender)) return;
            if (!CommandValidate.noPerm(sender, "report")) return;

            MySQL sql = new MySQL();
            StringBuilder reason = new StringBuilder();
            for (int i = 1; i < params.length; i++) {
                reason.append(params[i]);
                reason.append(" ");
            }
            if (sender.hasPermission("hg.report")) {
                if (params.length == 0) {
                    sender.sendMessage(playertext(prefix + "Wrong parameters! /hg report <user> <reason>"));
                    return;
                }
                if (params.length == 1) {
                    sender.sendMessage(playertext(prefix + "It looks like you'd like to report " + params[0] + ". You need to provide a reason."));
                    sender.sendMessage(playertext(prefix + "Wrong parameters! /hg report <user> <reason>"));
                    return;
                }
                String timeStamp = (new SimpleDateFormat("YYYYMMMddHHmmss")).format(Calendar.getInstance().getTime());
                UUID reportedBy = Bukkit.getPlayer(sender.getName()).getUniqueId();
                UUID reported = null;
                Player playerarg = Bukkit.getPlayer(params[0]);
                UUID id;
                OfflinePlayer oPlayer = Bukkit.getOfflinePlayer(params[0]);
                boolean type = false;
                if (playerarg == null) {
                    if (oPlayer == null) {
                        sender.sendMessage(playertext(prefix + "This player doesn't exist. Please try again."));
                        return;
                    }
                    if (!oPlayer.hasPlayedBefore()) {
                        sender.sendMessage(playertext(prefix + "This player have never joined this server."));
                        return;
                    }
                } else {
                    type = true;
                }
                if (type) {
                    reported = Bukkit.getPlayer(params[0]).getUniqueId();
                } else {
                    reported = Bukkit.getOfflinePlayer(params[0]).getUniqueId();
                }
                int maxReports = 10;
                /*if (maxReports != -1 ) { //&& sql.
                    sender.sendMessage(playertext(prefix + "You cannot make more than 10 reports."));
                    return;
                }*/
                if (reported.equals(reportedBy)) {
                    sender.sendMessage(playertext(prefix + "You can't report yourself!"));
                    return;
                }
                //sql.
                if (maxReports > -1) {
                    sender.sendMessage(playertext(prefix + "You have &c" + String.valueOf(maxReports) + " &rreports remaining. (max " + maxReports + ")")); // - sql.
                    String time = (new SimpleDateFormat("YYYY-MM-dd HH:mm:ss")).format(Calendar.getInstance().getTime());
                    sender.sendMessage(playertext(shortprefix + "Thanks for your report."));
                    sender.sendMessage(playertext(shortprefix + "Your report:"));
                    sender.sendMessage(playertext(shortprefix + "  - Your name: '" + ChatColor.RED + sender.getName() + ChatColor.RESET + "'"));
                    sender.sendMessage(playertext(shortprefix + "  - Player you reported: '" + ChatColor.RED + params[0] + ChatColor.RESET + "'"));
                    sender.sendMessage(playertext(shortprefix + "  - Date (YYYY-MM-DD) : '" + ChatColor.RED + String.valueOf(time) + ChatColor.RESET + "'"));
                    sender.sendMessage(playertext(shortprefix + "  - Reason: "));
                    sender.sendMessage(playertext("> " + ChatColor.RED + reason.toString()));
                    sender.sendMessage(playertext(shortprefix + "Staff should view your report within " + getConfig().getInt("Settings.ReportViewTime")));
                    for (Player loopplayer : Bukkit.getServer().getOnlinePlayers()) {
                        if (loopplayer.hasPermission("hg.reports.notify")) {
                            loopplayer.sendMessage("");
                            loopplayer.sendMessage(playertext(prefix + "Player: " + ChatColor.RED + params[0] + ChatColor.RESET + " has just been reported."));
                            //String[][] report = sql.get;
                            //int length = report.length;
                            loopplayer.sendMessage(playertext(prefix + "Type /hg reports view " + String.valueOf(params[0]) + ", for more info."));
                            loopplayer.sendMessage("");
                        }
                    }
                    return;
                }
                return;

            }
            sender.sendMessage("Unknown command. Type \"/help\" for help.\n");
            return;
        }));
        commandManager.register("tps", ((sender, params) -> {
            Player player = Bukkit.getPlayer(sender.getName());
            if (!CommandValidate.noPerm(sender, "tps")) return;
            if (params.length == 0) {
                sender.sendMessage(playertext(prefix + "Current TPS: " + Tps.getTPS()));
                return;
            } else {
                sender.sendMessage(playertext(prefix + "Current TPS: " + Tps.getTPS()));

                return;
            }
        }));
        commandManager.register("kick", ((sender, params) -> {
            if (!CommandValidate.noPerm(sender, "kick")) return;
            MySQL sql = new MySQL();
            StringBuilder reason = new StringBuilder();
            for (int i = 1; i < params.length; i++) {
                reason.append(params[i]);
                reason.append(" ");
            }


            if (params.length == 0) {
                sender.sendMessage(playertext(prefix + "Wrong parameters! /hg kick <player> <reason>"));
                return;
            }
            if (params.length == 1) {
                sender.sendMessage(playertext(prefix + "Okay you wanna kick '" + ChatColor.RED + params[0] + ChatColor.RESET + "', but you will need to supply a reason."));
                return;
            }
            Player p1 = Bukkit.getPlayer(params[0]);
            if (params.length >= 2) {
                if (p1.hasPermission("hg.bypass")) {
                    sender.sendMessage(playertext(prefix + "Sorry but we are unable to kick an operator!"));
                    sender.sendMessage(playertext(shortprefix + "If you believe this is an error please report to an Administrator!"));
                    return;
                }
                try {
                    Player p = Bukkit.getPlayer(params[0]);
                    String time = (new SimpleDateFormat("YYYY-MM-dd HH:mm:ss")).format(Calendar.getInstance().getTime());
                    if (sender instanceof Player) {
                        p.kickPlayer(playertext(shortprefix + "You where kicked for ") + ChatColor.RED + reason.toString() + ChatColor.RESET + "\n by " + ChatColor.RED + sender.getName() + ChatColor.RESET + " at " + ChatColor.RED + String.valueOf(time));
                        sender.sendMessage(playertext(prefix + "Kicked '" + ChatColor.RED + params[0] + ChatColor.RESET + "' for " + ChatColor.RED + reason.toString() + ChatColor.RESET + ""));
                    } else {
                        p.kickPlayer(playertext(shortprefix + "You where kicked for ") + ChatColor.RED + reason.toString() + ChatColor.RESET + "\n by " + ChatColor.RED + "Console" + ChatColor.RESET + " at " + ChatColor.RED + String.valueOf(time));
                        sender.sendMessage(playertext(prefix + "Kicked '" + ChatColor.RED + params[0] + ChatColor.RESET + "' for " + ChatColor.RED + reason.toString() + ChatColor.RESET + ""));
                    }
                    if (sender instanceof ConsoleCommandSender) {
                        sql.addPlayerHandlerReasons(p.getUniqueId(), "Kick", reason.toString(), "HackerGuardian");
                    }else {
                        sql.addPlayerHandlerReasons(p.getUniqueId(), "Kick", reason.toString(), sender.getName());
                    }
                    sql.addplayerkicks(p.getUniqueId(), 1);
                } catch (Exception e) {
                    if (hackerguardian.getInstance().getConfig().getBoolean("debug")) e.printStackTrace();

                }
            }

        }));
        commandManager.register("unmute", ((sender, params) -> {
            if (!CommandValidate.noPerm(sender, "unmute")) return;
            StringBuilder reason = new StringBuilder();
            MySQL sql = new MySQL();
            for (int i = 2; i < params.length; i++) {
                reason.append(params[i]);
                reason.append(" ");
            }


            if (params.length == 0) {
                sender.sendMessage(playertext(prefix + "Wrong parameters! /hg unmute <player>"));
                return;
            }
            Player p = Bukkit.getPlayer(params[0]);
            if (params.length == 1) {
                if (p.hasPermission("hg.bypass")) {
                    sender.sendMessage(playertext(prefix + "Sorry but we are unable to unmute an operator!"));
                    sender.sendMessage(playertext(shortprefix + "If you believe this is an error please report to an Administrator!"));
                    return;
                }
                if (sql.getisplayermuted(p.getUniqueId()).equalsIgnoreCase("false")) {
                    sender.sendMessage(playertext(prefix + "Sorry but this player is not muted!"));
                    return;
                } else {
                    sql.removeplayermute(p.getUniqueId());
                    p.sendMessage(playertext(prefix + "You've been unmuted!"));
                    sender.sendMessage(playertext(prefix + "Successfully unmuted '" + ChatColor.RED + p.getName() + ChatColor.RESET + "'"));
                }
                return;
            }


        }));
        commandManager.register("mute", ((sender, params) -> {
            if (!CommandValidate.noPerm(sender, "mute")) return;
            StringBuilder reason = new StringBuilder();
            MySQL sql = new MySQL();
            for (int i = 1; i < params.length; i++) {
                reason.append(params[i]);
                reason.append(" ");
            }


            if (params.length == 0) {
                sender.sendMessage(playertext(prefix + "Wrong parameters! /hg mute <player> <reason>"));
                return;
            }


            if (params.length == 1) {
                sender.sendMessage(playertext(prefix + "Okay you wanna mute '" + ChatColor.RED + params[0] + ChatColor.RESET + "', but you will need to supply a reason."));
                return;
            }
            Player p = Bukkit.getPlayer(params[0]);
            if (params.length >= 2) {
                if (p.hasPermission("hg.bypass")) {
                    sender.sendMessage(playertext(prefix + "Sorry but we are unable to mute an operator!"));
                    sender.sendMessage(playertext(shortprefix + "If you believe this is an error please report to an Administrator!"));
                    return;
                }
                if (sql.getisplayermuted(p.getUniqueId()).equalsIgnoreCase("true")) {
                    sender.sendMessage(playertext(prefix + "Sorry but this player is already muted!"));
                    return;
                } else {
                    sql.addplayermute(p.getUniqueId(), 1);
                    if (sender instanceof ConsoleCommandSender) {
                        sql.addPlayerHandlerReasons(p.getUniqueId(), "Mute", reason.toString(), "HackerGuardian");
                    }else {
                        sql.addPlayerHandlerReasons(p.getUniqueId(), "Mute", reason.toString(), sender.getName());
                    }
                    p.sendMessage(playertext(prefix + "You where muted for: " + reason.toString()));
                    sender.sendMessage(playertext(prefix + "Successfully muted '" + ChatColor.RED + p.getName() + ChatColor.RESET + "'"));
                }
                return;
            }

        }));
        commandManager.register("ban", ((sender, params) -> {
            if (!CommandValidate.noPerm(sender, "ban")) return;
            StringBuilder reason = new StringBuilder();
            MySQL sql = new MySQL();
            for (int i = 1; i < params.length; i++) {
                reason.append(params[i]);
                reason.append(" ");
            }
            if (params.length == 0) {
                sender.sendMessage(playertext(prefix + "Wrong parameters! /hg ban <player> <reason>"));
                return;
            }
            if (params.length == 1) {
                sender.sendMessage(playertext(prefix + "Okay you wanna mute '" + ChatColor.RED + params[0] + ChatColor.RESET + "', but you will need to supply a reason."));
                return;
            }
            OfflinePlayer p = Bukkit.getOfflinePlayer(params[0]);

            Player p1 = Bukkit.getPlayer(params[0]);
            if (params.length >= 2) {

                if (p.isOnline()) {
                    Player p2 = Bukkit.getPlayer(params[0]);
                    if (p2.hasPermission("hg.bypass")) {
                        sender.sendMessage(playertext(prefix + "Sorry but we are unable to ban an operator!"));
                        sender.sendMessage(playertext(shortprefix + "If you believe this is an error please report to an Administrator!"));
                        return;
                    }
                    if (sql.getplayerban(p.getUniqueId()).equalsIgnoreCase("true")) {
                        sender.sendMessage(playertext(prefix + "Sorry but this player is already banned!"));
                        return;
                    }
                }

                if (sql.getplayerban(p.getUniqueId()).equalsIgnoreCase("true")) {
                    sender.sendMessage(playertext(prefix + "Sorry but this player is already banned!"));
                    return;
                } else {
                    sql.setPlayerBantrue(p.getUniqueId());
                    if (sender instanceof ConsoleCommandSender) {
                        sql.addPlayerHandlerReasons(p.getUniqueId(), "Ban", reason.toString(), "HackerGuardian");
                    }else {
                        sql.addPlayerHandlerReasons(p.getUniqueId(), "Ban", reason.toString(), sender.getName());
                    }
                    String time = (new SimpleDateFormat("YYYY-MM-dd HH:mm:ss")).format(Calendar.getInstance().getTime());
                    if (sender instanceof Player) {
                        p1.kickPlayer(playertext(shortprefix + "You where banned for ") + ChatColor.RED + reason.toString() + ChatColor.RESET + "\n by " + ChatColor.RED + sender.getName() + ChatColor.RESET + " at " + ChatColor.RED + String.valueOf(time));
                        sender.sendMessage(playertext(prefix + "Banned '" + ChatColor.RED + params[0] + ChatColor.RESET + "' for " + ChatColor.RED + reason.toString() + ChatColor.RESET + ""));
                    } else {
                        p1.kickPlayer(playertext(shortprefix + "You where banned for ") + ChatColor.RED + reason.toString() + ChatColor.RESET + "\n by " + ChatColor.RED + "Console" + ChatColor.RESET + " at " + ChatColor.RED + String.valueOf(time));
                        sender.sendMessage(playertext(prefix + "Banned '" + ChatColor.RED + params[0] + ChatColor.RESET + "' for " + ChatColor.RED + reason.toString() + ChatColor.RESET + ""));
                    }
                }
                return;
            }

        }));
        commandManager.register("unban", ((sender, params) -> {
            if (!CommandValidate.noPerm(sender, "unban")) return;
            StringBuilder reason = new StringBuilder();
            MySQL sql = new MySQL();
            for (int i = 1; i < params.length; i++) {
                reason.append(params[i]);
                reason.append(" ");
            }

            if (params.length == 0) {
                sender.sendMessage(playertext(prefix + "Wrong parameters! /hg unban <player>"));
                return;
            }
            OfflinePlayer p = Bukkit.getOfflinePlayer(params[0]);
            if (params.length >= 1) {
                if (p.isOnline()) {
                    Player p2 = Bukkit.getPlayer(params[0]);
                    if (p2.hasPermission("hg.bypass")) {
                        sender.sendMessage(playertext(prefix + "Sorry but we are unable to unban an operator!"));
                        sender.sendMessage(playertext(shortprefix + "If you believe this is an error please report to an Administrator!"));
                        return;
                    }
                    if (sql.getplayerban(p2.getUniqueId()).equalsIgnoreCase("false")) {
                        sender.sendMessage(playertext(prefix + "Sorry but this player isn't banned!"));
                        return;
                    }
                }

                if (sql.getplayerban(p.getUniqueId()).equalsIgnoreCase("false")) {
                    sender.sendMessage(playertext(prefix + "Sorry but this player isn't banned!"));
                    return;
                } else {
                    sql.setPlayerBanfalse(p.getUniqueId());
                    if (reason.toString().isEmpty()) {
                        sender.sendMessage(playertext(prefix + "Sucessfully unbanned '" + ChatColor.RED + params[0] + ChatColor.RESET + "'"));
                    } else {
                        sender.sendMessage(playertext(prefix + "Sucessfully unbanned '" + ChatColor.RED + params[0] + ChatColor.RESET + "' for " + ChatColor.RED + reason.toString() + ChatColor.RESET + ""));
                    }
                    return;
                }
            }
        }));
        commandManager.register("ban-ip", ((sender, params) -> {
            if (!CommandValidate.noPerm(sender, "ban-ip")) return;
            StringBuilder reason = new StringBuilder();
            for (int i = 1; i < params.length; i++) {
                reason.append(params[i]);
                reason.append(" ");
            }
            if (params.length == 0) {
                sender.sendMessage(playertext(prefix + "Wrong parameters! /hg ban-ip <ip> <reason>"));
                return;
            }
        }));
        commandManager.register("temp-ban", ((sender, params) -> {
            if (!CommandValidate.noPerm(sender, "temp-ban")) return;
            StringBuilder reason = new StringBuilder();
            for (int i = 2; i < params.length; i++) {
                reason.append(params[i]);
                reason.append(" ");
            }
            if (params.length == 0) {
                sender.sendMessage(playertext(prefix + "Wrong parameters! /hg temp-ban <player> <time> <reason>"));
                return;
            }
        }));


        commandManager.register("reports", ((sender, params) -> {
            if (CommandValidate.notPlayer(sender)) return;
            if (!CommandValidate.noPerm(sender, "reports")) return;
            MySQL sql = new MySQL();
            StringBuilder reason = new StringBuilder();
            for (int i = 1; i < params.length; i++) {
                reason.append(params[i]);
                reason.append(" ");
            }
            if (sender.hasPermission("hg.reports")) {
                if (params.length == 0) {
                    sender.sendMessage(playertext(prefix + "Wrong parameters! /hg reports help"));
                    return;
                }

                if (params[0].equalsIgnoreCase("help")) {

                }
                if (params[0].equalsIgnoreCase("view")) {
                    if (sender.hasPermission("hg.reports.view")) {
                        if (params.length == 1) {
                            sender.sendMessage(playertext(prefix + "To view reports you can use Player name OR ID."));
                            sender.sendMessage(playertext(shortprefix + "To view by Name: " + ChatColor.RED + "/hg reports view <player> [Page]"));
                            sender.sendMessage(playertext(shortprefix + "To view by ID: " + ChatColor.RED + "/hg reports view <ID>"));
                            return;
                        }
                        if (params.length >= 2) {
                            Player player = Bukkit.getPlayer(params[1]);
                            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(params[1]);
                            UUID reported = null;
                            boolean type = false;
                            int arg = 0;
                            try {
                                arg = Integer.parseInt(params[1]);
                                //int lastid = sql.;
                                int lastid = 1;
                                if (lastid == -1) {
                                    sender.sendMessage(playertext(prefix + "Sorry but an error has occurred and we cannot retrieve latest ID"));
                                }
                                if (arg > lastid) {
                                    sender.sendMessage(playertext(prefix + "The typed ID does not exist in the system. Max ID: " + String.valueOf(lastid)));
                                    return;
                                }
                                try {
                                    return;
                                } catch (NullPointerException e) {

                                }
                            } catch (NumberFormatException e) {
                                if (player == null) {
                                    if (offlinePlayer == null) {
                                        sender.sendMessage(playertext(prefix + "This player doesn't exist. Please try again."));
                                        return;
                                    }
                                    if (!offlinePlayer.hasPlayedBefore()) {
                                        sender.sendMessage(playertext(prefix + "This player have never joined this server."));
                                        return;
                                    }
                                } else {
                                    type = true;
                                }
                                if (type) {
                                    reported = Bukkit.getPlayer(params[1]).getUniqueId();
                                } else {
                                    reported = Bukkit.getOfflinePlayer(params[1]).getUniqueId();
                                }
                                /*String[][] report = sql.g;
                                if (report == null || report.length == 0) {
                                    sender.sendMessage(playertext(prefix + "There currently isn't any reports on this player."));
                                    return;
                                }*/
                                //int reportsize = report.length;
                                int reportsize = 3;
                                try {
                                    if (params.length == 2) {
                                        sender.sendMessage(playertext(shortprefix + "Showing record 1 for " + ChatColor.RED + params[1]));
                                        sender.sendMessage(playertext(shortprefix + "We found a total number of: " + ChatColor.RED + String.valueOf(reportsize) + ChatColor.RESET + " report in records."));
                                        //stuff
                                        if (reportsize >= 2) {
                                            sender.sendMessage(playertext(shortprefix + "You can do: " + ChatColor.RED + "/hg reports view " + params[1] + " 2" + ChatColor.RESET + " to view the next record."));
                                        }
                                    }
                                } catch (Exception e2) {
                                    if (hackerguardian.getInstance().getConfig().getBoolean("debug"))
                                        e2.printStackTrace();

                                }
                            }
                        }
                    }
                }

            }
        }));

//        commandManager.register("info", (sender, params) -> {
//            if (!CommandValidate.noPerm(sender, "info")) return;
//            sender.sendMessage(playertext(shortprefix + "Neural network layer statistics: "));
//            sender.sendMessage(playertext(shortprefix + "  Dataset size: " + ChatColor.RED + summary.getInputCount()));
//            sender.sendMessage(playertext(shortprefix + "  Output layer: " + ChatColor.RED + summary.getOutputCount() + ChatColor.RESET + " neuron(s)"));
//            sender.sendMessage(playertext(shortprefix + "Neural network learning statistics:"));
//            sender.sendMessage(playertext(shortprefix + "  Epoch: " + ChatColor.RED + summary.getEpoch()));
//            sender.sendMessage(playertext(shortprefix + "  Current step size: " + ChatColor.RED + summary.getCurrentStepSize()));
//            sender.sendMessage(playertext(shortprefix + "Category statistics:"));
//            sender.sendMessage(playertext(shortprefix + "  Loaded: " + ChatColor.RED + categoryNameMap.size()));
//            sender.sendMessage(playertext(shortprefix + "  Mappings:"));
//
//        });

        commandManager.register("train", (sender, params) -> {
            if (CommandValidate.notPlayer(sender)) return;
            if (!CommandValidate.noPerm(sender, "train")) return;

        });

        commandManager.register("reload", (sender, params) -> {
            if (!CommandValidate.noPerm(sender, "reload")) return;
            MySQL sql = new MySQL();
            try {
                sql.shutdowndatabase();
                sql.setupCoreSystem();
                reloadConfig();
                ConfigManager.reloadConfig();
            } catch (Exception e) {
                sender.sendMessage(playertext(prefix + "An error has occured on reloading Database and config.!"));
                sender.sendMessage(playertext(shortprefix + "Please note an Administrator!"));
                if (hackerguardian.getInstance().getConfig().getBoolean("debug")) e.printStackTrace();
            }
            reloadConfig();
            sender.sendMessage(playertext(prefix + "Reloaded configuration."));
            return;
        });

        commandManager.register("resetdb", ((sender, params) -> {
            MySQL sql = new MySQL();
            if (CommandValidate.console(sender)) return;
            if (params.length == 0) {
                sender.sendMessage(playertext(shortprefix + ChatColor.RED + "[WARNING] THIS WILL ERASE EVERYTHING IN THE DATABASE OF HACKERGUARDIAN!"));
                sender.sendMessage(playertext(prefix + "Please confirm the reset!"));
                sender.sendMessage(playertext(shortprefix + "/hg resetdb confirm"));
            }
            if (params.length == 1) {
                sender.sendMessage(playertext(prefix + "Trying to erase database!"));
                sql.formatCoreDatabase();
            }


        }));



//        commandManager.register("Banwavetest", (sender, params) -> {
//            if (!CommandValidate.noPerm(sender, "bwtest")) return;
//            if (params.length != 1 && params.length != 2) {
//                sender.sendMessage(playertext(prefix + "This shouldn't really trigger AT ALL. Please report to system administrator."));
//                return;
//            }
//
//            Player testplayer = getServer().getPlayer(params[0]);
//            if (testplayer == null) {
//                sender.sendMessage(playertext(prefix + "Player'" + ChatColor.RED + params[0] + ChatColor.RESET + "' Was not found! Banwavetest failed."));
//                return;
//            }
//            if (params.length == 2)
//                if (!StringUtils.isNumeric(params[1])) {
//                    sender.sendMessage(playertext(prefix + ChatColor.RED + params[1] + ChatColor.RESET + " is not a valid number."));
//                    return;
//                }
//            int duration = params.length == 1 ? getConfig().getInt("test.default_duration") : Integer.valueOf(params[1]);
//            sender.sendMessage(playertext(shortprefix + "Attempting to sample motion of '" + ChatColor.RED + params[0]
//                    + ChatColor.RESET + "' for " + ChatColor.RED + duration + ChatColor.RESET + " seconds"));
//            sender.sendMessage(playertext(shortprefix + ChatColor.RED + "[WARNING] " + ChatColor.RESET
//                    + "Be aware of using this command. If used by users and not the system itself IT will add the ban count to system (if chances are that they are hacking)"));
//            sender.sendMessage(playertext(shortprefix + "The system doesn't check if this command is self triggered or player triggered."));
//            classifyPlayer(testplayer, duration, result -> {
//                double likelihood = SLMaths.round(result.getLikelihood() * 100, 2, RoundingMode.HALF_UP);
//                sender.sendMessage(playertext(shortprefix + "Neural network classification result:"));
//                sender.sendMessage(playertext(shortprefix + "  Best matched: " + ChatColor.RED + getCategoryNameFromID(result.getCategory())));
//                sender.sendMessage(playertext(shortprefix + "  Difference: " + ChatColor.RED + result.getDifference()));
//                sender.sendMessage(playertext(shortprefix + "  Likelihood: " + ChatColor.RED + likelihood + ChatColor.RESET + "%"));
//                if (!getCategoryNameFromID(result.getCategory()).contains("Legit".toLowerCase())) {
//                    if (getConfig().getBoolean("Settings.autoaddtobanwave")) {
//                        sender.sendMessage(playertext(prefix + "Recommended action: "));
//                        if (getCategoryNameFromID(result.getCategory()).contains("wurst".toLowerCase())) {
//                            sender.sendMessage(ChatColor.GREEN + "yeet");
//                        } else if (getCategoryNameFromID(result.getCategory()).contains("impact".toLowerCase())) {
//                            sender.sendMessage(ChatColor.GREEN + "yeet1");
//                        } else if (getCategoryNameFromID(result.getCategory()).contains("future".toLowerCase())) {
//                            sender.sendMessage(ChatColor.GREEN + "yeet2");
//                        } else if (getCategoryNameFromID(result.getCategory()).contains("forgehax".toLowerCase())) {
//                            sender.sendMessage(ChatColor.GREEN + "yeet3");
//                        } else if (getCategoryNameFromID(result.getCategory()).contains("wwe".toLowerCase())) {
//                            sender.sendMessage(ChatColor.GREEN + "yeet4");
//                        } else if (getCategoryNameFromID(result.getCategory()).contains("kami".toLowerCase())) {
//                            sender.sendMessage(ChatColor.GREEN + "yeet5");
//                        } else if (getCategoryNameFromID(result.getCategory()).contains("kamib".toLowerCase())) {
//                            sender.sendMessage(ChatColor.GREEN + "yeet6");
//                        } else if (getCategoryNameFromID(result.getCategory()).contains("lbounce".toLowerCase())) {
//                            sender.sendMessage(ChatColor.GREEN + "yeet7");
//                        } else if (getCategoryNameFromID(result.getCategory()).contains("skillcli".toLowerCase())) {
//                            sender.sendMessage(ChatColor.GREEN + "yeet8");
//                        } else if (getCategoryNameFromID(result.getCategory()).contains("aristois".toLowerCase())) {
//                            sender.sendMessage(ChatColor.GREEN + "yeet9");
//                        } else if (getCategoryNameFromID(result.getCategory()).contains("ares".toLowerCase())) {
//                            sender.sendMessage(ChatColor.GREEN + "yeet10");
//                        } else if (getCategoryNameFromID(result.getCategory()).contains("wolfram".toLowerCase())) {
//                            sender.sendMessage(ChatColor.GREEN + "yeet11");
//                        }
//                    } else {
//                        sender.sendMessage("test");
//                        /*Main.Systemban += 1;
//                        Main.ban.add(params[0]);
//                        this.getConfig().set("ban.players", Main.ban);
//                        this.saveConfig();
//                        this.registerConfig();*/
//                    }
//                }
//            });
//
//        });
//


        commandManager.register("nnpng", (sender, params) -> {
            if (!CommandValidate.noPerm(sender, "nnpng")) return;
            NeuralNetworkVisualizer.visualize(this.ai.network, "" + getDataFolder() + "/output/network.png");
        });


    }
    public int cpsval(Player p) {
        if (average.containsKey(p.getUniqueId())) {
            return average.get(p.getUniqueId());
        }
        return 0;
    }
    public void signalTimer7() {
        new BukkitRunnable() {
            public void run() {
                Integer[] tempSort = new Integer[2];
                for (Map.Entry<UUID, Integer> entry : current.entrySet()) {
                    if (!average.containsKey(entry.getKey()))
                        average.put(entry.getKey(), entry.getValue());
                    int lastCps = average.get(entry.getKey());
                    tempSort[0] = lastCps;
                    tempSort[1] = entry.getValue();
                    Arrays.sort(tempSort);
                    int median;
                    if (tempSort.length % 2 == 0)
                        median = (tempSort[tempSort.length / 2] + tempSort[tempSort.length / 2 - 1]) / 2;
                    else
                        median = tempSort[tempSort.length / 2];
                    average.put(entry.getKey(), median);
                    current.put(entry.getKey(), 0);
                }
            }
        }.runTaskTimer(this, 0L, 20L);
    }
    private static final class CommandValidate {
        private static boolean notPlayer(CommandSender sender) {
            if (!(sender instanceof Player))
                sender.sendMessage(hackerguardian.getInstance().playertext(getInstance().prefix + "This command can only be executed by a player."));
            return !(sender instanceof Player);
        }
        private static boolean console(CommandSender sender) {
            if (sender instanceof Player)
                sender.sendMessage(hackerguardian.getInstance().playertext(getInstance().prefix + "This command can only be executed in console."));
            return (sender instanceof Player);
        }
        private static boolean noPerm(CommandSender sender, String perm1) {
            if (!sender.hasPermission("hg." + perm1)) {
                sender.sendMessage(getInstance().playertext(getInstance().prefix + "Sorry but it seems you are missing the right privileges to run this command!"));
                sender.sendMessage(getInstance().playertext(getInstance().shortprefix + "If you believe this is an error please report to an Administrator!"));
                return false;
            }
            return true;
        }
    }
    public static Player randomPlayer() {
        Player[] players = Bukkit.getOnlinePlayers().toArray(new Player[0]);
        Random r = new Random();

        int n = r.nextInt((players.length - 1));

        return players[n];
    }

    public BaseComponent[] buildMessage(ReplayStats stats) {
        return new ComponentBuilder("§7§oRecorded data: ")
                .append("§6§n" + stats.getActionCount() + "§r")
                .event(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder(stats.getSortedActions().entrySet().stream().map(e -> "§7" + e.getKey() + ": §b" + e.getValue()).collect(Collectors.joining("\n"))).create()))
                .append(" §7§oactions")
                .reset()
                .create();

    }
    private Date getCreationDate(String replay) {
        if (ReplaySaver.replaySaver instanceof DefaultReplaySaver) {
            return new Date(new File(DefaultReplaySaver.DIR, replay + ".replay").lastModified());
        }

        if (ReplaySaver.replaySaver instanceof DatabaseReplaySaver) {
            return new Date(DatabaseReplaySaver.replayCache.get(replay).getTime());
        }

        return null;
    }

    private Comparator<String> dateComparator() {
        return (s1, s2) -> {
            if (getCreationDate(s1) != null && getCreationDate(s2) != null) {
                return getCreationDate(s1).compareTo(getCreationDate(s2));
            } else {
                return 0;
            }

        };
    }
    private void migrate(String replayName, IReplaySaver saver) {

        ReplaySaver.load(replayName, new me.hackerguardian.main.utils.recordinghandler.fetcher.Consumer<Replay>() {

            @Override
            public void accept(Replay replay) {
                try {
                    if (!saver.replayExists(replayName)) {
                        LogUtils.log("Migrating " + replayName + "...");

                        saver.saveReplay(replay);
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }

            }
        });
    }
    private String parseName(String[] args) {
        if (args.length >= 2) {
            String[] split = args[1].split(":");

            if (args[1].contains(":")) {
                if (split.length == 2 && split[0].length() > 0) return split[0];
            } else {
                return args[1];
            }
        }


        return me.hackerguardian.main.utils.recordinghandler.StringUtils.getRandomString(6);
    }

    private int parseDuration(String[] args) {
        if (args.length < 2 || !args[1].contains(":")) return 0;
        String[] split = args[1].split(":");

        if (split.length == 2 && MathUtils.isInt(split[1])) {
            return Integer.parseInt(split[1]);
        }

        if (split.length == 1) {
            if (!split[0].startsWith(":") || !MathUtils.isInt(split[0])) return -1;

            return Integer.parseInt(split[0]);
        }

        return 0;
    }
    public static hackerguardian getInstance() {
        return instance;
    }

    public HackerguardianAI getAi() {
        return ai;
    }
}
