package me.hackerguardian.bungee;

import me.hackerguardian.api.HgApiServerProxy;
import me.hackerguardian.api.reports.ReportRepository;
import me.hackerguardian.bungee.moderation.ProxyPunishEnforcer;
import me.hackerguardian.bungee.utils.BMySQL;
import me.hackerguardian.bungee.utils.TicketIssuer;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * @author JumpWatch on 19-04-2024
 * @Project HackerGuardian
 * v1.0.0
 */
public class HackerGuardianB extends Plugin {
    public static Configuration configuration;
    private static HackerGuardianB instance;
    private HgApiServerProxy api;
    private BMySQL mysql;
    public ReportRepository reportsRepo;
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
        mysql = new BMySQL();
        mysql.setupCoreSystem();

        if (getConfiguration().getBoolean("Settings.hg_secure_link")) {
            getProxy().registerChannel("hg:playerchannel");
            getProxy().getPluginManager().registerListener(this, new TicketIssuer(this));


            // cleanup job (optional)
            getProxy().getScheduler().schedule(
                    this,
                    () -> mysql.cleanupExpired(this),
                    5, 30, TimeUnit.MINUTES
            );
            getLogger().info("HG Link (Bungee) enabled.");
        }
        getProxy().getPluginManager().registerListener(this, new ProxyPunishEnforcer(this, mysql));
        this.reportsRepo = new ReportRepository(getMysql().getDataSource());
        api = new HgApiServerProxy(this);
        api.startIfEnabled();

    }

    public BMySQL getMysql() { return mysql; }

    @Override
    public void onDisable() {
        if (api != null) {
            api.stop();
            api = null;
        }
        if (mysql != null) {
            mysql.shutdown();
            mysql = null;
        }
        instance = null;
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
            in.transferTo(outputStream); // Throws IOException
        }
    }
    public static HackerGuardianB getInstance() {
        return instance;
    }
    public static Configuration getConfiguration() { return configuration;}
}
