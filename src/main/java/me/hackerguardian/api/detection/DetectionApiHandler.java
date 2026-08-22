package me.hackerguardian.api.detection;

import com.sun.net.httpserver.HttpExchange;
import me.hackerguardian.api.HgApiAuth;
import me.hackerguardian.api.HgApiHttp;
import me.hackerguardian.api.replays.ReplayApiRepository;
import me.hackerguardian.api.status.ServerStatusRepository;
import me.hackerguardian.main.detection.DetectionRuntime;
import me.hackerguardian.main.detection.journal.DetectionEventRepository;
import me.hackerguardian.main.detection.learning.LearningPlayerState;
import me.hackerguardian.main.detection.learning.LearningRuntime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Read-only Detection v2 API for both standalone Paper and the proxy aggregator. */
public final class DetectionApiHandler {

    private static final String BASE = "/v1/detection";

    private final HgApiAuth auth;
    private final DetectionEventRepository events;
    private final ReplayApiRepository replays;
    private final ServerStatusRepository serverStatus;
    private final DetectionRuntime directRuntime;
    private final String serverScope;
    private final long staleAfterMs;

    public DetectionApiHandler(HgApiAuth auth,
                               DetectionEventRepository events,
                               ReplayApiRepository replays,
                               ServerStatusRepository serverStatus,
                               DetectionRuntime directRuntime,
                               String serverScope,
                               long staleAfterMs) {
        this.auth = auth;
        this.events = events;
        this.replays = replays;
        this.serverStatus = serverStatus;
        this.directRuntime = directRuntime;
        this.serverScope = serverScope;
        this.staleAfterMs = Math.max(10_000L, staleAfterMs);
    }

    public void handle(HttpExchange exchange) {
        try {
            HgApiHttp.AuthenticatedRequest request = HgApiHttp.authenticate(exchange, auth);
            if (!HgApiHttp.requireAuthenticated(exchange, request)) return;
            if (!HgApiHttp.requireGet(exchange, request)) return;

            String path = request.path();
            if ((BASE + "/status").equals(path)) {
                status(exchange);
                return;
            }
            if ((BASE + "/recent").equals(path)) {
                recent(exchange, request);
                return;
            }
            if (path.startsWith(BASE + "/player/")) {
                String uuid = path.substring((BASE + "/player/").length());
                player(exchange, uuid);
                return;
            }
            HgApiHttp.writeError(exchange, 404, "NOT_FOUND", "Detection route not found");
        } catch (java.io.IOException tooLarge) {
            HgApiHttp.writeError(exchange, 413, "REQUEST_TOO_LARGE", tooLarge.getMessage());
        } catch (Exception e) {
            HgApiHttp.writeError(exchange, 500, "INTERNAL_ERROR", "Detection API request failed");
        }
    }

    private void status(HttpExchange exchange) throws Exception {
        if (directRuntime != null) {
            HgApiHttp.writeOk(exchange, 200, directStatus());
            return;
        }
        HgApiHttp.writeOk(exchange, 200, aggregateStatus());
    }

    private Map<String, Object> directStatus() {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", directRuntime.isRunning());
        data.put("mode", "OBSERVE_ONLY");
        data.put("assessment_window_ms", directRuntime.getCollector().getWindowMs());
        data.put("tracked_players", directRuntime.getCollector().getTrackedPlayerCount());
        data.put("detectors", directDetectors());

        LinkedHashMap<String, Object> ml = new LinkedHashMap<>();
        ml.put("supervised_enabled", directRuntime.getMlDetector() != null);
        ml.put("supervised_model_loaded", directRuntime.getMlDetector() != null && directRuntime.getMlDetector().isLoaded());
        ml.put("population_normality_enabled", directRuntime.getNormalityDetector() != null);
        ml.put("population_model_loaded", directRuntime.getNormalityDetector() != null && directRuntime.getNormalityDetector().isLoaded());
        data.put("ml", ml);
        data.put("learning", directLearningSummary());

        if (directRuntime.getEventJournal() != null) {
            data.put("journal", Map.of(
                    "enabled", true,
                    "written_events", directRuntime.getEventJournal().getWrittenEvents(),
                    "dropped_events", directRuntime.getEventJournal().getDroppedEvents()
            ));
        } else {
            data.put("journal", Map.of("enabled", false));
        }
        return data;
    }

    private List<Map<String, Object>> directDetectors() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String id : directRuntime.getEngine().getDetectorIds()) {
            String type = "snapshot";
            boolean loaded = true;
            if (directRuntime.getMlDetector() != null && id.equals(directRuntime.getMlDetector().id())) {
                type = "ml";
                loaded = directRuntime.getMlDetector().isLoaded();
            } else if (directRuntime.getNormalityDetector() != null && id.equals(directRuntime.getNormalityDetector().id())) {
                type = "normality";
                loaded = directRuntime.getNormalityDetector().isLoaded();
            }
            LinkedHashMap<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("type", type);
            item.put("enabled", true);
            if ("ml".equals(type) || "normality".equals(type)) item.put("model_loaded", loaded);
            out.add(item);
        }
        for (String id : directRuntime.getDeterministicRuntime().getCheckIds()) {
            out.add(Map.of("id", id, "type", "deterministic", "enabled", true));
        }
        return out;
    }

    private Map<String, Object> aggregateStatus() throws Exception {
        long now = System.currentTimeMillis();
        boolean enabled = false;
        int tracked = 0;
        int trusted = 0;
        int activeProbes = 0;
        boolean learningEnabled = false;
        boolean supervisedEnabled = false;
        boolean supervisedLoaded = false;
        boolean normalityEnabled = false;
        boolean normalityLoaded = false;
        LinkedHashMap<String, Map<String, Object>> detectors = new LinkedHashMap<>();
        List<Map<String, Object>> servers = new ArrayList<>();

        for (ServerStatusRepository.Status status : serverStatus.list()) {
            if (status.lastSeenMs() <= 0L || now - status.lastSeenMs() > staleAfterMs) continue;
            enabled |= status.detectionEnabled();
            tracked += status.trackedPlayers();
            trusted += status.trustedPlayers();
            activeProbes += status.activeProbes();
            learningEnabled |= status.learningEnabled();

            for (TypedDetector typed : parseTypedDetectors(status.detectorIds())) {
                LinkedHashMap<String, Object> detector = new LinkedHashMap<>();
                detector.put("id", typed.id());
                detector.put("type", typed.publicType());
                detector.put("enabled", true);
                if (typed.model()) detector.put("model_loaded", typed.loaded());
                detectors.putIfAbsent(typed.publicType() + "\n" + typed.id(), detector);
                if ("ml".equals(typed.publicType())) {
                    supervisedEnabled = true;
                    supervisedLoaded |= typed.loaded();
                }
                if ("normality".equals(typed.publicType())) {
                    normalityEnabled = true;
                    normalityLoaded |= typed.loaded();
                }
            }

            servers.add(Map.of(
                    "name", status.serverName(),
                    "tracked_players", status.trackedPlayers(),
                    "detection_enabled", status.detectionEnabled(),
                    "learning_enabled", status.learningEnabled()
            ));
        }

        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", enabled);
        data.put("mode", "OBSERVE_ONLY");
        data.put("assessment_window_ms", null);
        data.put("tracked_players", tracked);
        data.put("detectors", new ArrayList<>(detectors.values()));
        data.put("ml", Map.of(
                "supervised_enabled", supervisedEnabled,
                "supervised_model_loaded", supervisedLoaded,
                "population_normality_enabled", normalityEnabled,
                "population_model_loaded", normalityLoaded
        ));
        data.put("learning", Map.of(
                "enabled", learningEnabled,
                "trusted_players", trusted,
                "active_probes", activeProbes
        ));
        data.put("servers", servers);
        return data;
    }

    private void recent(HttpExchange exchange, HgApiHttp.AuthenticatedRequest request) throws Exception {
        int limit = positiveInt(request.query("limit"), 100, 500);
        Long before = optionalLong(request.query("before"));
        String playerUuid = request.query("player_uuid");
        if (playerUuid != null && !playerUuid.isBlank() && !validUuid(playerUuid)) {
            HgApiHttp.writeError(exchange, 400, "INVALID_PLAYER_UUID", "Invalid player UUID");
            return;
        }
        String detector = request.query("detector");

        List<Map<String, Object>> items = new ArrayList<>();
        for (DetectionEventRepository.Event event : events.recent(serverScope, playerUuid, detector, before, limit)) {
            items.add(eventMap(event));
        }
        HgApiHttp.writeOk(exchange, 200, Map.of("events", items));
    }

    private void player(HttpExchange exchange, String playerUuid) throws Exception {
        if (!validUuid(playerUuid)) {
            HgApiHttp.writeError(exchange, 400, "INVALID_PLAYER_UUID", "Invalid player UUID");
            return;
        }

        List<DetectionEventRepository.Event> findings = events.recent(serverScope, playerUuid, null, null, 100);
        ReplayApiRepository.Filters replayFilters = new ReplayApiRepository.Filters();
        replayFilters.playerUuid = playerUuid;
        replayFilters.server = serverScope;
        replayFilters.page = 1;
        replayFilters.perPage = 10;
        ReplayApiRepository.Page replayPage = replays.list(replayFilters);

        String playerName = findings.isEmpty() ? null : findings.get(0).playerName();
        if (playerName == null && !replayPage.replays().isEmpty()) playerName = replayPage.replays().get(0).playerName();

        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        LinkedHashMap<String, Object> player = new LinkedHashMap<>();
        player.put("uuid", playerUuid);
        player.put("name", playerName);
        data.put("player", player);

        if (findings.isEmpty()) {
            data.put("latest_assessment", null);
        } else {
            DetectionEventRepository.Event latest = findings.get(0);
            data.put("latest_assessment", Map.of(
                    "time_ms", latest.timeMs(),
                    "risk", latest.assessmentRisk(),
                    "reliability", latest.assessmentReliability()
            ));
        }

        List<Map<String, Object>> findingMaps = new ArrayList<>();
        for (DetectionEventRepository.Event event : findings) findingMaps.add(eventMap(event));
        data.put("recent_findings", findingMaps);
        data.put("learning", directLearningPlayer(playerUuid));
        data.put("normality", directNormalitySummary());

        List<Map<String, Object>> replayMaps = new ArrayList<>();
        for (ReplayApiRepository.ReplayRecord replay : replayPage.replays()) {
            LinkedHashMap<String, Object> item = new LinkedHashMap<>();
            item.put("id", replay.id());
            item.put("server_name", replay.serverName());
            item.put("started_at", replay.startedAt());
            item.put("ended_at", replay.endedAt());
            item.put("duration_ms", replay.durationMs());
            item.put("trigger_type", replay.triggerType());
            replayMaps.add(item);
        }
        data.put("recent_replays", replayMaps);
        HgApiHttp.writeOk(exchange, 200, data);
    }

    private Map<String, Object> directLearningSummary() {
        LearningRuntime learning = directRuntime.getLearningRuntime();
        int trusted = 0;
        double hours = 0.0;
        for (LearningPlayerState state : learning.getStates()) {
            LearningPlayerState.Snapshot snapshot = state.snapshot();
            if (snapshot.isTrustedLastSeen()) trusted++;
            hours += snapshot.getCollectedHours();
        }
        int activeProbes = learning.getProbeEngine() == null ? 0 : learning.getProbeEngine().getActiveProbeCount();
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", learning.isEnabled());
        out.put("trusted_players", trusted);
        out.put("total_active_hours", hours);
        out.put("active_probes", activeProbes);
        return out;
    }

    private Object directLearningPlayer(String playerUuid) {
        if (directRuntime == null) return null;
        UUID uuid = UUID.fromString(playerUuid);
        LearningRuntime learning = directRuntime.getLearningRuntime();
        for (LearningPlayerState state : learning.getStates()) {
            LearningPlayerState.Snapshot snapshot = state.snapshot();
            if (!uuid.equals(snapshot.getPlayerId())) continue;
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            out.put("trusted", snapshot.isTrustedLastSeen());
            out.put("active_hours", snapshot.getCollectedHours());
            out.put("first_trusted_ms", snapshot.getFirstTrustedMs());
            out.put("last_seen_ms", snapshot.getLastSeenMs());
            out.put("last_probe_ms", snapshot.getLastProbeMs());
            out.put("baseline_mature", snapshot.getCollectedMs() >= learning.getMinimumBaselineHours() * 3_600_000.0);
            return out;
        }
        return null;
    }

    private Object directNormalitySummary() {
        if (directRuntime == null || directRuntime.getNormalityDetector() == null) return null;
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", true);
        out.put("loaded", directRuntime.getNormalityDetector().isLoaded());
        if (directRuntime.getNormalityDetector().isLoaded()) {
            out.put("model_id", directRuntime.getNormalityDetector().getModel().getModelId());
            out.put("schema", directRuntime.getNormalityDetector().getModel().getSchemaId());
        }
        return out;
    }

    private static Map<String, Object> eventMap(DetectionEventRepository.Event event) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("id", event.eventId());
        out.put("time_ms", event.timeMs());
        out.put("player_uuid", event.playerUuid());
        out.put("player_name", event.playerName());
        out.put("server_name", event.serverName());
        out.put("detector", event.detectorId());
        out.put("category", event.category());
        out.put("evidence_strength", event.evidenceStrength());
        out.put("score", event.score());
        out.put("reliability", event.reliability());
        out.put("assessment_risk", event.assessmentRisk());
        out.put("metadata", event.metadata());
        out.put("replay_id", event.replayId());
        return out;
    }

    private static List<TypedDetector> parseTypedDetectors(String encoded) {
        if (encoded == null || encoded.isBlank()) return List.of();
        List<TypedDetector> out = new ArrayList<>();
        for (String token : encoded.split(",")) {
            int split = token.indexOf('|');
            if (split <= 0 || split == token.length() - 1) {
                out.add(new TypedDetector("snapshot", token, true));
                continue;
            }
            String type = token.substring(0, split);
            String id = token.substring(split + 1);
            boolean loaded = !type.endsWith("_unavailable");
            String publicType = type.replace("_loaded", "").replace("_unavailable", "");
            out.add(new TypedDetector(publicType, id, loaded));
        }
        return out;
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

    private static int positiveInt(String value, int fallback, int max) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? Math.min(parsed, max) : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private record TypedDetector(String publicType, String id, boolean loaded) {
        boolean model() { return "ml".equals(publicType) || "normality".equals(publicType); }
    }
}
