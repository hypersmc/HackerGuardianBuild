package me.hackerguardian.main.utils.recordinghandler;

import java.util.HashMap;

import me.hackerguardian.main.Recording.API.ReplayAPI;
import me.hackerguardian.main.Recording.Replay;
import me.hackerguardian.main.Recording.filesys.ConfigManager;
import me.hackerguardian.main.Recording.listener.ReplayListener;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class ReplayManager {

    public static HashMap<String, Replay> activeReplays = new HashMap<String, Replay>();

    public static void register() {
        registerEvents();

        if (ConfigManager.RECORD_STARTUP) {
            ReplayAPI.getInstance().recordReplay(null, Bukkit.getConsoleSender(), new Player[] {});
        }
    }

    private static void registerEvents() {
        new ReplayListener().register();
    }



}
