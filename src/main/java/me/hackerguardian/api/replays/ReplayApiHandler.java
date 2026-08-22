package me.hackerguardian.api.replays;

import com.sun.net.httpserver.HttpExchange;
import me.hackerguardian.api.HgApiAuth;
import me.hackerguardian.api.HgApiHttp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Public read-only replay routes consumed by the Laravel web backend. */
public final class ReplayApiHandler {

    private static final String BASE = "/v1/replays";

    private final HgApiAuth auth;
    private final ReplayApiRepository repository;

    public ReplayApiHandler(HgApiAuth auth, ReplayApiRepository repository) {
        this.auth = auth;
        this.repository = repository;
    }

    public void handle(HttpExchange exchange) {
        try {
            HgApiHttp.AuthenticatedRequest request = HgApiHttp.authenticate(exchange, auth);
            if (!HgApiHttp.requireAuthenticated(exchange, request)) return;
            if (!HgApiHttp.requireGet(exchange, request)) return;

            String path = request.path();
            if (BASE.equals(path)) {
                list(exchange, request);
                return;
            }
            if (!path.startsWith(BASE + "/")) {
                HgApiHttp.writeError(exchange, 404, "NOT_FOUND", "Replay route not found");
                return;
            }

            String[] parts = path.substring((BASE + "/").length()).split("/");
            long replayId = positiveLong(parts.length > 0 ? parts[0] : null);
            if (replayId <= 0) {
                HgApiHttp.writeError(exchange, 400, "INVALID_REPLAY_ID", "Invalid replay id");
                return;
            }

            if (parts.length == 1) {
                manifest(exchange, replayId);
                return;
            }
            if (parts.length == 3 && "chunks".equals(parts[1])) {
                int seq = nonNegativeInt(parts[2]);
                if (seq < 0) {
                    HgApiHttp.writeError(exchange, 400, "INVALID_CHUNK_SEQUENCE", "Invalid replay chunk sequence");
                    return;
                }
                chunk(exchange, replayId, seq);
                return;
            }
            if (parts.length == 2 && "world".equals(parts[1])) {
                world(exchange, replayId);
                return;
            }
            if (parts.length == 5 && "world".equals(parts[1]) && "chunks".equals(parts[2])) {
                Integer chunkX = integer(parts[3]);
                Integer chunkZ = integer(parts[4]);
                if (chunkX == null || chunkZ == null) {
                    HgApiHttp.writeError(exchange, 400, "INVALID_WORLD_CHUNK", "Invalid world chunk coordinates");
                    return;
                }
                worldChunk(exchange, request, replayId, chunkX, chunkZ);
                return;
            }

            HgApiHttp.writeError(exchange, 404, "NOT_FOUND", "Replay route not found");
        } catch (java.io.IOException tooLarge) {
            HgApiHttp.writeError(exchange, 413, "REQUEST_TOO_LARGE", tooLarge.getMessage());
        } catch (Exception e) {
            HgApiHttp.writeError(exchange, 500, "INTERNAL_ERROR", "Replay API request failed");
        }
    }

    private void list(HttpExchange exchange, HgApiHttp.AuthenticatedRequest request) throws Exception {
        ReplayApiRepository.Filters filters = new ReplayApiRepository.Filters();
        filters.playerUuid = request.query("player_uuid");
        filters.playerName = request.query("player_name");
        filters.server = request.query("server");
        filters.trigger = request.query("trigger");
        filters.fromMs = optionalLong(request.query("from"));
        filters.toMs = optionalLong(request.query("to"));
        filters.page = positiveInt(request.query("page"), 1);
        filters.perPage = positiveInt(request.query("per_page"), 25);

        ReplayApiRepository.Page page = repository.list(filters);
        List<Map<String, Object>> items = new ArrayList<>();
        for (ReplayApiRepository.ReplayRecord replay : page.replays()) items.add(summary(replay));

        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("page", page.page());
        data.put("per_page", page.perPage());
        data.put("total", page.total());
        data.put("pages", page.pages());
        data.put("replays", items);
        HgApiHttp.writeOk(exchange, 200, data);
    }

    private void manifest(HttpExchange exchange, long replayId) throws Exception {
        ReplayApiRepository.ReplayRecord replay = repository.get(replayId);
        if (replay == null) {
            HgApiHttp.writeError(exchange, 404, "NOT_FOUND", "Replay not found");
            return;
        }

        long origin = replay.captureStartMs() == null ? replay.startedAt() : replay.captureStartMs();
        List<ReplayApiRepository.ChunkMeta> chunkRows = repository.chunks(replayId);
        List<Map<String, Object>> chunks = new ArrayList<>();
        for (ReplayApiRepository.ChunkMeta chunk : chunkRows) {
            chunks.add(Map.of(
                    "seq", chunk.seq(),
                    "start_ms", Math.max(0L, chunk.startMs() - origin),
                    "end_ms", Math.max(0L, chunk.endMs() - origin),
                    "size_bytes", chunk.sizeBytes()
            ));
        }

        String world = null;
        if (!chunkRows.isEmpty()) {
            ReplayApiRepository.ChunkData first = repository.chunk(replayId, chunkRows.get(0).seq());
            if (first != null) world = ReplayWebDecoder.firstWorld(replay, first);
        }

        LinkedHashMap<String, Object> data = new LinkedHashMap<>(summary(replay));
        data.put("capture_start_at", replay.captureStartMs());
        data.put("capture_end_at", replay.captureEndMs());
        data.put("trigger_offset_ms", replay.triggerOffsetMs());
        data.put("world", world == null ? null : Map.of("name", world));
        data.put("chunks", chunks);
        data.put("world_snapshot", worldManifest(repository.worldChunks(replayId), replay.triggerOffsetMs()));

        LinkedHashMap<String, Object> triggerEvent = new LinkedHashMap<>();
        triggerEvent.put("time_ms", replay.triggerOffsetMs());
        triggerEvent.put("type", "TRIGGER");
        triggerEvent.put("trigger_type", replay.triggerType());
        triggerEvent.put("metadata", parseTriggerMeta(replay.triggerMeta()));
        if (replay.aiScore() != null) triggerEvent.put("score", replay.aiScore());
        data.put("events", List.of(triggerEvent));

        HgApiHttp.writeOk(exchange, 200, data);
    }

    private void chunk(HttpExchange exchange, long replayId, int seq) throws Exception {
        ReplayApiRepository.ReplayRecord replay = repository.get(replayId);
        if (replay == null) {
            HgApiHttp.writeError(exchange, 404, "NOT_FOUND", "Replay not found");
            return;
        }
        ReplayApiRepository.ChunkData chunk = repository.chunk(replayId, seq);
        if (chunk == null) {
            HgApiHttp.writeError(exchange, 404, "NOT_FOUND", "Replay chunk not found");
            return;
        }

        ReplayWebDecoder.DecodedChunk decoded = ReplayWebDecoder.decode(replay, chunk);
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("replay_id", replayId);
        data.put("seq", decoded.seq());
        data.put("start_ms", decoded.startMs());
        data.put("end_ms", decoded.endMs());
        data.put("format", "hg-web-replay-v1");
        data.put("frames", decoded.frames());
        HgApiHttp.writeOk(exchange, 200, data);
    }

    private void world(HttpExchange exchange, long replayId) throws Exception {
        ReplayApiRepository.ReplayRecord replay = repository.get(replayId);
        if (replay == null) {
            HgApiHttp.writeError(exchange, 404, "NOT_FOUND", "Replay not found");
            return;
        }

        List<ReplayApiRepository.WorldChunkMeta> chunks = repository.worldChunks(replayId);
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("replay_id", replayId);
        data.putAll(worldManifest(chunks, replay.triggerOffsetMs()));
        HgApiHttp.writeOk(exchange, 200, data);
    }

    private void worldChunk(HttpExchange exchange,
                            HgApiHttp.AuthenticatedRequest request,
                            long replayId,
                            int chunkX,
                            int chunkZ) throws Exception {
        ReplayApiRepository.ReplayRecord replay = repository.get(replayId);
        if (replay == null) {
            HgApiHttp.writeError(exchange, 404, "NOT_FOUND", "Replay not found");
            return;
        }

        String world = request.query("world");
        ReplayApiRepository.WorldChunkData stored = repository.worldChunk(replayId, world, chunkX, chunkZ);
        if (stored == null) {
            HgApiHttp.writeError(exchange, 404, "WORLD_CHUNK_NOT_FOUND", "Replay world chunk not found");
            return;
        }

        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("replay_id", replayId);
        data.put("format", "hg-web-world-v1");
        data.put("world", stored.world());
        data.put("chunk_x", stored.chunkX());
        data.put("chunk_z", stored.chunkZ());
        // Existing snapshot capture begins when the replay session becomes active,
        // which is around the trigger. Until capture timestamps are persisted per
        // world chunk, this is explicitly an estimated anchor rather than invented precision.
        data.put("anchor_ms", replay.triggerOffsetMs());
        data.putAll(ReplayWorldWebCodec.decode(stored.data()));
        HgApiHttp.writeOk(exchange, 200, data);
    }

    private static LinkedHashMap<String, Object> worldManifest(List<ReplayApiRepository.WorldChunkMeta> rows,
                                                                long anchorMs) {
        List<Map<String, Object>> chunks = new ArrayList<>();
        long bytes = 0L;
        for (ReplayApiRepository.WorldChunkMeta row : rows) {
            LinkedHashMap<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("world", row.world());
            chunk.put("chunk_x", row.chunkX());
            chunk.put("chunk_z", row.chunkZ());
            chunk.put("size_bytes", row.sizeBytes());
            chunks.add(chunk);
            bytes += Math.max(0, row.sizeBytes());
        }

        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("available", !rows.isEmpty());
        out.put("format", "hg-web-world-v1");
        out.put("anchor_ms", anchorMs);
        out.put("anchor_precision", "trigger_estimate");
        out.put("chunk_count", rows.size());
        out.put("size_bytes", bytes);
        out.put("chunks", chunks);
        return out;
    }

    private static Map<String, Object> summary(ReplayApiRepository.ReplayRecord replay) {
        LinkedHashMap<String, Object> item = new LinkedHashMap<>();
        item.put("id", replay.id());
        item.put("player_uuid", replay.playerUuid());
        item.put("player_name", replay.playerName());
        item.put("server_name", replay.serverName());
        item.put("started_at", replay.startedAt());
        item.put("ended_at", replay.endedAt());
        item.put("duration_ms", replay.durationMs());
        item.put("trigger_type", replay.triggerType());
        item.put("trigger_meta", parseTriggerMeta(replay.triggerMeta()));
        item.put("format_version", replay.formatVersion());
        item.put("codec", replay.codec());
        item.put("size_bytes", replay.sizeBytes());
        item.put("chunk_count", replay.chunkCount());
        return item;
    }

    private static Map<String, Object> parseTriggerMeta(String value) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        if (value == null || value.isBlank()) return out;
        for (String part : value.split(";")) {
            int equals = part.indexOf('=');
            if (equals <= 0) continue;
            String key = part.substring(0, equals).trim();
            String val = part.substring(equals + 1).trim();
            if (!key.isEmpty()) out.put(key, val);
        }
        if (out.isEmpty()) out.put("raw", value);
        return out;
    }

    private static Long optionalLong(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Long.parseLong(value); }
        catch (Exception ignored) { return null; }
    }

    private static long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : -1L;
        } catch (Exception ignored) {
            return -1L;
        }
    }

    private static int nonNegativeInt(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= 0 ? parsed : -1;
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static Integer integer(String value) {
        try { return Integer.parseInt(value); }
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
