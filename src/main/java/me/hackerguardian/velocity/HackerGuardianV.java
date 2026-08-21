package me.hackerguardian.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import me.hackerguardian.velocity.utils.VelocityTicketIssuer;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@Plugin(id = "hackerguardian-link", name = "HackerGuardian Linker", version = "0.0.1",
authors = {"HypersMC", "JumpWatch"})
public class HackerGuardianV {
    public static final ChannelIdentifier CH = MinecraftChannelIdentifier.create("hg", "playerchannel");

    private final ProxyServer server;
    private final Logger logger = Logger.getLogger("HGVelocity_Link");
    private final VelocityTicketIssuer issuer;
    public static File dataFolder;
    public static Map<String, Object> config;
    public static Map<String, Object> settings;
    public static Map<String, Object> sql;
    private static HackerGuardianV instance;

    @Inject
    public HackerGuardianV(ProxyServer server, @DataDirectory Path dataFolder) {
        this.server = server;
        this.issuer = new VelocityTicketIssuer(server);
        HackerGuardianV.dataFolder = dataFolder.toFile();

    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent e) {
        boolean enablelinker = (boolean) settings.getOrDefault("hg_secure_link", false);
        if (enablelinker) {
            issuer.handleServerConnected(e);
        }
    }

    @Subscribe //Velocity
    public void onProxyInitialization(ProxyInitializeEvent event) throws IOException {
        instance = this;
        logger.info("HGVelocity_Link initialized");
        loadConfig();
        //noinspection unchecked
        settings = (Map<String, Object>) config.get("Settings");
        //noinspection unchecked
        sql = (Map<String, Object>) config.get("SQL");
        logger.info("Starting MySQL system");
        VMySQL mysql = new VMySQL();
        mysql.setupCoreSystem();

        //LINKER
        boolean enablelinker = (boolean) settings.getOrDefault("hg_secure_link", false);
        if (enablelinker) {
            server.getChannelRegistrar().register(CH);
        }
    }


    private void loadConfig() {
        try {
            Path configFile = dataFolder.toPath().resolve("configvelocity.yml");
            if (!Files.exists(configFile)) {
                try (InputStream in = getClass().getResourceAsStream("/configvelocity.yml")) {
                    if (in == null) {
                        logger.log(Level.WARNING, "Resource config.yml not found in plugin JAR.");
                        return;
                    }
                    Files.createDirectories(dataFolder.toPath());
                    Files.copy(in, configFile);
                    logger.info("Default configuration file copied to: " + configFile);
                }
            }
            Yaml yaml = new Yaml();
            try (InputStream in = Files.newInputStream(configFile)) {
                config = yaml.load(in);
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to load configuration file");
        }
    }
    private void saveConfig() {
        try {
            Path configFile = dataFolder.toPath().resolve("config.yml");
            Yaml yaml = new Yaml();
            try (OutputStream out = Files.newOutputStream(configFile);
                 OutputStreamWriter writer = new OutputStreamWriter(out)) {
                yaml.dump(config, writer);
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to save configuration file");
        }
    }

    public static HackerGuardianV getInstance() {
        return instance;
    }
    public ProxyServer getServer() {
        return server;
    }
}
