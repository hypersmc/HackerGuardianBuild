package me.hackerguardian.main;

import me.arcaniax.hdb.api.HeadDatabaseAPI;
import me.hackerguardian.main.Checkings.DamageHandler;
import me.hackerguardian.main.Checkings.ExemptHandler;
import me.hackerguardian.main.aicore.*;
import me.hackerguardian.main.aicore.aievents.*;
import me.hackerguardian.main.ui.info.info;
import me.hackerguardian.main.utils.*;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
/**
 * @author JumpWatch on 27-03-2023
 * @Project HackerGuardianV2
 * v1.0.0
 */
public class HackerGuardian extends JavaPlugin {
    private static HackerGuardian instance;
    final util util = new util();
    static HGai hgai;
    private CommandManager commandManager;
    private ExemptHandler exemptHandler = null;
    public DamageHandler damageHandler = null;
    public static textHandling text = new textHandling();
    /*
    old stuff for just learning mode
     */
    public HackerguardianAI ai;
    public HackerguardianAI ai2;
    public HackerguardianAI ai3;
    public HackerguardianAI ai4;
    public HackerguardianAI ai5;
    public HackerguardianAI ai6;
    public boolean learning;

    public static HGai getAi() {
        return hgai;
    }

    @Override
    public void onEnable() {
        instance = this;
//        MySQL sql = new MySQL();
//        sql.setupCoreSystem();
        initializeFirstSetup();
        handleFileOperations();
        initializeSettings();
        initializeAI();
        commandManager = new CommandManager(this, "HackerGuardian");
        regiserCommand();
        registerChecker();
        registerAllEvents();

    }
    private void initializeFirstSetup() {
//        HeadDatabaseAPI api = new HeadDatabaseAPI();
//        ItemStack right = api.getItemHead("7826");
//        ItemStack left = api.getItemHead("7827");

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
                        + ChatColor.RED +"Soft Dependencies: " + ChatColor.RESET + "\n"
                        + util.detectPluginProtocollib()
                        + util.detectPluginProtocolsupport()
                        + util.detectPluginViaversion()
                        + ChatColor.DARK_GRAY +"|   " + ChatColor.RESET
                        + ChatColor.RED +"Features enabled: " + ChatColor.RESET + "\n"
                        + util.detectSettingAI(this)
                        + util.detectSettingWebsite(this)
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
        MySQL sql = new MySQL();
        sql.InitializeDBShutdown();
        if (this.getConfig().getBoolean("Settings.EnableAI")) {
            saveAllTrainingData();
        }
    }
    private void saveAllTrainingData() {
        MySQL sql = new MySQL();
        if (this.getConfig().getBoolean("Settings.UseDBForAIData")){
            sql.insertAIData("onPlayerChat.bin", onPlayerChat.getTrainingData().getDataSet());
            sql.insertAIData("onPlayerInteract.bin", onPlayerInteract.getTrainingData().getDataSet());
            sql.insertAIData("onPlayerItemConsume.bin", onPlayerItemConsume.getTrainingData().getDataSet());
            sql.insertAIData("onPlayerMove.bin", onPlayerMove.getTrainingData().getDataSet());
            sql.insertAIData("onPlayerToggleFlight.bin", onPlayerToggleFlight.getTrainingData().getDataSet());
            sql.insertAIData("onPlayerToggleSneak.bin", onPlayerToggleSneak.getTrainingData().getDataSet());
        }else {
            saveTrainingDataToFile(onPlayerChat.getTrainingData(), "onPlayerChat.bin");
            saveTrainingDataToFile(onPlayerInteract.getTrainingData(), "onPlayerInteract.bin");
            saveTrainingDataToFile(onPlayerItemConsume.getTrainingData(), "onPlayerItemConsume.bin");
            saveTrainingDataToFile(onPlayerMove.getTrainingData(), "onPlayerMove.bin");
            saveTrainingDataToFile(onPlayerToggleFlight.getTrainingData(), "onPlayerToggleFlight.bin");
            saveTrainingDataToFile(onPlayerToggleSneak.getTrainingData(), "onPlayerToggleSneak.bin");
        }
    }
    public  void saveTrainingDataToFile(TrainingData trainingData, String fileName) {
        try {
            DataIO.saveTrainingData(trainingData.getDataSet(), getDataFolder() + "/datacore/" + fileName);
            text.SendconsoleTextWsp("Data for " + fileName + " saved!");
        } catch (Exception e) {
            ErrorHandler.handleGenericException(e, "Error Saving data for " + fileName);
        }
    }
    public static HackerGuardian getInstance() {
        return instance;
    }




    public void initializeAI() {
        if (this.getConfig().getBoolean("Settings.EnableAI")) {
//            hgai = new HGai();
            int totalInputs = 6;
            int numOutputs = 1;
            int numHiddenNodes = 15;
            ai = new HackerguardianAI(totalInputs, numOutputs, numHiddenNodes);
            ai2 = new HackerguardianAI(5, numOutputs, numHiddenNodes);
            ai3 = new HackerguardianAI(50, numOutputs, numHiddenNodes);
            ai4 = new HackerguardianAI(12, numOutputs, numHiddenNodes);
            ai5 = new HackerguardianAI(50, numOutputs, numHiddenNodes);
            ai6 = new HackerguardianAI(3, numOutputs, numHiddenNodes);
            schedules.setDataForAI(this);
            schedules.saveCurrentData(this);
        }
    }
    private void registerAllEvents() {
        if (this.getConfig().getBoolean("Settings.EnableAI")) { //AI events
//            getServer().getPluginManager().registerEvents(new AiEvents(), this);
            getServer().getPluginManager().registerEvents(new onPlayerChat(), this);
            getServer().getPluginManager().registerEvents(new onPlayerInteract(), this);
            getServer().getPluginManager().registerEvents(new onPlayerItemConsume(), this);
            getServer().getPluginManager().registerEvents(new onPlayerMove(), this);
            getServer().getPluginManager().registerEvents(new onPlayerToggleFlight(), this);
            getServer().getPluginManager().registerEvents(new onPlayerToggleSneak(), this);
            getServer().getPluginManager().registerEvents(new onPlayerJoin(), this);
            getServer().getPluginManager().registerEvents(new onEntityDamageByEntityEvent(), this);
        }
        Bukkit.getServer().getScheduler().scheduleSyncRepeatingTask(this, new Tps(), 100L, 1L);

    }
    private void registerChecker(){
        exemptHandler = new ExemptHandler(this);
        damageHandler = new DamageHandler(this);
    }

    private void handleFileOperations() {
        File outputFolder = new File(this.getDataFolder(), "output");
        File dataCoreFolder = new File(this.getDataFolder(), "datacore");

        if (!outputFolder.exists()) {
            outputFolder.mkdirs();
            text.SendconsoleTextWp("output folder created!");
        }
        if (!dataCoreFolder.exists()) {
            dataCoreFolder.mkdirs();
            text.SendconsoleTextWp("datacore folder created!");
        }
    }
    private void initializeSettings() {
        try {
            FileUtil.saveResourceIfAbsent(this, "config.yml", "config.yml");
        } catch (IOException e) {
            ErrorHandler.handleIOException(e, "Error saving config.yml");
        }
        learning = this.getConfig().getBoolean("Settings.LearningMode");

    }

    public ExemptHandler getExemptHandler() {
        return exemptHandler;
    }
    private void regiserCommand() {
        commandManager.register("test", (sender, params) -> { //Denne command er /zn menu
            Player p = (Player) sender;
            new info(p.getName()).open(p);
        });
    }
}
