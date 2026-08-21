package me.hackerguardian.api;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import me.hackerguardian.api.reports.ReportsApiHandler;
import me.hackerguardian.bungee.HackerGuardianB;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class HgApiServerProxy {
    private final HackerGuardianB plugin;
    private HttpServer server;
    private ExecutorService executor;
    private HgApiAuth auth;
    private ReportsApiHandler reportsHandler;

    public HgApiServerProxy(HackerGuardianB plugin) {
        this.plugin = plugin;
    }

    public synchronized void startIfEnabled() {
        if (server != null) {
            plugin.getLogger().warning("[HG-API] API is already running");
            return;
        }

        boolean useWebsite = plugin.getConfiguration().getBoolean("Settings.UseWebsiteFunction", false);
        if (!useWebsite) {
            plugin.getLogger().info("[HG-API] Not starting (UseWebsiteFunction=false)");
            return;
        }

        boolean enabled = plugin.getConfiguration().getBoolean("SettingsWeb.Api.enabled", false);
        if (!enabled) {
            plugin.getLogger().info("[HG-API] Not starting (SettingsWeb.Api.enabled=false)");
            return;
        }

        String host = plugin.getConfiguration().getString("SettingsWeb.Api.bind_host", "127.0.0.1");
        int port = plugin.getConfiguration().getInt("SettingsWeb.Api.bind_port", 8787);
        boolean requireAuth = plugin.getConfiguration().getBoolean("SettingsWeb.Api.require_auth", true);
        long skew = plugin.getConfiguration().getLong("SettingsWeb.Api.allowed_skew_ms", 90000);
        long nonceTtl = plugin.getConfiguration().getLong("SettingsWeb.Api.nonce_ttl_ms", 300000);

        Map<String, Object> keysSection = plugin.getConfiguration().getSection("SettingsWeb.Api.keys") == null
                ? Map.of()
                : plugin.getConfiguration().getSection("SettingsWeb.Api.keys").getKeys().stream()
                .collect(java.util.stream.Collectors.toMap(
                        k -> k,
                        k -> plugin.getConfiguration().getString("SettingsWeb.Api.keys." + k)
                ));

        java.util.HashMap<String, String> keys = new java.util.HashMap<>();
        for (Map.Entry<String, Object> e : keysSection.entrySet()) {
            keys.put(e.getKey(), String.valueOf(e.getValue()));
        }

        this.auth = new HgApiAuth(keys, requireAuth, skew, nonceTtl);
        this.reportsHandler = new ReportsApiHandler(auth, plugin.reportsRepo);

        try {
            server = HttpServer.create(new InetSocketAddress(host, port), 0);

            AtomicInteger threadNumber = new AtomicInteger();
            executor = Executors.newFixedThreadPool(4, runnable -> {
                Thread thread = new Thread(runnable, "HackerGuardian-ProxyApi-" + threadNumber.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);

            server.createContext("/v1/health", this::handleHealth);
            server.createContext("/v1/reports", this::handleReports);

            server.start();
            plugin.getLogger().info("[HG-API] Proxy API listening on " + host + ":" + port);
        } catch (Exception e) {
            plugin.getLogger().severe("[HG-API] Failed to start API: " + e.getMessage());
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

    private void handleReports(HttpExchange ex) {
        try {
            String method = ex.getRequestMethod();
            String fullPath = ex.getRequestURI().getPath();
            if (fullPath.length() > 1 && fullPath.endsWith("/")) {
                fullPath = fullPath.substring(0, fullPath.length() - 1);
            }
            Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());

            byte[] body = ex.getRequestBody().readAllBytes();
            String finalFullPath = fullPath;
            String finalMethod = method;

            ReportsApiHandler.Request req = new ReportsApiHandler.Request() {
                @Override public String method() { return finalMethod; }
                @Override public String path() { return finalFullPath; }
                @Override public byte[] bodyBytes() { return body; }

                @Override public String header(String name) {
                    if (name == null) return null;
                    return ex.getRequestHeaders().getFirst(name);
                }

                @Override public String query(String name) {
                    return query.get(name);
                }
            };

            ReportsApiHandler.Response resp = reportsHandler.handle(req);
            ex.getResponseHeaders().set("Content-Type", resp.contentType);

            if ("OPTIONS".equalsIgnoreCase(method)) {
                ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                ex.getResponseHeaders().set("Access-Control-Allow-Headers",
                        "Content-Type, X-HG-KEY, X-HG-TS, X-HG-NONCE, X-HG-SIG");
                ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
                ex.sendResponseHeaders(204, -1);
                ex.close();
                return;
            }

            ex.sendResponseHeaders(resp.status, resp.body.length);
            ex.getResponseBody().write(resp.body);
            ex.close();

        } catch (Exception e) {
            plugin.getLogger().warning("[HG-API] /v1/reports crashed: " + e.getMessage());

            try {
                byte[] out = ("{\"ok\":false,\"message\":\"server error\"}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                ex.sendResponseHeaders(500, out.length);
                ex.getResponseBody().write(out);
                ex.close();
            } catch (Exception ignored) {}
        }
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
                    h.getFirst("x-hg-key"),
                    h.getFirst("x-hg-ts"),
                    h.getFirst("x-hg-nonce"),
                    h.getFirst("x-hg-sig")
            );
            if (!ar.ok()) {
                writeJson(ex, ar.status(), HgApiJson.obj("ok", false, "error", ar.message()));
                return;
            }

            boolean behindProxy = plugin.getConfiguration().getBoolean("Settings.behind_proxy", true);

            writeJson(ex, 200, HgApiJson.obj(
                    "ok", true,
                    "role", "proxy",
                    "behindProxy", behindProxy,
                    "time", System.currentTimeMillis(),
                    "playersOnline", plugin.getProxy().getOnlineCount(),
                    "servers", String.join(",", plugin.getProxy().getServers().keySet()),
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
        } catch (Exception ignored) {}
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> out = new java.util.HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return out;

        for (String part : rawQuery.split("&")) {
            int i = part.indexOf('=');
            String k = i >= 0 ? part.substring(0, i) : part;
            String v = i >= 0 ? part.substring(i + 1) : "";

            try {
                k = java.net.URLDecoder.decode(k, java.nio.charset.StandardCharsets.UTF_8);
                v = java.net.URLDecoder.decode(v, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception ignored) {}

            out.put(k, v);
        }
        return out;
    }
}
