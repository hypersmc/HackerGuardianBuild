package me.hackerguardian.main.report;

import java.sql.ResultSet;
import java.sql.SQLException;

public record CommentRow(
        long id,
        long reportId,
        String commenterUuid,
        String commenterName,
        String comment,
        long createdAt
) {
    static CommentRow from(ResultSet rs) throws SQLException {
        return new CommentRow(
                rs.getLong("id"),
                rs.getLong("report_id"),
                rs.getString("commenter_uuid"),
                rs.getString("commenter_name"),
                rs.getString("comment"),
                rs.getLong("created_at")
        );
    }
}