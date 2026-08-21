package me.hackerguardian.bungee;

import me.hackerguardian.api.HgApiServerProxy;
import me.hackerguardian.api.reports.ReportRepository;
import me.hackerguardian.bungee.moderation.ProxyPunishEnforcer;
import me.hackerguardian.bungee.utils.BDatabase;
import me.hackerguardian.bungee.utils.TicketIssuer;
import me.hackerguardian.main.moderation.punish.PunishmentRepository;
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

public class HackerGuardianB extends Plugin {
    public static Configuration configuration;
    private static HackerGuardianB instance;

    private HgApiServerProxy api;
    private BDatabase database;
    public ReportRepository reportsRepo;

    private final Logger logger = Logger.getLogger("HGBungee_Link");

    @Override
    public void onEnable() {
        logger.info("Initialising HackerGuardian proxy plugin.");
        instance = this;

        try {
            makeConfig();
            configuration = ConfigurationProvider.getProvider(YamlConfiguration.class)
                    .load(new File(getDataFolder(), "configbungee.yml"));
            logger.info("Configuration registered.");
        } catch (IOException e) {
            logger.severe("Unable to load configbungee.yml: " + e.getMessage());
            return;
        }

        database = new BDatabase(this);
        if (!database.init()) {
            logger.severe("HackerGuardian proxy startup stopped because database setup is incomplete or invalid.");
            return;
        }

        try {
            // The proxy can start before any backend server, so it must also be
            // capable of creating the shared moderation/report schema.
            new PunishmentRepository(database.getDataSource()).ensureTables();
            new me.hackerguardian.main.report.ReportRepository(database.getDataSource()).ensureTables();
        } catch (Exception e) {
            logger.severe("Unable to initialize shared HackerGuardian database schema: " + e.getMessage());
            database.shutdown();
            return;
        }

        if (getConfiguration().getBoolean("Settings.hg_secure_link")) {
            getProxy().registerChannel("hg:playerchannel");
            getProxy().getPluginManager().registerListener(this, new TicketIssuer(this, database));

            getProxy().getScheduler().schedule(
                    this,
                    database::cleanupExpired,
                    5, 30, TimeUnit.MINUTES
            );
            getLogger().info("HG Link (Bungee) enabled.");
        }

        getProxy().getPluginManager().registerListener(this, new ProxyPunishEnforcer(this, database));
        reportsRepo = new ReportRepository(database.getDataSource());

        api = new HgApiServerProxy(this);
        api.startIfEnabled();
    }

    public BDatabase getDatabase() {
        return database;
    }

    @Override
    public void onDisable() {
        if (api != null) {
            api.stop();
            api = null;
        }
        if (database != null) {
            database.shutdown();
            database = null;
        }
        instance = null;
    }

    public void makeConfig() throws IOException {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            throw new IOException("Could not create plugin data folder: " + getDataFolder());
        }

        File configFile = new File(getDataFolder(), "configbungee.yml");
        if (!configFile.exists()) {
            try (InputStream in = getResourceAsStream("configbungee.yml")) {
                if (in == null) throw new IOException("Bundled configbungee.yml was not found");
                try (FileOutputStream outputStream = new FileOutputStream(configFile)) {
                    in.transferTo(outputStream);
                }
            }
        }
    }

    public static HackerGuardianB getInstance() {
        return instance;
    }

    public static Configuration getConfiguration() {
        return configuration;
    }
}
