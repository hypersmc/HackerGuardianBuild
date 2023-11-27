package me.hackerguardian.main;

import me.hackerguardian.main.aicore.DataIO;
import me.hackerguardian.main.aicore.HackerguardianAI;
import me.hackerguardian.main.aicore.TrainingData;
import me.hackerguardian.main.events.*;
import me.hackerguardian.main.utils.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.logging.Logger;

/**
 * @author JumpWatch on 27-03-2023
 * @Project HackerGuardianV2
 * v1.0.0
 */
public class hackerguardian extends JavaPlugin {
    public textHandling text = new textHandling();

    private static hackerguardian instance;
    private CommandManager commandManager;
    public HackerguardianAI ai;
    private util util;
    public boolean learning;
    @Override
    public void onEnable() {
        instance = this;
        Logger logger = this.getLogger();
        loadConfig();
        //File & folder management
        if (new File("plugins/HackerGuardian/output/").exists()){
            text.SendconsoleTextWp("output folder exists!");
        }else if (!new File(getDataFolder() + "/output/", "dummyfile.txt").exists()){
            saveResource("output/dummyfile.txt", false);
            File removefile = new File(getDataFolder() + "/output/dummyfile.txt");
            try {
                removefile.delete();
            } catch (Exception ignored) {}
        }
        if (new File("plugins/HackerGuardian/datacore/").exists()){
            text.SendconsoleTextWp("datacore folder exists!");
        }else if (!new File(getDataFolder() + "/datacore/", "dummyfile.txt").exists()){
            saveResource("datacore/dummyfile.txt", false);
            File removefile = new File(getDataFolder() + "/datacore/dummyfile.txt");
            try {
                removefile.delete();
            } catch (Exception ignored) {}
        }
        try {
            FileUtil.saveResourceIfAbsent(this, "config.yml", "config.yml");
        } catch (IOException e) {
            if (hackerguardian.getInstance().getConfig().getBoolean("debug")) e.printStackTrace();

        }
        learning = instance.getConfig().getBoolean("Settings.LearningMode");
        MySQL sql = new MySQL();
        sql.setupCoreSystem();
        //General AI data loading
        File dataDirectory = new File(getDataFolder(), "datacore");

        if (dataDirectory.exists() && dataDirectory.isDirectory()) {
            File[] files = dataDirectory.listFiles((dir, name) -> name.endsWith(".bin"));

            if (files != null && files.length > 0) {
                for (File file : files) {
                    String fileName = file.getName();
                    String fileNameWithoutExtension = fileName.substring(0, fileName.lastIndexOf('.'));
                    String fileAbsolutePath = file.getAbsolutePath();
                    File fileToLoad = new File(dataDirectory, fileName);

                    text.SendconsoleTextWsp("Absolute path of " + fileName + ": " + fileAbsolutePath);
                    if (fileToLoad.exists()) {
                        TrainingData dataSet = DataIO.loadTrainingData(fileToLoad.getName());
                        try {
                            Class<?> clazz = Class.forName(fileName);
                            Object instance = clazz.getDeclaredConstructor().newInstance();
                            clazz.getMethod("setTrainingData", TrainingData.class, String.class)
                                    .invoke(instance, dataSet, fileNameWithoutExtension);
                            text.SendconsoleTextWsp("Training data for " + fileName + " have been added!");
                        } catch (ClassNotFoundException | InstantiationException |
                                 IllegalAccessException | InvocationTargetException |
                                 NoSuchMethodException ignored) {
                            text.SendconsoleTextWsp("Failed to process file: " + fileName);
                        }
                    } else {
                        text.SendconsoleTextWsp("File " + fileToLoad.getName() + " does not exist!");
                    }
                }
            } else {
                text.SendconsoleTextWsp("No .bin files found in the data-core folder!");
            }
        } else {
            text.SendconsoleTextWsp("Data-core directory does not exist!");
        }


        int totalInputs = calculateTotalInputs();
        int numOutputs = 1;
        int numHiddenNodes = 10;

        Bukkit.getServer().getScheduler().scheduleSyncRepeatingTask(this, new Tps(), 100L, 1L);
        commandManager = new CommandManager(this, "hackerguardian");
        ai = new HackerguardianAI(totalInputs, numOutputs, numHiddenNodes);
//        getServer().getPluginManager().registerEvents(new onPlayerChat(), this);
        getServer().getPluginManager().registerEvents(new onPlayerInteract(), this);
        getServer().getPluginManager().registerEvents(new onPlayerItemConsume(), this);
        getServer().getPluginManager().registerEvents(new onPlayerMove(), this);
        getServer().getPluginManager().registerEvents(new onPlayerToggleFlight(), this);
        getServer().getPluginManager().registerEvents(new onPlayerToggleSneak(), this);
        getServer().getPluginManager().registerEvents(new onPlayerJoin(), this);

    }

    @Override
    public void onDisable() {
        try {
            TrainingData onplayerChat = onPlayerChat.getTrainingData();
            DataIO.saveTrainingData(onplayerChat.getDataSet(), getDataFolder() + "/datacore/" + "onPlayerChat.bin");

            TrainingData onplayerInteract = onPlayerInteract.getTrainingData();
            DataIO.saveTrainingData(onplayerInteract.getDataSet(), getDataFolder() + "/datacore/" + "onPlayerInteract.bin");

            TrainingData onplayerItemConsume = onPlayerItemConsume.getTrainingData();
            DataIO.saveTrainingData(onplayerItemConsume.getDataSet(), getDataFolder() + "/datacore/" + "onPlayerItemConsume.bin");

            TrainingData onplayerMove = onPlayerMove.getTrainingData();
            DataIO.saveTrainingData(onplayerMove.getDataSet(), getDataFolder() + "/datacore/" + "onPlayerMove.bin");

            TrainingData onplayerToggleFlight = onPlayerToggleFlight.getTrainingData();
            DataIO.saveTrainingData(onplayerToggleFlight.getDataSet(), getDataFolder() + "/datacore/" + "onPlayerToggleFlight.bin");

            TrainingData onplayerToggleSneak = onPlayerToggleSneak.getTrainingData();
            DataIO.saveTrainingData(onplayerToggleSneak.getDataSet(), getDataFolder() + "/datacore/" + "onPlayerToggleSneak.bin");
        } catch (Exception e) {
            if (hackerguardian.getInstance().getConfig().getBoolean("debug")) e.printStackTrace();
        }
    }
    public void loadConfig() {
        saveDefaultConfig();
        reloadConfig();
    }
    public static hackerguardian getInstance() {
        return instance;
    }

    public HackerguardianAI getAi() {
        return ai;
    }
    public int calculateTotalInputs() {
        return 6;
    }
}
