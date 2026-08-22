package me.hackerguardian.api.moderation;

import com.sun.net.httpserver.HttpExchange;
import me.hackerguardian.api.HgApiAuth;
import me.hackerguardian.api.HgApiHttp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** GET /v1/moderation/actions. */
public final class ModerationApiHandler {

    private static final String PATH = "/v1/moderation/actions";

    private final HgApiAuth auth;
    private final ModerationApiRepository repository;
    private final String serverScope;

    public ModerationApiHandler(HgApiAuth auth, ModerationApiRepository repository, String serverScope) {
        this.auth = auth;
        this.repository = repository;
        this.serverScope = serverScope;
    }

    public void handle(HttpExchange exchange) {
        try {
            HgApiHttp.AuthenticatedRequest request = HgApiHttp.authenticate(exchange, auth);
            if (!HgApiHttp.requireAuthenticated(exchange, request)) return;
            if (!HgApiHttp.requireGet(exchange, request)) return;
            if (!PATH.equals(request.path())) {
                HgApiHttp.writeError(exchange, 404, "NOT_FOUND", "Moderation route not found");
                return;
            }

            ModerationApiRepository.Filters filters = new ModerationApiRepository.Filters();
            filters.targetUuid = request.query("target_uuid");
            if (filters.targetUuid != null && !filters.targetUuid.isBlank() && !validUuid(filters.targetUuid)) {
                HgApiHttp.writeError(exchange, 400, "INVALID_PLAYER_UUID", "Invalid target UUID");
                return;
            }
            filters.type = request.query("type");
            filters.server = serverScope == null ? request.query("server") : serverScope;
            filters.fromMs = optionalLong(request.query("from"));
            filters.toMs = optionalLong(request.query("to"));
            filters.page = positiveInt(request.query("page"), 1);
            filters.perPage = positiveInt(request.query("per_page"), 25);

            ModerationApiRepository.Page page = repository.list(filters);
            List<Map<String, Object>> actions = new ArrayList<>();
            for (ModerationApiRepository.Action action : page.actions()) {
                LinkedHashMap<String, Object> item = new LinkedHashMap<>();
                item.put("id", action.id());
                item.put("server_name", action.serverName());
                item.put("target_uuid", action.targetUuid());
                item.put("target_name", action.targetName());
                item.put("type", action.type());
                item.put("reason", action.reason());
                item.put("actor_uuid", action.actorUuid());
                item.put("actor_name", action.actorName());
                item.put("created_at", action.createdAt());
                item.put("expires_at", action.expiresAt());
                item.put("active", action.active());
                item.put("scope", action.scope());
                item.put("linked_report_id", null);
                item.put("linked_replay_id", null);
                actions.add(item);
            }

            LinkedHashMap<String, Object> data = new LinkedHashMap<>();
            data.put("page", page.page());
            data.put("per_page", page.perPage());
            data.put("total", page.total());
            data.put("pages", page.pages());
            data.put("actions", actions);
            HgApiHttp.writeOk(exchange, 200, data);
        } catch (java.io.IOException tooLarge) {
            HgApiHttp.writeError(exchange, 413, "REQUEST_TOO_LARGE", tooLarge.getMessage());
        } catch (Exception e) {
            HgApiHttp.writeError(exchange, 500, "INTERNAL_ERROR", "Moderation API request failed");
        }
    }

    private static boolean validUuid(String value) {
        try { UUID.fromString(value); return true; }
        catch (Exception ignored) { return false; }
    }

    private static Long optionalLong(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Long.parseLong(value); }
        catch (Exception ignored) { return null; }
    }

    private static int positiveInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
