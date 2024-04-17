package me.hackerguardian.main.utils;

import me.hackerguardian.main.HackerGuardian;
import me.hackerguardian.main.aicore.DataIO;
import me.hackerguardian.main.aicore.aievents.*;
import me.hackerguardian.main.aievents.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.neuroph.core.data.DataSet;

import java.io.File;
import java.lang.reflect.InvocationTargetException;

/**
 * @author JumpWatch on 30-11-2023
 * @Project HackerGuardian
 * v1.0.0
 */
public class schedules {
    static HackerGuardian main = HackerGuardian.getInstance();
    static textHandling text = new textHandling();

    public static void setDataForAI(JavaPlugin javaPlugin){
        new BukkitRunnable(){

            @Override
            public void run() {
                File dataDirectory = new File(main.getDataFolder(), "datacore");

                if (dataDirectory.exists() && dataDirectory.isDirectory()) {
                    File[] files = dataDirectory.listFiles((dir, name) -> name.endsWith(".bin"));

                    if (files != null && files.length > 0) {
                        for (File file : files) {
                            String fileName = file.getName();
                            String fileNameWithoutExtension = fileName.substring(0, fileName.lastIndexOf('.'));
                            File fileToLoad = new File(dataDirectory, fileName);
                            if (fileToLoad.exists()) {
                                DataSet dataSet = DataIO.loadTrainingData(fileToLoad.getName());
                                try {
                                    Class<?> clazz = Class.forName("me.hackerguardian.main.events." + fileNameWithoutExtension);
                                    Object ins = clazz.newInstance();
                                    clazz.getMethod("setTrainingData", DataSet.class, String.class).invoke(ins, dataSet, fileNameWithoutExtension);
                                    text.SendconsoleTextWsp("Training data for " + fileName + " have been added!");
                                } catch (ClassNotFoundException | InstantiationException |
                                         IllegalAccessException | InvocationTargetException |
                                         NoSuchMethodException e) {
                                    text.SendconsoleTextWsp("Failed to process file: " + fileName);
                                    if (main.getConfig().getBoolean("debug")) e.printStackTrace();
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
            }
        }.runTaskAsynchronously(javaPlugin);
    }
    public static void saveCurrentData(JavaPlugin javaPlugin){
        new BukkitRunnable(){

            @Override
            public void run() {
                text.SendconsoleTextWsp("Saving current data from AI");
                try {
                    main.saveTrainingDataToFile(onPlayerChat.getTrainingData(), "onPlayerChat.bin");
                    main.saveTrainingDataToFile(onPlayerInteract.getTrainingData(), "onPlayerInteract.bin");
                    main.saveTrainingDataToFile(onPlayerItemConsume.getTrainingData(), "onPlayerItemConsume.bin");
                    main.saveTrainingDataToFile(onPlayerMove.getTrainingData(), "onPlayerMove.bin");
                    main.saveTrainingDataToFile(onPlayerToggleFlight.getTrainingData(), "onPlayerToggleFlight.bin");
                    main.saveTrainingDataToFile(onPlayerToggleSneak.getTrainingData(), "onPlayerToggleSneak.bin");
                } catch (Exception e) {
                    if (HackerGuardian.getInstance().getConfig().getBoolean("debug")) e.printStackTrace();
                }
            }
        }.runTaskTimerAsynchronously(javaPlugin, 400L, 20L*60L*5L);
    }
}
