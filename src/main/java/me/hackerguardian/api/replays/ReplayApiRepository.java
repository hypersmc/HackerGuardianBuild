package me.hackerguardian.api.replays;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Read-only SQL projection for the stable web replay contract. */
public final class ReplayApiRepository {

    private final DataSource dataSource;

    public ReplayApiRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Page list(Filters filters) throws SQLException {
        Filters f = filters == null ? new Filters() : filters;
        int page = Math.max(1, f.page);
        int perPage = Math.max(1, Math.min(100, f.perPage));
        int offset = (page - 1) * perPage;

        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (notBlank(f.playerUuid)) {
            where.append(" AND r.player_uuid=?");
            params.add(f.playerUuid.trim());
        }
        if (notBlank(f.playerName)) {
            where.append(" AND LOWER(r.player_name) LIKE ?");
            params.add("%" + f.playerName.trim().toLowerCase(Locale.ROOT) + "%");
        }
        if (notBlank(f.server)) {
            where.append(" AND r.server_name=?");
            params.add(f.server.trim());
        }
        if (notBlank(f.trigger)) {
            where.append(" AND r.trigger_type=?");
            params.add(f.trigger.trim().toUpperCase(Locale.ROOT));
        }
        if (f.fromMs != null) {
            where.append(" AND r.started_at>=?");
            params.add(f.fromMs);
        }
        if (f.toMs != null) {
            where.append(" AND r.started_at<=?");
            params.add(f.toMs);
        }

        long total;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM hg_replays r" + where)) {
            bind(statement, params);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                total = result.getLong(1);
            }
        }

        String select = "SELECT r.id, r.player_uuid, r.player_name, r.server_name, r.started_at, r.ended_at, "
                + "r.trigger_type, r.trigger_meta, r.ai_score, r.format_version, r.codec, r.size_bytes, "
                + "(SELECT COUNT(*) FROM hg_replay_chunks c WHERE c.replay_id=r.id) AS chunk_count, "
                + "(SELECT MIN(c.start_ms) FROM hg_replay_chunks c WHERE c.replay_id=r.id) AS capture_start_ms, "
                + "(SELECT MAX(c.end_ms) FROM hg_replay_chunks c WHERE c.replay_id=r.id) AS capture_end_ms "
                + "FROM hg_replays r" + where + " ORDER BY r.started_at DESC LIMIT ? OFFSET ?";
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(perPage);
        pageParams.add(offset);

        List<ReplayRecord> records = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(select)) {
            bind(statement, pageParams);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) records.add(mapReplay(result));
            }
        }
        return new Page(page, perPage, total, records);
    }

    public ReplayRecord get(long replayId) throws SQLException {
        String sql = "SELECT r.id, r.player_uuid, r.player_name, r.server_name, r.started_at, r.ended_at, "
                + "r.trigger_type, r.trigger_meta, r.ai_score, r.format_version, r.codec, r.size_bytes, "
                + "(SELECT COUNT(*) FROM hg_replay_chunks c WHERE c.replay_id=r.id) AS chunk_count, "
                + "(SELECT MIN(c.start_ms) FROM hg_replay_chunks c WHERE c.replay_id=r.id) AS capture_start_ms, "
                + "(SELECT MAX(c.end_ms) FROM hg_replay_chunks c WHERE c.replay_id=r.id) AS capture_end_ms "
                + "FROM hg_replays r WHERE r.id=?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, replayId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? mapReplay(result) : null;
            }
        }
    }

    public List<ChunkMeta> chunks(long replayId) throws SQLException {
        List<ChunkMeta> chunks = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT seq, start_ms, end_ms, size_bytes FROM hg_replay_chunks WHERE replay_id=? ORDER BY seq ASC"
             )) {
            statement.setLong(1, replayId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    chunks.add(new ChunkMeta(
                            result.getInt("seq"),
                            result.getLong("start_ms"),
                            result.getLong("end_ms"),
                            result.getInt("size_bytes")
                    ));
                }
            }
        }
        return chunks;
    }

    public ChunkData chunk(long replayId, int seq) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT seq, start_ms, end_ms, size_bytes, data FROM hg_replay_chunks WHERE replay_id=? AND seq=?"
             )) {
            statement.setLong(1, replayId);
            statement.setInt(2, seq);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                return new ChunkData(
                        result.getInt("seq"),
                        result.getLong("start_ms"),
                        result.getLong("end_ms"),
                        result.getInt("size_bytes"),
                        result.getBytes("data")
                );
            }
        }
    }

    /** Metadata for the immutable world snapshots captured for replay sandbox/web reconstruction. */
    public List<WorldChunkMeta> worldChunks(long replayId) throws SQLException {
        List<WorldChunkMeta> chunks = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT world, chunk_x, chunk_z, size_bytes FROM hg_replay_world_chunks "
                             + "WHERE replay_id=? ORDER BY world ASC, chunk_x ASC, chunk_z ASC"
             )) {
            statement.setLong(1, replayId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    chunks.add(new WorldChunkMeta(
                            result.getString("world"),
                            result.getInt("chunk_x"),
                            result.getInt("chunk_z"),
                            result.getInt("size_bytes")
                    ));
                }
            }
        }
        return chunks;
    }

    /**
     * Returns one private stored world snapshot. A null/blank world is accepted only
     * as a convenience for single-world replays and resolves deterministically.
     */
    public WorldChunkData worldChunk(long replayId, String world, int chunkX, int chunkZ) throws SQLException {
        boolean scoped = notBlank(world);
        String sql = scoped
                ? "SELECT world, chunk_x, chunk_z, size_bytes, data FROM hg_replay_world_chunks "
                    + "WHERE replay_id=? AND world=? AND chunk_x=? AND chunk_z=?"
                : "SELECT world, chunk_x, chunk_z, size_bytes, data FROM hg_replay_world_chunks "
                    + "WHERE replay_id=? AND chunk_x=? AND chunk_z=? ORDER BY world ASC";

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 1;
            statement.setLong(i++, replayId);
            if (scoped) statement.setString(i++, world.trim());
            statement.setInt(i++, chunkX);
            statement.setInt(i, chunkZ);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                return new WorldChunkData(
                        result.getString("world"),
                        result.getInt("chunk_x"),
                        result.getInt("chunk_z"),
                        result.getInt("size_bytes"),
                        result.getBytes("data")
                );
            }
        }
    }

    private static ReplayRecord mapReplay(ResultSet result) throws SQLException {
        Long endedAt = nullableLong(result, "ended_at");
        Long captureStart = nullableLong(result, "capture_start_ms");
        Long captureEnd = nullableLong(result, "capture_end_ms");
        Double aiScore = result.getObject("ai_score") == null ? null : result.getDouble("ai_score");
        return new ReplayRecord(
                result.getLong("id"),
                result.getString("player_uuid"),
                result.getString("player_name"),
                result.getString("server_name"),
                result.getLong("started_at"),
                endedAt,
                result.getString("trigger_type"),
                result.getString("trigger_meta"),
                aiScore,
                result.getInt("format_version"),
                result.getString("codec"),
                result.getLong("size_bytes"),
                result.getInt("chunk_count"),
                captureStart,
                captureEnd
        );
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        Object value = result.getObject(column);
        return value == null ? null : result.getLong(column);
    }

    private static void bind(PreparedStatement statement, List<Object> values) throws SQLException {
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            int index = i + 1;
            if (value instanceof Integer integer) statement.setInt(index, integer);
            else if (value instanceof Long number) statement.setLong(index, number);
            else statement.setString(index, String.valueOf(value));
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    public static final class Filters {
        public String playerUuid;
        public String playerName;
        public String server;
        public String trigger;
        public Long fromMs;
        public Long toMs;
        public int page = 1;
        public int perPage = 25;
    }

    public record Page(int page, int perPage, long total, List<ReplayRecord> replays) {
        public long pages() {
            return perPage <= 0 ? 1 : Math.max(1L, (total + perPage - 1L) / perPage);
        }
    }

    public record ReplayRecord(
            long id,
            String playerUuid,
            String playerName,
            String serverName,
            long startedAt,
            Long endedAt,
            String triggerType,
            String triggerMeta,
            Double aiScore,
            int formatVersion,
            String codec,
            long sizeBytes,
            int chunkCount,
            Long captureStartMs,
            Long captureEndMs
    ) {
        public long durationMs() {
            if (captureStartMs == null || captureEndMs == null) return 0L;
            return Math.max(0L, captureEndMs - captureStartMs);
        }

        public long triggerOffsetMs() {
            return captureStartMs == null ? 0L : Math.max(0L, startedAt - captureStartMs);
        }
    }

    public record ChunkMeta(int seq, long startMs, long endMs, int sizeBytes) {}
    public record ChunkData(int seq, long startMs, long endMs, int sizeBytes, byte[] data) {}
    public record WorldChunkMeta(String world, int chunkX, int chunkZ, int sizeBytes) {}
    public record WorldChunkData(String world, int chunkX, int chunkZ, int sizeBytes, byte[] data) {}
}
