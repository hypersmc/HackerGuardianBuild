package me.hackerguardian.api.status;

import com.sun.net.httpserver.HttpExchange;
import me.hackerguardian.api.HgApiAuth;
import me.hackerguardian.api.HgApiHttp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** GET /v1/servers backed by secret-free SQL heartbeats from Paper servers. */
public final class ServerStatusApiHandler {

    private final HgApiAuth auth;
    private final ServerStatusRepository repository;
    private final long staleAfterMs;
    private final String onlyServer;

    public ServerStatusApiHandler(HgApiAuth auth,
                                  ServerStatusRepository repository,
                                  long staleAfterMs,
                                  String onlyServer) {
        this.auth = auth;
        this.repository = repository;
        this.staleAfterMs = Math.max(10_000L, staleAfterMs);
        this.onlyServer = onlyServer;
    }

    public void handle(HttpExchange exchange) {
        try {
            HgApiHttp.AuthenticatedRequest request = HgApiHttp.authenticate(exchange, auth);
            if (!HgApiHttp.requireAuthenticated(exchange, request)) return;
            if (!HgApiHttp.requireGet(exchange, request)) return;

            long now = System.currentTimeMillis();
            List<Map<String, Object>> servers = new ArrayList<>();
            for (ServerStatusRepository.Status status : repository.list()) {
                if (onlyServer != null && !onlyServer.equals(status.serverName())) continue;
                boolean online = status.lastSeenMs() > 0L && now - status.lastSeenMs() <= staleAfterMs;
                LinkedHashMap<String, Object> item = new LinkedHashMap<>();
                item.put("name", status.serverName());
                item.put("online", online);
                item.put("players_online", online ? status.playersOnline() : 0);
                item.put("minecraft_version", status.minecraftVersion());
                item.put("plugin_version", status.pluginVersion());
                item.put("detection_enabled", status.detectionEnabled());
                item.put("learning_enabled", status.learningEnabled());
                item.put("synthetic_probes", status.syntheticProbes());
                item.put("last_seen_ms", status.lastSeenMs() > 0L ? status.lastSeenMs() : null);
                servers.add(item);
            }
            HgApiHttp.writeOk(exchange, 200, Map.of("servers", servers));
        } catch (java.io.IOException tooLarge) {
            HgApiHttp.writeError(exchange, 413, "REQUEST_TOO_LARGE", tooLarge.getMessage());
        } catch (Exception e) {
            HgApiHttp.writeError(exchange, 500, "INTERNAL_ERROR", "Unable to read server status");
        }
    }
}
