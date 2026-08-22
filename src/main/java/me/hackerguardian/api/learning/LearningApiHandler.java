package me.hackerguardian.api.learning;

import com.sun.net.httpserver.HttpExchange;
import me.hackerguardian.api.HgApiAuth;
import me.hackerguardian.api.HgApiHttp;
import me.hackerguardian.api.status.ServerStatusRepository;
import me.hackerguardian.main.detection.DetectionRuntime;
import me.hackerguardian.main.detection.learning.LearningRuntime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only Learning Mode administration surface for Laravel. */
public final class LearningApiHandler {

    private static final String BASE = "/v1/learning";

    private final HgApiAuth auth;
    private final LearningPlayerStatusRepository players;
    private final ServerStatusRepository servers;
    private final DetectionRuntime directRuntime;
    private final String serverScope;
    private final long staleAfterMs;

    public LearningApiHandler(HgApiAuth auth,
                              LearningPlayerStatusRepository players,
                              ServerStatusRepository servers,
                              DetectionRuntime directRuntime,
                              String serverScope,
                              long staleAfterMs) {
        this.auth = auth;
        this.players = players;
        this.servers = servers;
        this.directRuntime = directRuntime;
        this.serverScope = serverScope;
        this.staleAfterMs = Math.max(10_000L, staleAfterMs);
    }

    public void handle(HttpExchange exchange) {
        try {
            HgApiHttp.AuthenticatedRequest request = HgApiHttp.authenticate(exchange, auth);
            if (!HgApiHttp.requireAuthenticated(exchange, request)) return;
            if (!HgApiHttp.requireGet(exchange, request)) return;

            if ((BASE + "/status").equals(request.path())) {
                status(exchange);
                return;
            }
            if ((BASE + "/players").equals(request.path())) {
                listPlayers(exchange, request);
                return;
            }
            HgApiHttp.writeError(exchange, 404, "NOT_FOUND", "Learning route not found");
        } catch (java.io.IOException tooLarge) {
            HgApiHttp.writeError(exchange, 413, "REQUEST_TOO_LARGE", tooLarge.getMessage());
        } catch (Exception e) {
            HgApiHttp.writeError(exchange, 500, "INTERNAL_ERROR", "Learning API request failed");
        }
    }

    private void status(HttpExchange exchange) throws Exception {
        long now = System.currentTimeMillis();
        boolean enabled = false;
        int trustedPlayers = 0;
        double totalHours = 0.0;
        int activeProbes = 0;
        boolean probesEnabled = false;
        boolean modelLoaded = false;

        for (ServerStatusRepository.Status status : servers.list()) {
            if (serverScope != null && !serverScope.equals(status.serverName())) continue;
            if (status.lastSeenMs() <= 0L || now - status.lastSeenMs() > staleAfterMs) continue;
            enabled |= status.learningEnabled();
            trustedPlayers += status.trustedPlayers();
            totalHours += status.learningActiveHours();
            activeProbes += status.activeProbes();
            probesEnabled |= status.syntheticProbes();
            if (status.detectorIds() != null && status.detectorIds().contains("normality_loaded|")) {
                modelLoaded = true;
            }
        }

        LinkedHashMap<String, Object> model = new LinkedHashMap<>();
        model.put("loaded", modelLoaded);
        model.put("schema", null);
        model.put("trained_players", null);
        model.put("trained_samples", null);
        model.put("trained_at", null);

        Long candidateSessionRows = null;
        if (directRuntime != null) {
            LearningRuntime learning = directRuntime.getLearningRuntime();
            enabled = learning.isEnabled();
            trustedPlayers = 0;
            totalHours = 0.0;
            for (var state : learning.getStates()) {
                var snapshot = state.snapshot();
                if (snapshot.isTrustedLastSeen()) trustedPlayers++;
                totalHours += snapshot.getCollectedHours();
            }
            activeProbes = learning.getProbeEngine() == null ? 0 : learning.getProbeEngine().getActiveProbeCount();
            probesEnabled = learning.getProbeEngine() != null && learning.getProbeEngine().isEnabled();
            candidateSessionRows = learning.getDatasetRecorder() == null
                    ? null : learning.getDatasetRecorder().getWrittenRows();

            if (directRuntime.getNormalityDetector() != null) {
                modelLoaded = directRuntime.getNormalityDetector().isLoaded();
                model.put("loaded", modelLoaded);
                if (modelLoaded) {
                    var normality = directRuntime.getNormalityDetector().getModel();
                    model.put("schema", normality.getSchemaId());
                    model.put("trained_players", normality.getTrainingPlayers());
                    model.put("trained_samples", normality.getTrainingSamples());
                }
            }
        }

        LinkedHashMap<String, Object> probes = new LinkedHashMap<>();
        probes.put("enabled", probesEnabled);
        probes.put("active", activeProbes);
        probes.put("completed", null);

        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", enabled);
        // Candidate/eligible totals are intentionally null until counters can be
        // maintained without scanning a potentially multi-million-row CSV on an API request.
        data.put("candidate_rows", null);
        data.put("eligible_rows", null);
        data.put("candidate_rows_this_process", candidateSessionRows);
        data.put("trusted_players", trustedPlayers);
        data.put("total_active_hours", totalHours);
        data.put("model", model);
        data.put("probes", probes);
        data.put("metrics_note", "candidate_rows/eligible_rows/completed probes require persistent aggregate counters and are not guessed from partial runtime state");
        HgApiHttp.writeOk(exchange, 200, data);
    }

    private void listPlayers(HttpExchange exchange, HgApiHttp.AuthenticatedRequest request) throws Exception {
        boolean trustedOnly = !"false".equalsIgnoreCase(request.query("trusted_only"));
        int limit = positiveInt(request.query("limit"), 500, 5000);
        List<Map<String, Object>> out = new ArrayList<>();
        for (LearningPlayerStatusRepository.PlayerStatus player : players.list(serverScope, trustedOnly, limit)) {
            LinkedHashMap<String, Object> item = new LinkedHashMap<>();
            item.put("server_name", player.serverName());
            item.put("uuid", player.playerUuid());
            item.put("name", player.playerName());
            item.put("trusted", player.trusted());
            item.put("active_hours", player.activeHours());
            item.put("candidate_samples", null);
            item.put("eligible_samples", null);
            // Eligibility is tracked per captured row, not as one player-wide date.
            item.put("quarantine_until", null);
            item.put("baseline_mature", player.baselineMature());
            item.put("first_trusted", player.firstTrustedMs() > 0L ? player.firstTrustedMs() : null);
            item.put("last_seen", player.lastSeenMs() > 0L ? player.lastSeenMs() : null);
            item.put("last_probe", player.lastProbeMs() > 0L ? player.lastProbeMs() : null);
            out.add(item);
        }
        HgApiHttp.writeOk(exchange, 200, Map.of("players", out));
    }

    private static int positiveInt(String value, int fallback, int max) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? Math.min(parsed, max) : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
