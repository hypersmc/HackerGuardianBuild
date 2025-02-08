package me.hackerguardian.bungee.utils;

import net.md_5.bungee.api.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * @author JumpWatch on 19-04-2024
 * @Project HackerGuardian
 * v1.0.0
 */
public class PluginDisabler {
    static Logger logger = Logger.getLogger("PluginDisabler");
    public static void disablePlugin(Plugin plugin) {
        try {
            // Get the main class of the plugin
            Class<?> pluginClass = plugin.getClass();

            // Get the onDisable() method
            Method onDisableMethod = pluginClass.getDeclaredMethod("onDisable");

            // Make the method accessible (since it's likely to be private)
            onDisableMethod.setAccessible(true);

            // Invoke the onDisable() method
            onDisableMethod.invoke(plugin);
            logger.info("Plugin '" + plugin.getDescription().getName() + "' disabled!");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
