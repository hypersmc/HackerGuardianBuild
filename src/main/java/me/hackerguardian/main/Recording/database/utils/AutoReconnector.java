package me.hackerguardian.main.Recording.database.utils;

import me.hackerguardian.main.Recording.database.DatabaseRegistry;
import me.hackerguardian.main.Recording.database.MySQLDatabase;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

public class AutoReconnector extends BukkitRunnable {

    protected Plugin plugin;
    public AutoReconnector(Plugin plugin){
        this.plugin = plugin;
        this.runTaskTimerAsynchronously(plugin, 20*60, 20*60);
    }

    @Override
    public void run() {
        MySQLDatabase database = (MySQLDatabase) DatabaseRegistry.getDatabase();
        database.update("USE "+database.getDatabase()+"");
    }

}
