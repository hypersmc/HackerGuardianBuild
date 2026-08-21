package me.hackerguardian.api;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class HgApiServerBackend {
    private final JavaPlugin plugin;
    private HttpServer server;
    private ExecutorService executor;
    private HgApiAuth auth;

    public HgApiServerBackend(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void startIfEnabled() {
        if (server != null) {
            plugin.getLogger().warning("[HG-API] API is already running");
            return;
        }

        boolean useWebsite = plugin.getConfig().getBoolean("Settings.UseWebsiteFunction", false);
        boolean behindProxy = plugin.getConfig().getBoolean("Settings.behind_proxy", false);

        if (!useWebsite || behindProxy) {
            plugin.getLogger().info("[HG-API] Not starting (UseWebsiteFunction=" + useWebsite
                    + ", behind_proxy=" + behindProxy + ")");
            return;
        }

        boolean enabled = plugin.getConfig().getBoolean("SettingsWeb.Api.enabled", false);
        if (!enabled) {
            plugin.getLogger().info("[HG-API] Not starting (Api.enabled=false)");
            return;
        }

        String host = plugin.getConfig().getString("SettingsWeb.Api.bind_host", "127.0.0.1");
        int port = plugin.getConfig().getInt("SettingsWeb.Api.bind_port", 8787);
        boolean requireAuth = plugin.getConfig().getBoolean("SettingsWeb.Api.require_auth", true);
        long skew = plugin.getConfig().getLong("SettingsWeb.Api.allowed_skew_ms", 90000);
        long nonceTtl = plugin.getConfig().getLong("SettingsWeb.Api.nonce_ttl_ms", 300000);

        Map<String, Object> keysSection = plugin.getConfig().getConfigurationSection("SettingsWeb.Api.keys") == null
                ? Map.of()
                : plugin.getConfig().getConfigurationSection("SettingsWeb.Api.keys").getValues(false);

        java.util.HashMap<String, String> keys = new java.util.HashMap<>();
        for (Map.Entry<String, Object> e : keysSection.entrySet()) {
            keys.put(e.getKey(), String.valueOf(e.getValue()));
        }

        this.auth = new HgApiAuth(keys, requireAuth, skew, nonceTtl);

        try {
            server = HttpServer.create(new InetSocketAddress(host, port), 0);

            AtomicInteger threadNumber = new AtomicInteger();
            executor = Executors.newFixedThreadPool(4, runnable -> {
                Thread thread = new Thread(runnable, "HackerGuardian-Api-" + threadNumber.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);

            server.createContext("/v1/health", this::handleHealth);
            server.start();
            plugin.getLogger().info("[HG-API] Paper API listening on " + host + ":" + port);
        } catch (Exception e) {
            plugin.getLogger().severe("[HG-API] Failed to start API: " + e.getMessage());
            if (plugin.getConfig().getBoolean("debug")) e.printStackTrace();
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

        if (wasRunning) plugin.getLogger().info("[HG-API] Stopped");
    }

    private void handleHealth(HttpExchange ex) {
        try {
            String method = ex.getRequestMethod();
            if (!"GET".equalsIgnoreCase(method)) {
                writeJson(ex, 405, HgApiJson.obj("ok", false, "error", "Method not allowed"));
                return;
            }

            byte[] body = readAll(ex.getRequestBody());
            String path = ex.getRequestURI().getPath();

            Headers h = ex.getRequestHeaders();
            HgApiAuth.AuthResult ar = auth.verify(
                    method,
                    path,
                    body,
                    h.getFirst("X-HG-KeyId"),
                    h.getFirst("X-HG-Timestamp"),
                    h.getFirst("X-HG-Nonce"),
                    h.getFirst("X-HG-Signature")
            );
            if (!ar.ok()) {
                writeJson(ex, ar.status(), HgApiJson.obj("ok", false, "error", ar.message()));
                return;
            }

            String serverName = plugin.getConfig().getString("Settings.server_name", "default");
            boolean behindProxy = plugin.getConfig().getBoolean("Settings.behind_proxy", false);

            writeJson(ex, 200, HgApiJson.obj(
                    "ok", true,
                    "role", "backend",
                    "serverName", serverName,
                    "behindProxy", behindProxy,
                    "time", System.currentTimeMillis(),
                    "playersOnline", Bukkit.getOnlinePlayers().size(),
                    "version", plugin.getDescription().getVersion()
            ));
        } catch (Exception e) {
            writeJson(ex, 500, HgApiJson.obj("ok", false, "error", "Internal error"));
        }
    }

    private static byte[] readAll(InputStream in) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) != -1) baos.write(buf, 0, r);
            return baos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static void writeJson(HttpExchange ex, int status, Map<String, Object> obj) {
        try {
            byte[] out = HgApiJson.json(obj);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        } catch (Exception ignored) {
        }
    }
}
