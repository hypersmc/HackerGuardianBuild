package me.hackerguardian.main.report;

import java.sql.ResultSet;
import java.sql.SQLException;

public record ReportRow(
        long id,
        String reportedUuid,
        String reportedName,
        String reporterUuid,
        String reporterName,
        String reason,
        String status,
        long createdAt,
        long updatedAt,
        String resolvedByUuid,
        Long resolvedAt
) {
    static ReportRow from(ResultSet rs) throws SQLException {
        String resolver = rs.getString("resolved_by_uuid");
        long resolvedAtVal = rs.getLong("resolved_at");
        Long resolvedAt = rs.wasNull() ? null : resolvedAtVal;

        return new ReportRow(
                rs.getLong("id"),
                rs.getString("reported_uuid"),
                rs.getString("reported_name"),
                rs.getString("reporter_uuid"),
                rs.getString("reporter_name"),
                rs.getString("reason"),
                rs.getString("status"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"),
                resolver,
                resolvedAt
        );
    }
}