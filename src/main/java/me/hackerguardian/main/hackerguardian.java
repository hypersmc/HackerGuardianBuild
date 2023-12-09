package me.hackerguardian.main;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketListener;
import me.hackerguardian.main.aicore.DataIO;
import me.hackerguardian.main.aicore.HackerguardianAI;
import me.hackerguardian.main.aicore.TrainingData;
import me.hackerguardian.main.events.*;
import me.hackerguardian.main.utils.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.neuroph.core.data.DataSet;

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
    public HackerguardianAI ai2;
    public HackerguardianAI ai3;
    public HackerguardianAI ai4;
    public HackerguardianAI ai5;
    public HackerguardianAI ai6;
    private util util;
    public boolean learning;
    @Override
    public void onEnable() {

        instance = this;
        Logger logger = this.getLogger();
        loadConfig();
        AIPermissions.setLearningFilesPermissions(this);
        try {
            MySQL sql = new MySQL();
            sql.setupCoreSystem();
        } catch (Exception ignored) {}
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

        //AI so it's ready
        int totalInputs = calculateTotalInputs();
        int numOutputs = 1;
        int numHiddenNodes = 15;
        ai = new HackerguardianAI(totalInputs, numOutputs, numHiddenNodes);
        ai2 = new HackerguardianAI(5, numOutputs, numHiddenNodes);
        ai3 = new HackerguardianAI(50, numOutputs, numHiddenNodes);
        ai4 = new HackerguardianAI(12, numOutputs, numHiddenNodes);
        ai5 = new HackerguardianAI(50, numOutputs, numHiddenNodes);
        ai6 = new HackerguardianAI(3, numOutputs, numHiddenNodes);
        //General AI data
        schedules.setDataForAI(this);
        schedules.saveCurrentData(this);


        Bukkit.getServer().getScheduler().scheduleSyncRepeatingTask(this, new Tps(), 100L, 1L);
        commandManager = new CommandManager(this, "hackerguardian");

        getServer().getPluginManager().registerEvents(new onPlayerChat(), this);
        getServer().getPluginManager().registerEvents(new onPlayerInteract(), this);
        getServer().getPluginManager().registerEvents(new onPlayerItemConsume(), this);
        getServer().getPluginManager().registerEvents(new onPlayerMove(), this);
        getServer().getPluginManager().registerEvents(new onPlayerToggleFlight(), this);
        getServer().getPluginManager().registerEvents(new onPlayerToggleSneak(), this);
        getServer().getPluginManager().registerEvents(new onPlayerJoin(), this);
        new onPackageListener();
    }

    @Override
    public void onDisable() {
        try {
            saveTrainingDataToFile(onPlayerChat.getTrainingData(), "onPlayerChat.bin");
            saveTrainingDataToFile(onPlayerInteract.getTrainingData(), "onPlayerInteract.bin");
            saveTrainingDataToFile(onPlayerItemConsume.getTrainingData(), "onPlayerItemConsume.bin");
            saveTrainingDataToFile(onPlayerMove.getTrainingData(), "onPlayerMove.bin");
            saveTrainingDataToFile(onPlayerToggleFlight.getTrainingData(), "onPlayerToggleFlight.bin");
            saveTrainingDataToFile(onPlayerToggleSneak.getTrainingData(), "onPlayerToggleSneak.bin");
        } catch (Exception e) {
            if (hackerguardian.getInstance().getConfig().getBoolean("debug")) {
                e.printStackTrace();
            }
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
    public  void saveTrainingDataToFile(TrainingData trainingData, String fileName) {
        try {
            DataIO.saveTrainingData(trainingData.getDataSet(), getDataFolder() + "/datacore/" + fileName);
            text.SendconsoleTextWsp("Data for " + fileName + " saved!");
        } catch (Exception e) {
            if (hackerguardian.getInstance().getConfig().getBoolean("debug")) {
                e.printStackTrace();
            }
        }
    }
}
