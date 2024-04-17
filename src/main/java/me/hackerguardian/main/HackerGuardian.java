package me.hackerguardian.main;

import me.hackerguardian.main.aicore.DataIO;
import me.hackerguardian.main.aicore.HackerguardianAI;
import me.hackerguardian.main.aicore.TrainingData;
import me.hackerguardian.main.aicore.aievents.*;
import me.hackerguardian.main.aievents.*;
import me.hackerguardian.main.utils.*;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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
    public HackerguardianAI ai;
    public HackerguardianAI ai2;
    public HackerguardianAI ai3;
    public HackerguardianAI ai4;
    public HackerguardianAI ai5;
    public HackerguardianAI ai6;
    public boolean learning;
    final util util = new util();
    private CommandManager commandManager;
    public static textHandling text = new textHandling();

    @Override
    public void onEnable() {
        instance = this;
        initializeFirstSetup();
        
        handleFileOperations();
        initializeSettings();
        initializeAI();
        commandManager = new CommandManager(this, "HackerGuardian");
        registerAllEvents();

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
        try {
            MySQL sql = new MySQL();
            sql.setupCoreSystem();
        } catch (Exception e) {
            ErrorHandler.handleGenericException(e, "Error saving training data");

        }
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
    public static HackerGuardian getInstance() {
        return instance;
    }
    private void saveAllTrainingData() {
        saveTrainingDataToFile(onPlayerChat.getTrainingData(), "onPlayerChat.bin");
        saveTrainingDataToFile(onPlayerInteract.getTrainingData(), "onPlayerInteract.bin");
        saveTrainingDataToFile(onPlayerItemConsume.getTrainingData(), "onPlayerItemConsume.bin");
        saveTrainingDataToFile(onPlayerMove.getTrainingData(), "onPlayerMove.bin");
        saveTrainingDataToFile(onPlayerToggleFlight.getTrainingData(), "onPlayerToggleFlight.bin");
        saveTrainingDataToFile(onPlayerToggleSneak.getTrainingData(), "onPlayerToggleSneak.bin");
    }
    public  void saveTrainingDataToFile(TrainingData trainingData, String fileName) {
        try {
            DataIO.saveTrainingData(trainingData.getDataSet(), getDataFolder() + "/datacore/" + fileName);
            text.SendconsoleTextWsp("Data for " + fileName + " saved!");
        } catch (Exception e) {
            ErrorHandler.handleGenericException(e, "Error Saving data for " + fileName);
        }
    }



    public void initializeAI() {
        if (this.getConfig().getBoolean("Settings.EnableAI")) {
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
            getServer().getPluginManager().registerEvents(new onPlayerChat(), this);
            getServer().getPluginManager().registerEvents(new onPlayerInteract(), this);
            getServer().getPluginManager().registerEvents(new onPlayerItemConsume(), this);
            getServer().getPluginManager().registerEvents(new onPlayerMove(), this);
            getServer().getPluginManager().registerEvents(new onPlayerToggleFlight(), this);
            getServer().getPluginManager().registerEvents(new onPlayerToggleSneak(), this);
            getServer().getPluginManager().registerEvents(new onPlayerJoin(), this);
        }
        Bukkit.getServer().getScheduler().scheduleSyncRepeatingTask(this, new Tps(), 100L, 1L);

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
}
