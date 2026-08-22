package me.hackerguardian.main.replay;

import me.hackerguardian.database.DatabaseType;
import me.hackerguardian.database.SqlSchema;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class ReplayStorage {

    private final DataSource ds;
    private final String serverName;

    public ReplayStorage(DataSource ds, String serverName) {
        this.ds = ds;
        this.serverName = serverName;
    }

    public void ensureTables() throws SQLException {
        try (Connection c = ds.getConnection()) {
            DatabaseType type = SqlSchema.detectType(c);
            String generatedId = type.generatedIdColumn();
            String blobType = type.binaryLargeObjectType();

            try (PreparedStatement ps = c.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS hg_replays (" +
                            "id " + generatedId + "," +
                            "player_uuid CHAR(36) NOT NULL," +
                            "player_name VARCHAR(16) NOT NULL," +
                            "server_name VARCHAR(64) NOT NULL," +
                            "started_at BIGINT NOT NULL," +
                            "ended_at BIGINT NULL," +
                            "trigger_type VARCHAR(16) NOT NULL," +
                            "trigger_meta TEXT NULL," +
                            "ai_score DOUBLE PRECISION NULL," +
                            "format_version INT NOT NULL," +
                            "codec VARCHAR(16) NOT NULL," +
                            "size_bytes BIGINT NOT NULL DEFAULT 0" +
                            ")"
            )) { ps.executeUpdate(); }

            try (PreparedStatement ps = c.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS hg_replay_chunks (" +
                            "replay_id BIGINT NOT NULL," +
                            "seq INT NOT NULL," +
                            "start_ms BIGINT NOT NULL," +
                            "end_ms BIGINT NOT NULL," +
                            "data " + blobType + " NOT NULL," +
                            "size_bytes INT NOT NULL," +
                            "PRIMARY KEY (replay_id, seq)" +
                            ")"
            )) { ps.executeUpdate(); }

            try (PreparedStatement ps = c.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS hg_replay_world_chunks (" +
                            "replay_id BIGINT NOT NULL," +
                            "world VARCHAR(128) NOT NULL," +
                            "chunk_x INT NOT NULL," +
                            "chunk_z INT NOT NULL," +
                            "captured_at BIGINT NOT NULL DEFAULT 0," +
                            "data " + blobType + " NOT NULL," +
                            "size_bytes INT NOT NULL," +
                            "PRIMARY KEY (replay_id, world, chunk_x, chunk_z)" +
                            ")"
            )) { ps.executeUpdate(); }

            // Existing installations predate exact world-snapshot timestamps.
            SqlSchema.ensureColumn(c, "hg_replay_world_chunks", "captured_at", "BIGINT NOT NULL DEFAULT 0");

            try (PreparedStatement ps = c.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS hg_replay_world_context (" +
                            "replay_id BIGINT NOT NULL," +
                            "world VARCHAR(128) NOT NULL," +
                            "minecraft_version VARCHAR(32) NOT NULL," +
                            "environment VARCHAR(32) NOT NULL," +
                            "game_time BIGINT NOT NULL," +
                            "full_time BIGINT NOT NULL," +
                            "storm INT NOT NULL," +
                            "thundering INT NOT NULL," +
                            "resource_pack_id VARCHAR(128) NULL," +
                            "PRIMARY KEY (replay_id)" +
                            ")"
            )) { ps.executeUpdate(); }

            SqlSchema.ensureIndex(c, "hg_replays", "idx_hg_replays_player_uuid", "player_uuid");
            SqlSchema.ensureIndex(c, "hg_replays", "idx_hg_replays_started_at", "started_at");
            SqlSchema.ensureIndex(c, "hg_replay_chunks", "idx_hg_replay_chunks_replay_id", "replay_id");
            SqlSchema.ensureIndex(c, "hg_replay_world_chunks", "idx_hg_replay_world_chunks_replay_id", "replay_id");
        }
    }

    public long createReplay(String playerUuid, String playerName, long startedAt, ReplayTriggerType triggerType,
                             String triggerMeta, Double aiScore, int formatVersion, String codec) throws SQLException {
        String sql = "INSERT INTO hg_replays (player_uuid, player_name, server_name, started_at, trigger_type, trigger_meta, ai_score, format_version, codec) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, playerUuid);
            ps.setString(2, playerName);
            ps.setString(3, serverName);
            ps.setLong(4, startedAt);
            ps.setString(5, triggerType.name());
            ps.setString(6, triggerMeta);
            if (aiScore == null) ps.setNull(7, Types.DOUBLE); else ps.setDouble(7, aiScore);
            ps.setInt(8, formatVersion);
            ps.setString(9, codec);
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
            throw new SQLException("No generated key for replay");
        }
    }

    public void appendChunk(long replayId, int seq, long startMs, long endMs, byte[] rawChunk) throws SQLException {
        byte[] compressed = gzip(rawChunk);
        String sql = "INSERT INTO hg_replay_chunks (replay_id, seq, start_ms, end_ms, data, size_bytes) VALUES (?, ?, ?, ?, ?, ?)";

        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, replayId);
            ps.setInt(2, seq);
            ps.setLong(3, startMs);
            ps.setLong(4, endMs);
            ps.setBytes(5, compressed);
            ps.setInt(6, compressed.length);
            ps.executeUpdate();
        }

        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE hg_replays SET size_bytes = size_bytes + ? WHERE id = ?")) {
            ps.setLong(1, compressed.length);
            ps.setLong(2, replayId);
            ps.executeUpdate();
        }
    }

    public void finishReplay(long replayId, long endedAt) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE hg_replays SET ended_at = ? WHERE id = ?")) {
            ps.setLong(1, endedAt);
            ps.setLong(2, replayId);
            ps.executeUpdate();
        }
    }

    private static byte[] gzip(byte[] raw) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(128, raw.length / 2));
            try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
                gz.write(raw);
            }
            return baos.toByteArray();
        } catch (IOException e) {
            return raw;
        }
    }

    public static final class ReplayMeta {
        public final long id;
        public final String playerUuid;
        public final String playerName;
        public final String serverName;
        public final long startedAt;
        public final Long endedAt;

        public ReplayMeta(long id, String playerUuid, String playerName, String serverName, long startedAt, Long endedAt) {
            this.id = id;
            this.playerUuid = playerUuid;
            this.playerName = playerName;
            this.serverName = serverName;
            this.startedAt = startedAt;
            this.endedAt = endedAt;
        }
    }

    public ReplayMeta getReplayMeta(long replayId) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM hg_replays WHERE id = ?")) {
            ps.setLong(1, replayId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Long ended = rs.getObject("ended_at") == null ? null : rs.getLong("ended_at");
                return new ReplayMeta(
                        rs.getLong("id"),
                        rs.getString("player_uuid"),
                        rs.getString("player_name"),
                        rs.getString("server_name"),
                        rs.getLong("started_at"),
                        ended
                );
            }
        }
    }

    public static final class ReplayChunk {
        public final int seq;
        public final long startMs;
        public final long endMs;
        public final byte[] data;

        public ReplayChunk(int seq, long startMs, long endMs, byte[] data) {
            this.seq = seq;
            this.startMs = startMs;
            this.endMs = endMs;
            this.data = data;
        }
    }

    public List<ReplayChunk> getChunks(long replayId) throws SQLException {
        ArrayList<ReplayChunk> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT seq, start_ms, end_ms, data FROM hg_replay_chunks WHERE replay_id = ? ORDER BY seq ASC"
             )) {
            ps.setLong(1, replayId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ReplayChunk(
                            rs.getInt("seq"),
                            rs.getLong("start_ms"),
                            rs.getLong("end_ms"),
                            rs.getBytes("data")
                    ));
                }
            }
        }
        return out;
    }

    public static final class ReplayInfoMeta {
        public final long id;
        public final String playerUuid;
        public final String playerName;
        public final String serverName;
        public final long startedAt;
        public final Long endedAt;
        public final String triggerType;
        public final String triggerMeta;
        public final Double aiScore;
        public final int formatVersion;
        public final String codec;
        public final long bytesTotal;

        public ReplayInfoMeta(long id, String playerUuid, String playerName, String serverName,
                              long startedAt, Long endedAt,
                              String triggerType, String triggerMeta, Double aiScore,
                              int formatVersion, String codec, long bytesTotal) {
            this.id = id;
            this.playerUuid = playerUuid;
            this.playerName = playerName;
            this.serverName = serverName;
            this.startedAt = startedAt;
            this.endedAt = endedAt;
            this.triggerType = triggerType;
            this.triggerMeta = triggerMeta;
            this.aiScore = aiScore;
            this.formatVersion = formatVersion;
            this.codec = codec;
            this.bytesTotal = bytesTotal;
        }
    }

    public ReplayInfoMeta getReplayInfoMeta(long replayId) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM hg_replays WHERE id = ?")) {
            ps.setLong(1, replayId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Long ended = rs.getObject("ended_at") == null ? null : rs.getLong("ended_at");
                Double aiScore = rs.getObject("ai_score") == null ? null : rs.getDouble("ai_score");
                return new ReplayInfoMeta(
                        rs.getLong("id"), rs.getString("player_uuid"), rs.getString("player_name"),
                        rs.getString("server_name"), rs.getLong("started_at"), ended,
                        rs.getString("trigger_type"), rs.getString("trigger_meta"), aiScore,
                        rs.getInt("format_version"), rs.getString("codec"), rs.getLong("size_bytes")
                );
            }
        }
    }

    public static final class WorldChunkSnapshot {
        public final String world;
        public final int chunkX;
        public final int chunkZ;
        public final long capturedAtMs;
        public final byte[] data;

        public WorldChunkSnapshot(String world, int chunkX, int chunkZ, long capturedAtMs, byte[] data) {
            this.world = world;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.capturedAtMs = capturedAtMs;
            this.data = data;
        }

        public WorldChunkSnapshot(String world, int chunkX, int chunkZ, byte[] data) {
            this(world, chunkX, chunkZ, 0L, data);
        }
    }

    /** Backward-compatible call used by older tests/callers. */
    public void upsertWorldChunk(long replayId, String world, int chunkX, int chunkZ, byte[] raw) throws SQLException {
        upsertWorldChunk(replayId, world, chunkX, chunkZ, 0L, raw);
    }

    public void upsertWorldChunk(long replayId, String world, int chunkX, int chunkZ,
                                 long capturedAtMs, byte[] raw) throws SQLException {
        byte[] compressed = gzip(raw);

        try (Connection c = ds.getConnection()) {
            DatabaseType type = SqlSchema.detectType(c);
            String sql;
            if (type == DatabaseType.POSTGRESQL) {
                sql = "INSERT INTO hg_replay_world_chunks (replay_id, world, chunk_x, chunk_z, captured_at, data, size_bytes) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?) " +
                        "ON CONFLICT (replay_id, world, chunk_x, chunk_z) DO UPDATE SET " +
                        "captured_at = EXCLUDED.captured_at, data = EXCLUDED.data, size_bytes = EXCLUDED.size_bytes";
            } else {
                sql = "INSERT INTO hg_replay_world_chunks (replay_id, world, chunk_x, chunk_z, captured_at, data, size_bytes) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE captured_at = VALUES(captured_at), data = VALUES(data), size_bytes = VALUES(size_bytes)";
            }

            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, replayId);
                ps.setString(2, world);
                ps.setInt(3, chunkX);
                ps.setInt(4, chunkZ);
                ps.setLong(5, Math.max(0L, capturedAtMs));
                ps.setBytes(6, compressed);
                ps.setInt(7, compressed.length);
                ps.executeUpdate();
            }
        }
    }

    public List<WorldChunkSnapshot> getWorldChunks(long replayId) throws SQLException {
        ArrayList<WorldChunkSnapshot> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT world, chunk_x, chunk_z, captured_at, data FROM hg_replay_world_chunks WHERE replay_id = ?"
             )) {
            ps.setLong(1, replayId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new WorldChunkSnapshot(
                            rs.getString("world"), rs.getInt("chunk_x"), rs.getInt("chunk_z"),
                            rs.getLong("captured_at"), rs.getBytes("data")
                    ));
                }
            }
        }
        return out;
    }

    public void upsertWorldContext(long replayId, ReplayWorldContext context) throws SQLException {
        if (context == null) return;
        try (Connection c = ds.getConnection()) {
            DatabaseType type = SqlSchema.detectType(c);
            String sql;
            if (type == DatabaseType.POSTGRESQL) {
                sql = "INSERT INTO hg_replay_world_context " +
                        "(replay_id, world, minecraft_version, environment, game_time, full_time, storm, thundering, resource_pack_id) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (replay_id) DO UPDATE SET " +
                        "world=EXCLUDED.world, minecraft_version=EXCLUDED.minecraft_version, environment=EXCLUDED.environment, " +
                        "game_time=EXCLUDED.game_time, full_time=EXCLUDED.full_time, storm=EXCLUDED.storm, " +
                        "thundering=EXCLUDED.thundering, resource_pack_id=EXCLUDED.resource_pack_id";
            } else {
                sql = "INSERT INTO hg_replay_world_context " +
                        "(replay_id, world, minecraft_version, environment, game_time, full_time, storm, thundering, resource_pack_id) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE " +
                        "world=VALUES(world), minecraft_version=VALUES(minecraft_version), environment=VALUES(environment), " +
                        "game_time=VALUES(game_time), full_time=VALUES(full_time), storm=VALUES(storm), " +
                        "thundering=VALUES(thundering), resource_pack_id=VALUES(resource_pack_id)";
            }

            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, replayId);
                ps.setString(2, context.world());
                ps.setString(3, context.minecraftVersion());
                ps.setString(4, context.environment());
                ps.setLong(5, context.gameTime());
                ps.setLong(6, context.fullTime());
                ps.setInt(7, context.storm() ? 1 : 0);
                ps.setInt(8, context.thundering() ? 1 : 0);
                if (context.resourcePackId() == null || context.resourcePackId().isBlank()) ps.setNull(9, Types.VARCHAR);
                else ps.setString(9, context.resourcePackId());
                ps.executeUpdate();
            }
        }
    }

    public ReplayWorldContext getWorldContext(long replayId) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM hg_replay_world_context WHERE replay_id = ?")) {
            ps.setLong(1, replayId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new ReplayWorldContext(
                        rs.getString("world"), rs.getString("minecraft_version"), rs.getString("environment"),
                        rs.getLong("game_time"), rs.getLong("full_time"), rs.getInt("storm") != 0,
                        rs.getInt("thundering") != 0, rs.getString("resource_pack_id")
                );
            }
        }
    }

    public static byte[] gunzip(byte[] compressed) {
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = gis.read(buf)) != -1) baos.write(buf, 0, r);
            return baos.toByteArray();
        } catch (Exception e) {
            return compressed;
        }
    }
}
