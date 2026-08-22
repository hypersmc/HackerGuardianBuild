package me.hackerguardian.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import me.hackerguardian.api.detection.DetectionApiHandler;
import me.hackerguardian.api.learning.LearningApiHandler;
import me.hackerguardian.api.learning.LearningPlayerStatusRepository;
import me.hackerguardian.api.moderation.ModerationApiHandler;
import me.hackerguardian.api.moderation.ModerationApiRepository;
import me.hackerguardian.api.replays.ReplayApiHandler;
import me.hackerguardian.api.replays.ReplayApiRepository;
import me.hackerguardian.api.reports.ReportRepository;
import me.hackerguardian.api.reports.ReportsV1ApiHandler;
import me.hackerguardian.api.settings.SafeSettingsApiHandler;
import me.hackerguardian.api.status.ServerStatusApiHandler;
import me.hackerguardian.api.status.ServerStatusRepository;
import me.hackerguardian.bungee.HackerGuardianB;
import me.hackerguardian.main.detection.journal.DetectionEventRepository;
import me.hackerguardian.main.replay.ReplayStorage;
import net.md_5.bungee.config.Configuration;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Authoritative network API host when HackerGuardian is deployed behind Bungee/FlameCord. */
public final class HgApiServerProxy {

    private final HackerGuardianB plugin;
    private final long startedAtMs = System.currentTimeMillis();

    private HttpServer server;
    private ExecutorService executor;
    private HgApiAuth auth;
    private ServerStatusRepository statusRepository;

    public HgApiServerProxy(HackerGuardianB plugin) {
        this.plugin = plugin;
    }

    public synchronized void startIfEnabled() {
        if (server != null) {
            plugin.getLogger().warning("[HG-API] Proxy API is already running");
            return;
        }
        if (!plugin.getConfiguration().getBoolean("Settings.UseWebsiteFunction", false)) {
            plugin.getLogger().info("[HG-API] Not starting (UseWebsiteFunction=false)");
            return;
        }
        if (!plugin.getConfiguration().getBoolean("SettingsWeb.Api.enabled", false)) {
            plugin.getLogger().info("[HG-API] Not starting (SettingsWeb.Api.enabled=false)");
            return;
        }

        boolean requireAuth = plugin.getConfiguration().getBoolean("SettingsWeb.Api.require_auth", true);
        Map<String, String> keys = readKeys();
        if (requireAuth && !hasUsableKey(keys)) {
            plugin.getLogger().severe("[HG-API] Refusing to start authenticated proxy API: configure at least one non-placeholder SettingsWeb.Api.keys secret.");
            return;
        }
        long skew = plugin.getConfiguration().getLong("SettingsWeb.Api.allowed_skew_ms", 90_000L);
        long nonceTtl = plugin.getConfiguration().getLong("SettingsWeb.Api.nonce_ttl_ms", 300_000L);
        auth = new HgApiAuth(keys, requireAuth, skew, nonceTtl);

        String host = plugin.getConfiguration().getString("SettingsWeb.Api.bind_host", "127.0.0.1");
        int port = plugin.getConfiguration().getInt("SettingsWeb.Api.bind_port", 8787);

        try {
            DataSource dataSource = plugin.getDatabase().getDataSource();
            if (dataSource == null) throw new IllegalStateException("database is unavailable");

            // The proxy may start before all Paper backends. Ensure read-side
            // tables exist so every endpoint has a stable empty state.
            new ReplayStorage(dataSource, "proxy").ensureTables();
            statusRepository = new ServerStatusRepository(dataSource);
            statusRepository.ensureTable();
            DetectionEventRepository detectionEvents = new DetectionEventRepository(dataSource);
            detectionEvents.ensureTable();
            LearningPlayerStatusRepository learningPlayers = new LearningPlayerStatusRepository(dataSource);
            learningPlayers.ensureTable();

            ReportRepository reports = new ReportRepository(dataSource);
            ReplayApiRepository replays = new ReplayApiRepository(dataSource);
            ModerationApiRepository moderation = new ModerationApiRepository(dataSource);
            long staleAfterMs = plugin.getConfiguration().getLong("SettingsWeb.Api.server_status_stale_ms", 60_000L);

            ReportsV1ApiHandler reportHandler = new ReportsV1ApiHandler(auth, reports);
            ReplayApiHandler replayHandler = new ReplayApiHandler(auth, replays);
            ServerStatusApiHandler serversHandler = new ServerStatusApiHandler(
                    auth, statusRepository, staleAfterMs, null
            );
            DetectionApiHandler detectionHandler = new DetectionApiHandler(
                    auth, detectionEvents, replays, statusRepository,
                    null, null, staleAfterMs
            );
            LearningApiHandler learningHandler = new LearningApiHandler(
                    auth, learningPlayers, statusRepository,
                    null, null, staleAfterMs
            );
            ModerationApiHandler moderationHandler = new ModerationApiHandler(
                    auth, moderation, null
            );
            SafeSettingsApiHandler settingsHandler = new SafeSettingsApiHandler(auth, this::safeSettings);

            server = HttpServer.create(new InetSocketAddress(host, port), 0);
            int workers = Math.max(2, Math.min(32,
                    plugin.getConfiguration().getInt("SettingsWeb.Api.worker_threads", 4)));
            AtomicInteger threadNumber = new AtomicInteger();
            executor = Executors.newFixedThreadPool(workers, runnable -> {
                Thread thread = new Thread(runnable, "HackerGuardian-ProxyApi-" + threadNumber.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);

            server.createContext("/v1/health", this::handleHealth);
            server.createContext("/v1/reports", reportHandler::handle);
            server.createContext("/v1/replays", replayHandler::handle);
            server.createContext("/v1/detection", detectionHandler::handle);
            server.createContext("/v1/learning", learningHandler::handle);
            server.createContext("/v1/moderation", moderationHandler::handle);
            server.createContext("/v1/servers", serversHandler::handle);
            server.createContext("/v1/settings", settingsHandler::handle);

            server.start();
            plugin.getLogger().info("[HG-API] Proxy API v1 listening on " + host + ":" + port);
        } catch (Exception e) {
            plugin.getLogger().severe("[HG-API] Failed to start proxy API: " + e.getMessage());
            stop();
        }
    }

    public synchronized void stop() {
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
        if (wasRunning) plugin.getLogger().info("[HG-API] Proxy HTTP listener stopped");
    }

    private void handleHealth(HttpExchange exchange) {
        try {
            HgApiHttp.AuthenticatedRequest request = HgApiHttp.authenticate(exchange, auth);
            if (!HgApiHttp.requireAuthenticated(exchange, request)) return;
            if (!HgApiHttp.requireGet(exchange, request)) return;
            if (!"/v1/health".equals(request.path())) {
                HgApiHttp.writeError(exchange, 404, "NOT_FOUND", "Health route not found");
                return;
            }

            List<ServerStatusRepository.Status> statuses = statusRepository == null
                    ? List.of() : statusRepository.list();
            List<Map<String, Object>> servers = serverMaps(statuses);
            long now = System.currentTimeMillis();
            long staleMs = plugin.getConfiguration().getLong("SettingsWeb.Api.server_status_stale_ms", 60_000L);

            boolean detection = false;
            boolean learning = false;
            boolean probes = false;
            for (ServerStatusRepository.Status status : statuses) {
                boolean online = status.lastSeenMs() > 0L && now - status.lastSeenMs() <= staleMs;
                if (!online) continue;
                detection |= status.detectionEnabled();
                learning |= status.learningEnabled();
                probes |= status.syntheticProbes();
            }

            LinkedHashMap<String, Object> database = new LinkedHashMap<>();
            database.put("configured", plugin.getDatabase().getDataSource() != null);
            database.put("healthy", databaseHealthy());
            database.put("type", plugin.getDatabase().getDatabaseType() == null
                    ? null : plugin.getDatabase().getDatabaseType().name().toLowerCase(java.util.Locale.ROOT));

            LinkedHashMap<String, Object> capabilities = new LinkedHashMap<>();
            capabilities.put("reports", true);
            capabilities.put("replays", true);
            capabilities.put("detection", detection);
            capabilities.put("moderation", true);
            capabilities.put("learning", learning);
            capabilities.put("synthetic_probes", probes);

            LinkedHashMap<String, Object> data = new LinkedHashMap<>();
            data.put("role", "proxy");
            data.put("instance_name", plugin.getConfiguration().getString("Settings.instance_name", "network-proxy-1"));
            data.put("plugin_version", plugin.getDescription().getVersion());
            data.put("minecraft_version", null);
            data.put("uptime_ms", Math.max(0L, now - startedAtMs));
            data.put("players_online", plugin.getProxy().getOnlineCount());
            data.put("database", database);
            data.put("capabilities", capabilities);
            data.put("servers", servers);
            HgApiHttp.writeOk(exchange, 200, data);
        } catch (java.io.IOException tooLarge) {
            HgApiHttp.writeError(exchange, 413, "REQUEST_TOO_LARGE", tooLarge.getMessage());
        } catch (Exception e) {
            HgApiHttp.writeError(exchange, 500, "INTERNAL_ERROR", "Health check failed");
        }
    }

    private List<Map<String, Object>> serverMaps(List<ServerStatusRepository.Status> statuses) {
        long now = System.currentTimeMillis();
        long staleMs = plugin.getConfiguration().getLong("SettingsWeb.Api.server_status_stale_ms", 60_000L);
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> represented = new HashSet<>();

        for (ServerStatusRepository.Status status : statuses) {
            represented.add(status.serverName());
            boolean online = status.lastSeenMs() > 0L && now - status.lastSeenMs() <= staleMs;
            LinkedHashMap<String, Object> item = new LinkedHashMap<>();
            item.put("name", status.serverName());
            item.put("online", online);
            item.put("players_online", online ? status.playersOnline() : 0);
            item.put("plugin_version", status.pluginVersion());
            item.put("minecraft_version", status.minecraftVersion());
            item.put("detection_enabled", status.detectionEnabled());
            item.put("learning_enabled", status.learningEnabled());
            item.put("last_seen_ms", status.lastSeenMs() > 0L ? status.lastSeenMs() : null);
            out.add(item);
        }

        // A configured backend that has never published a heartbeat is still
        // useful to the panel as an offline/unknown server. Never expose its IP.
        for (String configured : plugin.getProxy().getServers().keySet()) {
            if (represented.contains(configured)) continue;
            LinkedHashMap<String, Object> item = new LinkedHashMap<>();
            item.put("name", configured);
            item.put("online", false);
            item.put("players_online", 0);
            item.put("plugin_version", null);
            item.put("minecraft_version", null);
            item.put("detection_enabled", false);
            item.put("learning_enabled", false);
            item.put("last_seen_ms", null);
            out.add(item);
        }
        out.sort(java.util.Comparator.comparing(map -> String.valueOf(map.get("name"))));
        return out;
    }

    private Map<String, Object> safeSettings() {
        boolean detection = false;
        boolean learning = false;
        try {
            if (statusRepository != null) {
                long now = System.currentTimeMillis();
                long staleMs = plugin.getConfiguration().getLong("SettingsWeb.Api.server_status_stale_ms", 60_000L);
                for (ServerStatusRepository.Status status : statusRepository.list()) {
                    if (status.lastSeenMs() <= 0L || now - status.lastSeenMs() > staleMs) continue;
                    detection |= status.detectionEnabled();
                    learning |= status.learningEnabled();
                }
            }
        } catch (Exception ignored) {
        }
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("detection", Map.of("enabled", detection, "observe_only", true));
        data.put("learning", Map.of("enabled", learning));
        data.put("replays", Map.of("enabled", true));
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
        Configuration section = plugin.getConfiguration().getSection("SettingsWeb.Api.keys");
        if (section == null) return Map.of();
        HashMap<String, String> keys = new HashMap<>();
        for (String key : section.getKeys()) {
            String value = plugin.getConfiguration().getString("SettingsWeb.Api.keys." + key);
            if (key != null && value != null) keys.put(key, value);
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
