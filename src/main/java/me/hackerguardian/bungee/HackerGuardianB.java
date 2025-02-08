package me.hackerguardian.bungee;

import me.hackerguardian.bungee.HG.Linker;
import me.hackerguardian.bungee.events.joinevent;
import me.hackerguardian.bungee.utils.BMySQL;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Logger;

/**
 * @author JumpWatch on 19-04-2024
 * @Project HackerGuardian
 * v1.0.0
 */
public class HackerGuardianB extends Plugin {
    public Configuration configuration;
    private static HackerGuardianB instance;
    Logger logger = Logger.getLogger("HGBungee_Link");
    @Override
    public void onEnable() {
        logger.info("Initialising plugin!");
        instance = this;
        try {
            makeConfig();
            configuration = ConfigurationProvider.getProvider(YamlConfiguration.class).load(new File(getDataFolder(), "configbungee.yml"));
            logger.info("Configuration registered!");
        } catch (IOException ignore) {}
        logger.info("Starting MySQL system");
        BMySQL sql = new BMySQL();
        sql.setupCoreSystem();
        getProxy().getPluginManager().registerListener(this, new joinevent());
        getProxy().registerChannel("hg:channel");
        getProxy().getPluginManager().registerListener(this, new Linker());
    }

    @Override
    public void onDisable() {
        super.onDisable();
    }

    public void makeConfig() throws IOException {
        // Create plugin config folder if it doesn't exist
        if (!getDataFolder().exists()) {
            logger.info("Created config folder: " + getDataFolder().mkdir());
        }

        File configFile = new File(getDataFolder(), "configbungee.yml");
        // Copy default config if it doesn't exist
        if (!configFile.exists()) {
            FileOutputStream outputStream = new FileOutputStream(configFile); // Throws IOException
            InputStream in = getResourceAsStream("configbungee.yml"); // This file must exist in the jar resources folder
//            in.transferTo(outputStream); // Throws IOException
        }
    }
    public static HackerGuardianB getInstance() {
        return instance;
    }

}
