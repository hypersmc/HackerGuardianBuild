package me.hackerguardian.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import me.hackerguardian.api.replays.ReplayApiHandler;
import me.hackerguardian.api.replays.ReplayApiRepository;
import me.hackerguardian.api.reports.ReportRepository;
import me.hackerguardian.api.reports.ReportsV1ApiHandler;
import me.hackerguardian.api.settings.SafeSettingsApiHandler;
import me.hackerguardian.api.status.PaperServerStatusHeartbeat;
import me.hackerguardian.api.status.ServerStatusApiHandler;
import me.hackerguardian.api.status.ServerStatusRepository;
import me.hackerguardian.compat.ServerCompatibility;
import me.hackerguardian.main.HackerGuardian;
import me.hackerguardian.main.detection.DetectionRuntime;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Paper/Spigot HTTP API host.
 *
 * When Paper is behind the HackerGuardian proxy this class deliberately does
 * not open a public listener. It still publishes sanitized operational status
 * into the shared database so the proxy can expose one authoritative API.
 */
public final class HgApiServerBackend {

    private final HackerGuardian plugin;
    private final long startedAtMs = System.currentTimeMillis();
    private final ServerCompatibility compatibility;

    private HttpServer server;
    private ExecutorService executor;
    private HgApiAuth auth;
    private PaperServerStatusHeartbeat statusHeartbeat;
    private ServerStatusRepository statusRepository;

    public HgApiServerBackend(HackerGuardian plugin) {
        this.plugin = plugin;
        this.compatibility = plugin.getDetectionRuntime() != null
                ? plugin.getDetectionRuntime().getCompatibility()
                : ServerCompatibility.detect();
    }

    public synchronized void startIfEnabled() {
        if (server != null || statusHeartbeat != null) {
            plugin.getLogger().warning("[HG-API] API/heartbeat is already running");
            return;
        }

        boolean useWebsite = plugin.getConfig().getBoolean("Settings.UseWebsiteFunction", false);
        if (!useWebsite) {
            plugin.getLogger().info("[HG-API] Not starting (UseWebsiteFunction=false)");
            return;
        }

        try {
            statusHeartbeat = new PaperServerStatusHeartbeat(plugin);
            statusHeartbeat.start();
        } catch (Exception e) {
            statusHeartbeat = null;
            plugin.getLogger().warning("[HG-API] Backend status heartbeat is unavailable: " + e.getMessage());
        }

        boolean behindProxy = plugin.getConfig().getBoolean("Settings.behind_proxy", false);
        if (behindProxy) {
            plugin.getLogger().info("[HG-API] Paper is behind a proxy; operational heartbeat enabled, public HTTP listener disabled.");
            return;
        }

        boolean enabled = plugin.getConfig().getBoolean("SettingsWeb.Api.enabled", false);
        if (!enabled) {
            plugin.getLogger().info("[HG-API] Public Paper API disabled (SettingsWeb.Api.enabled=false)");
            return;
        }

        boolean requireAuth = plugin.getConfig().getBoolean("SettingsWeb.Api.require_auth", true);
        Map<String, String> keys = readKeys();
        if (requireAuth && !hasUsableKey(keys)) {
            plugin.getLogger().severe("[HG-API] Refusing to start authenticated API: configure at least one non-placeholder SettingsWeb.Api.keys secret.");
            return;
        }

        long skew = plugin.getConfig().getLong("SettingsWeb.Api.allowed_skew_ms", 90_000L);
        long nonceTtl = plugin.getConfig().getLong("SettingsWeb.Api.nonce_ttl_ms", 300_000L);
        this.auth = new HgApiAuth(keys, requireAuth, skew, nonceTtl);

        String host = plugin.getConfig().getString("SettingsWeb.Api.bind_host", "127.0.0.1");
        int port = plugin.getConfig().getInt("SettingsWeb.Api.bind_port", 8787);

        try {
            DataSource dataSource = plugin.getDatabase().getDataSource();
            if (dataSource == null) throw new IllegalStateException("database is unavailable");

            ReportRepository reports = new ReportRepository(dataSource);
            ReplayApiRepository replays = new ReplayApiRepository(dataSource);
            statusRepository = new ServerStatusRepository(dataSource);
            statusRepository.ensureTable();

            ReportsV1ApiHandler reportHandler = new ReportsV1ApiHandler(auth, reports);
            ReplayApiHandler replayHandler = new ReplayApiHandler(auth, replays);
            String serverName = plugin.getConfig().getString("Settings.server_name", "default");
            long staleAfterMs = plugin.getConfig().getLong("SettingsWeb.Api.server_status_stale_ms", 60_000L);
            ServerStatusApiHandler serversHandler = new ServerStatusApiHandler(
                    auth, statusRepository, staleAfterMs, serverName
            );
            SafeSettingsApiHandler settingsHandler = new SafeSettingsApiHandler(auth, this::safeSettings);

            server = HttpServer.create(new InetSocketAddress(host, port), 0);
            AtomicInteger threadNumber = new AtomicInteger();
            int threads = Math.max(2, Math.min(32, plugin.getConfig().getInt("SettingsWeb.Api.worker_threads", 4)));
            executor = Executors.newFixedThreadPool(threads, runnable -> {
                Thread thread = new Thread(runnable, "HackerGuardian-Api-" + threadNumber.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);

            server.createContext("/v1/health", this::handleHealth);
            server.createContext("/v1/reports", reportHandler::handle);
            server.createContext("/v1/replays", replayHandler::handle);
            server.createContext("/v1/servers", serversHandler::handle);
            server.createContext("/v1/settings", settingsHandler::handle);

            server.start();
            plugin.getLogger().info("[HG-API] Paper API v1 listening on " + host + ":" + port);
        } catch (Exception e) {
            plugin.getLogger().severe("[HG-API] Failed to start Paper API: " + e.getMessage());
            if (plugin.getConfig().getBoolean("debug", false)) e.printStackTrace();
            stopHttpOnly();
        }
    }

    public synchronized void stop() {
        stopHttpOnly();
        if (statusHeartbeat != null) {
            try { statusHeartbeat.stop(); }
            catch (Exception e) { plugin.getLogger().warning("[HG-API] Failed to stop status heartbeat: " + e.getMessage()); }
            statusHeartbeat = null;
        }
    }

    private void stopHttpOnly() {
        boolean wasRunning = server != null || executor != null;
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) executor.shutdownNow();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
            executor = null;
        }
        if (wasRunning) plugin.getLogger().info("[HG-API] Paper HTTP listener stopped");
    }

    private void handleHealth(HttpExchange exchange) {
        try {
            HgApiHttp.AuthenticatedRequest request = HgApiHttp.authenticate(exchange, auth);
            if (!HgApiHttp.requireAuthenticated(exchange, request)) return;
            if (!HgApiHttp.requireGet(exchange, request)) return;

            DetectionRuntime detection = plugin.getDetectionRuntime();
            boolean detectionEnabled = detection != null && detection.isRunning();
            boolean learningEnabled = detectionEnabled && detection.getLearningRuntime().isEnabled();
            boolean probes = learningEnabled
                    && detection.getLearningRuntime().getProbeEngine() != null
                    && detection.getLearningRuntime().getProbeEngine().isEnabled()
                    && compatibility.supportsSyntheticPlayerPackets();

            LinkedHashMap<String, Object> database = new LinkedHashMap<>();
            database.put("configured", plugin.getDatabase().getDataSource() != null);
            database.put("healthy", databaseHealthy());
            database.put("type", plugin.getDatabase().getDatabaseType() == null
                    ? null : plugin.getDatabase().getDatabaseType().name().toLowerCase(java.util.Locale.ROOT));

            LinkedHashMap<String, Object> capabilities = new LinkedHashMap<>();
            capabilities.put("reports", true);
            capabilities.put("replays", plugin.getConfig().getBoolean("Replays.enabled", true));
            capabilities.put("detection", detectionEnabled);
            capabilities.put("moderation", true);
            capabilities.put("learning", learningEnabled);
            capabilities.put("synthetic_probes", probes);

            LinkedHashMap<String, Object> data = new LinkedHashMap<>();
            data.put("role", "backend");
            data.put("instance_name", plugin.getConfig().getString("Settings.server_name", "default"));
            data.put("plugin_version", plugin.getDescription().getVersion());
            data.put("minecraft_version", compatibility.minecraftVersionString());
            data.put("uptime_ms", Math.max(0L, System.currentTimeMillis() - startedAtMs));
            data.put("players_online", Bukkit.getOnlinePlayers().size());
            data.put("database", database);
            data.put("capabilities", capabilities);
            data.put("servers", ownServerStatus());
            HgApiHttp.writeOk(exchange, 200, data);
        } catch (java.io.IOException tooLarge) {
            HgApiHttp.writeError(exchange, 413, "REQUEST_TOO_LARGE", tooLarge.getMessage());
        } catch (Exception e) {
            HgApiHttp.writeError(exchange, 500, "INTERNAL_ERROR", "Health check failed");
        }
    }

    private List<Map<String, Object>> ownServerStatus() {
        List<Map<String, Object>> servers = new ArrayList<>();
        String ownName = plugin.getConfig().getString("Settings.server_name", "default");
        try {
            if (statusRepository != null) {
                long now = System.currentTimeMillis();
                long staleMs = plugin.getConfig().getLong("SettingsWeb.Api.server_status_stale_ms", 60_000L);
                for (ServerStatusRepository.Status status : statusRepository.list()) {
                    if (!ownName.equals(status.serverName())) continue;
                    boolean online = status.lastSeenMs() > 0L && now - status.lastSeenMs() <= staleMs;
                    servers.add(serverStatusMap(status, online));
                }
            }
        } catch (Exception ignored) {
        }
        if (servers.isEmpty()) {
            LinkedHashMap<String, Object> own = new LinkedHashMap<>();
            own.put("name", ownName);
            own.put("online", true);
            own.put("players_online", Bukkit.getOnlinePlayers().size());
            own.put("plugin_version", plugin.getDescription().getVersion());
            own.put("minecraft_version", compatibility.minecraftVersionString());
            own.put("last_seen_ms", System.currentTimeMillis());
            servers.add(own);
        }
        return servers;
    }

    private static Map<String, Object> serverStatusMap(ServerStatusRepository.Status status, boolean online) {
        LinkedHashMap<String, Object> item = new LinkedHashMap<>();
        item.put("name", status.serverName());
        item.put("online", online);
        item.put("players_online", online ? status.playersOnline() : 0);
        item.put("plugin_version", status.pluginVersion());
        item.put("minecraft_version", status.minecraftVersion());
        item.put("last_seen_ms", status.lastSeenMs() > 0L ? status.lastSeenMs() : null);
        return item;
    }

    private Map<String, Object> safeSettings() {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("detection", Map.of(
                "enabled", plugin.getConfig().getBoolean("DetectionV2.enabled", true),
                "observe_only", true
        ));
        data.put("learning", Map.of(
                "enabled", plugin.getConfig().getBoolean("DetectionV2.learning.enabled", false)
        ));

        LinkedHashMap<String, Object> replays = new LinkedHashMap<>();
        replays.put("enabled", plugin.getConfig().getBoolean("Replays.enabled", true));
        if (plugin.getConfig().contains("Replays.retention_days")) {
            replays.put("retention_days", plugin.getConfig().getInt("Replays.retention_days"));
        }
        data.put("replays", replays);
        data.put("moderation", Map.of("enabled", true));
        return data;
    }

    private boolean databaseHealthy() {
        DataSource dataSource = plugin.getDatabase().getDataSource();
        if (dataSource == null) return false;
        try (Connection connection = dataSource.getConnection()) {
            try { return connection.isValid(2); }
            catch (Throwable unsupported) { return !connection.isClosed(); }
        } catch (Exception e) {
            return false;
        }
    }

    private Map<String, String> readKeys() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("SettingsWeb.Api.keys");
        if (section == null) return Map.of();
        HashMap<String, String> keys = new HashMap<>();
        for (Map.Entry<String, Object> entry : section.getValues(false).entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            keys.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        return keys;
    }

    private static boolean hasUsableKey(Map<String, String> keys) {
        for (Map.Entry<String, String> entry : keys.entrySet()) {
            String value = entry.getValue();
            if (entry.getKey() == null || entry.getKey().isBlank() || value == null) continue;
            String normalized = value.trim();
            if (normalized.length() < 32) continue;
            if (normalized.toUpperCase(java.util.Locale.ROOT).contains("CHANGE_ME")) continue;
            return true;
        }
        return false;
    }
}
