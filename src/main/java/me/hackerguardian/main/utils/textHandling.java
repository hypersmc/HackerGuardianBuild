package me.hackerguardian.main.utils;

import me.hackerguardian.main.HackerGuardian;
import org.bukkit.ChatColor;

/**
 * @author JumpWatch on 26-11-2023
 * @Project HackerGuardian
 * v1.0.0
 */
public class textHandling {
    public String prefix = "&4&l[&r&l&8HackerGuardian&r&4&l]&r ";
    public String prefixnoBR = "&l&8HackerGuardian&r ";
    public String shortprefix = "&4&l[&r&l&8HG&r&4&l]&r ";

    public textHandling(){}


    /**
     * @param text
     * @return will return the text argument with colors if (&) and color codes are present.
     */
    public String consoleText(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
    /**
     * @param text
     * @return will return the text argument with colors if (&) and color codes are present.
     */
    public String consoleTextWsp(String text) {
        return ChatColor.translateAlternateColorCodes('&',shortprefix + text);
    }
    /**
     * @param text
     * @return will return the text argument with colors if (&) and color codes are present.
     */
    public String consoleTextWp(String text) {
        return ChatColor.translateAlternateColorCodes('&',prefix + text);
    }

    /**
     * @param text
     */
    public void SendconsoleTextWp(String text) {
        HackerGuardian.getInstance().getServer().getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&',prefix + text));
    }
    /**
     * @param text
     */
    public void SendconsoleTextWsp(String text) {
        HackerGuardian.getInstance().getServer().getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&',shortprefix + text));
    }

    /**
     * @param text
     * @return will return the text argument with colors if (&) and color codes are present.
     */
    public String playerText(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
    /**
     * @param text
     * @return will return the text argument with colors if (&) and color codes are present.
     */
    public String playerTextWsp(String text) {
        return ChatColor.translateAlternateColorCodes('&',shortprefix + text);
    }
    /**
     * @param text
     * @return will return the text argument with colors if (&) and color codes are present.
     */
    public String playerTextWp(String text) {
        return ChatColor.translateAlternateColorCodes('&',prefix + text);
    }


}
