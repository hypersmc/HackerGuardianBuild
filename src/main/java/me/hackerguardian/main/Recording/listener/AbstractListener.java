package me.hackerguardian.main.Recording.listener;

import me.hackerguardian.main.hackerguardian;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;


public abstract class AbstractListener implements Listener{

    protected hackerguardian plugin;

    public AbstractListener(){
        this.plugin = hackerguardian.getInstance();
    }

    public void register(){
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
    }

    public void unregister(){
        HandlerList.unregisterAll(this);
    }


}
