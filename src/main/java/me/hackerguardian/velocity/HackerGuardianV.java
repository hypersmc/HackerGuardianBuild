package me.hackerguardian.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import me.hackerguardian.velocity.utils.VelocityTicketIssuer;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@Plugin(
        id = "hackerguardian-link",
        name = "HackerGuardian Linker",
        version = "0.0.1",
        authors = {"HypersMC", "JumpWatch"}
)
public class HackerGuardianV {
    public static final ChannelIdentifier CH = MinecraftChannelIdentifier.create("hg", "playerchannel");

    private final ProxyServer server;
    private final Path dataDirectory;
    private final Logger logger = Logger.getLogger("HGVelocity_Link");

    private Map<String, Object> config = Collections.emptyMap();
    private Map<String, Object> settings = Collections.emptyMap();
    private VDatabase database;
    private VelocityTicketIssuer issuer;
    private boolean channelRegistered;

    @Inject
    public HackerGuardianV(ProxyServer server, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        logger.info("Initialising HackerGuardian Velocity plugin.");

        try {
            config = loadConfig();
            settings = section(config, "Settings");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to load configvelocity.yml", e);
            return;
        }

        File configFile = dataDirectory.resolve("configvelocity.yml").toFile();
        database = new VDatabase(logger, configFile, config);
        if (!database.init()) {
            logger.severe("HackerGuardian Velocity startup stopped because database setup is incomplete or invalid.");
            return;
        }

        boolean secureLinkEnabled = booleanValue(settings.get("hg_secure_link"), false);
        if (!secureLinkEnabled) {
            logger.info("HG Secure Link is disabled on Velocity.");
            return;
        }

        String secret = stringValue(settings.get("shared_secret"), "");
        long ttlMs = longValue(settings.get("ticket_ttl_ms"), 15_000L);

        issuer = new VelocityTicketIssuer(this, server, database, secret, ttlMs);
        server.getChannelRegistrar().register(CH);
        channelRegistered = true;
        logger.info("HG Secure Link (Velocity) enabled.");
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        if (issuer != null) issuer.handleServerConnected(event);
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        issuer = null;
        if (channelRegistered) {
            try {
                server.getChannelRegistrar().unregister(CH);
            } catch (Exception e) {
                logger.warning("Failed to unregister HG plugin channel: " + e.getMessage());
            }
            channelRegistered = false;
        }
        if (database != null) {
            database.shutdown();
            database = null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadConfig() throws IOException {
        Path configFile = dataDirectory.resolve("configvelocity.yml");
        if (!Files.exists(configFile)) {
            try (InputStream in = getClass().getResourceAsStream("/configvelocity.yml")) {
                if (in == null) throw new IOException("Bundled configvelocity.yml was not found");
                Files.createDirectories(dataDirectory);
                Files.copy(in, configFile);
                logger.info("Default configuration file copied to: " + configFile);
            }
        }

        Yaml yaml = new Yaml();
        try (InputStream in = Files.newInputStream(configFile)) {
            Object loaded = yaml.load(in);
            if (!(loaded instanceof Map)) {
                throw new IOException("configvelocity.yml must contain a YAML mapping at its root");
            }
            return (Map<String, Object>) loaded;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> root, String key) {
        Object value = root.get(key);
        if (value instanceof Map) return (Map<String, Object>) value;
        return Collections.emptyMap();
    }

    private static String stringValue(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value == null) return fallback;
        try { return Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value == null) return fallback;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    public ProxyServer getServer() {
        return server;
    }

    public Logger getLogger() {
        return logger;
    }

    public VDatabase getDatabase() {
        return database;
    }
}
