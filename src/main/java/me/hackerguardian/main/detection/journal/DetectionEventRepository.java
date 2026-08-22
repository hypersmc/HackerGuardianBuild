package me.hackerguardian.main.detection.journal;

import me.hackerguardian.database.SqlSchema;
import me.hackerguardian.main.detection.DetectionFinding;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared SQL evidence journal used by the proxy/web investigation API. */
public final class DetectionEventRepository {

    private final DataSource dataSource;

    public DetectionEventRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void ensureTable() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "CREATE TABLE IF NOT EXISTS hg_detection_events ("
                             + "event_id CHAR(36) PRIMARY KEY,"
                             + "server_name VARCHAR(64) NOT NULL,"
                             + "time_ms BIGINT NOT NULL,"
                             + "player_uuid CHAR(36) NOT NULL,"
                             + "player_name VARCHAR(16) NOT NULL,"
                             + "assessment_risk DOUBLE PRECISION NOT NULL,"
                             + "assessment_reliability DOUBLE PRECISION NOT NULL,"
                             + "detector_id VARCHAR(128) NOT NULL,"
                             + "category VARCHAR(32) NOT NULL,"
                             + "evidence_strength VARCHAR(16) NOT NULL,"
                             + "score DOUBLE PRECISION NOT NULL,"
                             + "reliability DOUBLE PRECISION NOT NULL,"
                             + "metadata_data TEXT NOT NULL,"
                             + "replay_id BIGINT NULL"
                             + ")"
             )) {
            statement.executeUpdate();
            SqlSchema.ensureIndex(connection, "hg_detection_events", "idx_hg_detection_events_time", "time_ms");
            SqlSchema.ensureIndex(connection, "hg_detection_events", "idx_hg_detection_events_player_time", "player_uuid, time_ms");
            SqlSchema.ensureIndex(connection, "hg_detection_events", "idx_hg_detection_events_server_time", "server_name, time_ms");
            SqlSchema.ensureIndex(connection, "hg_detection_events", "idx_hg_detection_events_detector_time", "detector_id, time_ms");
        }
    }

    public void insert(Event event) throws SQLException {
        String sql = "INSERT INTO hg_detection_events "
                + "(event_id, server_name, time_ms, player_uuid, player_name, assessment_risk, assessment_reliability, "
                + "detector_id, category, evidence_strength, score, reliability, metadata_data, replay_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, event.eventId());
            statement.setString(2, event.serverName());
            statement.setLong(3, event.timeMs());
            statement.setString(4, event.playerUuid());
            statement.setString(5, event.playerName());
            statement.setDouble(6, event.assessmentRisk());
            statement.setDouble(7, event.assessmentReliability());
            statement.setString(8, event.detectorId());
            statement.setString(9, event.category());
            statement.setString(10, event.evidenceStrength());
            statement.setDouble(11, event.score());
            statement.setDouble(12, event.reliability());
            statement.setString(13, encodeMetadata(event.metadata()));
            if (event.replayId() == null) statement.setNull(14, Types.BIGINT);
            else statement.setLong(14, event.replayId());
            statement.executeUpdate();
        }
    }

    public List<Event> recent(String serverNameOrNull,
                              String playerUuidOrNull,
                              String detectorOrNull,
                              Long beforeMsOrNull,
                              int limit) throws SQLException {
        int boundedLimit = Math.max(1, Math.min(limit, 500));
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (serverNameOrNull != null && !serverNameOrNull.isBlank()) {
            where.append(" AND server_name=?");
            params.add(serverNameOrNull);
        }
        if (playerUuidOrNull != null && !playerUuidOrNull.isBlank()) {
            where.append(" AND player_uuid=?");
            params.add(playerUuidOrNull);
        }
        if (detectorOrNull != null && !detectorOrNull.isBlank()) {
            where.append(" AND detector_id=?");
            params.add(detectorOrNull);
        }
        if (beforeMsOrNull != null) {
            where.append(" AND time_ms<?");
            params.add(beforeMsOrNull);
        }

        String sql = "SELECT event_id, server_name, time_ms, player_uuid, player_name, assessment_risk, "
                + "assessment_reliability, detector_id, category, evidence_strength, score, reliability, "
                + "metadata_data, replay_id FROM hg_detection_events"
                + where + " ORDER BY time_ms DESC, event_id DESC LIMIT ?";
        params.add(boundedLimit);

        List<Event> out = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) out.add(map(result));
            }
        }
        return out;
    }

    public int cleanupBefore(long cutoffMs) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM hg_detection_events WHERE time_ms < ?"
             )) {
            statement.setLong(1, cutoffMs);
            return statement.executeUpdate();
        }
    }

    public static Event fromFinding(String eventId,
                                    String serverName,
                                    long timeMs,
                                    String playerUuid,
                                    String playerName,
                                    double assessmentRisk,
                                    double assessmentReliability,
                                    DetectionFinding finding,
                                    Long replayId) {
        return new Event(
                eventId,
                serverName,
                timeMs,
                playerUuid,
                playerName,
                assessmentRisk,
                assessmentReliability,
                finding.getDetectorId(),
                finding.getCategory().name(),
                finding.getStrength().name(),
                finding.getScore(),
                finding.getReliability(),
                finding.getEvidence(),
                replayId
        );
    }

    private static Event map(ResultSet result) throws SQLException {
        long replay = result.getLong("replay_id");
        Long replayId = result.wasNull() ? null : replay;
        return new Event(
                result.getString("event_id"),
                result.getString("server_name"),
                result.getLong("time_ms"),
                result.getString("player_uuid"),
                result.getString("player_name"),
                result.getDouble("assessment_risk"),
                result.getDouble("assessment_reliability"),
                result.getString("detector_id"),
                result.getString("category"),
                result.getString("evidence_strength"),
                result.getDouble("score"),
                result.getDouble("reliability"),
                decodeMetadata(result.getString("metadata_data")),
                replayId
        );
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

    /** Compact, lossless encoding for the flat String->double evidence maps. */
    private static String encodeMetadata(Map<String, Double> metadata) {
        if (metadata == null || metadata.isEmpty()) return "";
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Double> entry : metadata.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || !Double.isFinite(entry.getValue())) continue;
            if (out.length() > 0) out.append('\n');
            out.append(encoder.encodeToString(entry.getKey().getBytes(StandardCharsets.UTF_8)))
                    .append(':').append(Double.toString(entry.getValue()));
        }
        return out.toString();
    }

    private static Map<String, Double> decodeMetadata(String encoded) {
        LinkedHashMap<String, Double> out = new LinkedHashMap<>();
        if (encoded == null || encoded.isBlank()) return out;
        Base64.Decoder decoder = Base64.getUrlDecoder();
        for (String line : encoded.split("\\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            try {
                String key = new String(decoder.decode(line.substring(0, colon)), StandardCharsets.UTF_8);
                double value = Double.parseDouble(line.substring(colon + 1));
                if (Double.isFinite(value)) out.put(key, value);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public record Event(
            String eventId,
            String serverName,
            long timeMs,
            String playerUuid,
            String playerName,
            double assessmentRisk,
            double assessmentReliability,
            String detectorId,
            String category,
            String evidenceStrength,
            double score,
            double reliability,
            Map<String, Double> metadata,
            Long replayId
    ) {}
}
