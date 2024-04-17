package me.hackerguardian.main.utils;


import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.plugin.Plugin;

/**
 * @author JumpWatch on 28-03-2023
 * @Project HackerGuardian
 * v1.0.0
 */
public class util {



    public String detectPluginProtocollib(){
        if(Bukkit.getPluginManager().getPlugin("ProtocolLib") != null){
            return "" + ChatColor.DARK_GRAY +"|      " + ChatColor.GREEN +"ProtocolLib"+ ChatColor.RESET + "\n";
        }else{
            return "" + ChatColor.DARK_GRAY +"|      " + ChatColor.RED+"ProtocolLib"+ ChatColor.RESET + "\n";
        }
    }
    public String detectPluginViaversion(){
        if(Bukkit.getPluginManager().getPlugin("ViaVersion") != null){
            return "" + ChatColor.DARK_GRAY +"|      " + ChatColor.GREEN +"ViaVersion"+ ChatColor.RESET + "\n";
        }else{
            return "" + ChatColor.DARK_GRAY +"|      " + ChatColor.RED+"ViaVersion"+ ChatColor.RESET + "\n";
        }
    }
    public String detectPluginProtocolsupport(){
        if(Bukkit.getPluginManager().getPlugin("ProtocolSupport") != null){
            return "" + ChatColor.DARK_GRAY +"|      " + ChatColor.GREEN +"ProtocolSupport"+ ChatColor.RESET + "\n";
        }else{
            return "" + ChatColor.DARK_GRAY +"|      " + ChatColor.RED+"ProtocolSupport"+ ChatColor.RESET + "\n";
        }
    }
    public String detectSettingAI(Plugin pl){
        if(pl.getConfig().getBoolean("Settings.EnableAI")){
            return "" + ChatColor.DARK_GRAY +"|      " + ChatColor.GREEN +"AI Addon"+ ChatColor.RESET + "\n";
        }else{
            return "" + ChatColor.DARK_GRAY +"|      " + ChatColor.RED+"AI Addon"+ ChatColor.RESET + "\n";
        }
    }
    public String detectSettingWebsite(Plugin pl){
        if(pl.getConfig().getBoolean("Settings.UseWebsiteFunction")){
            return "" + ChatColor.DARK_GRAY +"|      " + ChatColor.GREEN +"Website Addon"+ ChatColor.RESET + "\n";
        }else{
            return "" + ChatColor.DARK_GRAY +"|      " + ChatColor.RED+"Website Addon"+ ChatColor.RESET + "\n";
        }
    }
}
