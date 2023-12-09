package me.hackerguardian.main.utils;

import me.hackerguardian.main.aicore.DataIO;
import me.hackerguardian.main.events.*;
import me.hackerguardian.main.hackerguardian;
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
    static hackerguardian main = hackerguardian.getInstance();

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
                                    main.text.SendconsoleTextWsp("Training data for " + fileName + " have been added!");
                                } catch (ClassNotFoundException | InstantiationException |
                                         IllegalAccessException | InvocationTargetException |
                                         NoSuchMethodException e) {
                                    main.text.SendconsoleTextWsp("Failed to process file: " + fileName);
                                    if (main.getConfig().getBoolean("debug")) e.printStackTrace();
                                }
                            } else {
                                main.text.SendconsoleTextWsp("File " + fileToLoad.getName() + " does not exist!");
                            }
                        }
                    } else {
                        main.text.SendconsoleTextWsp("No .bin files found in the data-core folder!");
                    }
                } else {
                    main.text.SendconsoleTextWsp("Data-core directory does not exist!");
                }
            }
        }.runTaskAsynchronously(javaPlugin);
    }
    public static void saveCurrentData(JavaPlugin javaPlugin){
        new BukkitRunnable(){

            @Override
            public void run() {
                main.text.SendconsoleTextWsp("Saving current data from AI");
                try {
                    main.saveTrainingDataToFile(onPlayerChat.getTrainingData(), "onPlayerChat.bin");
                    main.saveTrainingDataToFile(onPlayerInteract.getTrainingData(), "onPlayerInteract.bin");
                    main.saveTrainingDataToFile(onPlayerItemConsume.getTrainingData(), "onPlayerItemConsume.bin");
                    main.saveTrainingDataToFile(onPlayerMove.getTrainingData(), "onPlayerMove.bin");
                    main.saveTrainingDataToFile(onPlayerToggleFlight.getTrainingData(), "onPlayerToggleFlight.bin");
                    main.saveTrainingDataToFile(onPlayerToggleSneak.getTrainingData(), "onPlayerToggleSneak.bin");
                } catch (Exception e) {
                    if (hackerguardian.getInstance().getConfig().getBoolean("debug")) e.printStackTrace();
                }
            }
        }.runTaskTimerAsynchronously(javaPlugin, 400L, 20L*60L*5L);
    }
}
