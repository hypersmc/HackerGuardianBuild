package me.hackerguardian.main.report;

import me.hackerguardian.database.DatabaseType;
import me.hackerguardian.database.SqlSchema;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ReportRepository {

    private final DataSource ds;

    public ReportRepository(DataSource ds) {
        this.ds = ds;
    }

    public void ensureTables() throws SQLException {
        try (Connection c = ds.getConnection()) {
            DatabaseType type = SqlSchema.detectType(c);
            String generatedId = type.generatedIdColumn();

            try (PreparedStatement ps = c.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS hg_reports (" +
                            "id " + generatedId + "," +
                            "reported_uuid CHAR(36) NOT NULL," +
                            "reported_name VARCHAR(16) NOT NULL," +
                            "reporter_uuid CHAR(36) NOT NULL," +
                            "reporter_name VARCHAR(16) NOT NULL," +
                            "reason TEXT NOT NULL," +
                            "status VARCHAR(16) NOT NULL DEFAULT 'OPEN'," +
                            "created_at BIGINT NOT NULL," +
                            "updated_at BIGINT NOT NULL," +
                            "resolved_by_uuid CHAR(36) NULL," +
                            "resolved_at BIGINT NULL" +
                            ")"
            )) {
                ps.executeUpdate();
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS hg_report_comments (" +
                            "id " + generatedId + "," +
                            "report_id BIGINT NOT NULL," +
                            "commenter_uuid CHAR(36) NOT NULL," +
                            "commenter_name VARCHAR(16) NOT NULL," +
                            "comment TEXT NOT NULL," +
                            "created_at BIGINT NOT NULL," +
                            "CONSTRAINT fk_report_comments FOREIGN KEY (report_id) REFERENCES hg_reports(id) ON DELETE CASCADE" +
                            ")"
            )) {
                ps.executeUpdate();
            }

            SqlSchema.ensureIndex(c, "hg_reports", "idx_hg_reports_reported_uuid", "reported_uuid");
            SqlSchema.ensureIndex(c, "hg_reports", "idx_hg_reports_reporter_uuid", "reporter_uuid");
            SqlSchema.ensureIndex(c, "hg_reports", "idx_hg_reports_status", "status");
            SqlSchema.ensureIndex(c, "hg_reports", "idx_hg_reports_created_at", "created_at");
            SqlSchema.ensureIndex(c, "hg_report_comments", "idx_hg_report_comments_report_id", "report_id");
        }
    }

    public int countReportsByReporterSince(String reporterUuid, long sinceMs) throws SQLException {
        String sql = "SELECT COUNT(*) FROM hg_reports WHERE reporter_uuid = ? AND created_at >= ?";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, reporterUuid);
            ps.setLong(2, sinceMs);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public long createReport(String reportedUuid, String reportedName,
                             String reporterUuid, String reporterName,
                             String reason, long nowMs) throws SQLException {
        String sql =
                "INSERT INTO hg_reports (reported_uuid, reported_name, reporter_uuid, reporter_name, reason, status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, 'OPEN', ?, ?)";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, reportedUuid);
            ps.setString(2, reportedName);
            ps.setString(3, reporterUuid);
            ps.setString(4, reporterName);
            ps.setString(5, reason);
            ps.setLong(6, nowMs);
            ps.setLong(7, nowMs);
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
                throw new SQLException("No generated key for report insert");
            }
        }
    }

    public Optional<ReportRow> getReport(long reportId) throws SQLException {
        String sql = "SELECT * FROM hg_reports WHERE id = ?";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, reportId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(ReportRow.from(rs));
            }
        }
    }

    public List<ReportRow> listReportsForPlayer(String reportedUuid, int limit, int offset) throws SQLException {
        String sql = "SELECT * FROM hg_reports WHERE reported_uuid = ? ORDER BY created_at DESC LIMIT ? OFFSET ?";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, reportedUuid);
            ps.setInt(2, limit);
            ps.setInt(3, offset);

            List<ReportRow> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(ReportRow.from(rs));
            }
            return out;
        }
    }

    public int countReportsForPlayer(String reportedUuid) throws SQLException {
        String sql = "SELECT COUNT(*) FROM hg_reports WHERE reported_uuid = ?";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, reportedUuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public void addComment(long reportId, String commenterUuid, String commenterName, String comment, long nowMs) throws SQLException {
        String sql = "INSERT INTO hg_report_comments (report_id, commenter_uuid, commenter_name, comment, created_at) VALUES (?, ?, ?, ?, ?)";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, reportId);
            ps.setString(2, commenterUuid);
            ps.setString(3, commenterName);
            ps.setString(4, comment);
            ps.setLong(5, nowMs);
            ps.executeUpdate();
        }
    }

    public List<CommentRow> listComments(long reportId) throws SQLException {
        String sql = "SELECT * FROM hg_report_comments WHERE report_id = ? ORDER BY created_at ASC";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, reportId);

            List<CommentRow> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(CommentRow.from(rs));
            }
            return out;
        }
    }

    public boolean setStatus(long reportId, String newStatus, String resolverUuidOrNull, long nowMs) throws SQLException {
        String sql =
                "UPDATE hg_reports SET status = ?, updated_at = ?, resolved_by_uuid = ?, resolved_at = ? " +
                        "WHERE id = ?";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, newStatus);
            ps.setLong(2, nowMs);

            if (resolverUuidOrNull == null) {
                ps.setNull(3, Types.VARCHAR);
                ps.setNull(4, Types.BIGINT);
            } else {
                ps.setString(3, resolverUuidOrNull);
                ps.setLong(4, nowMs);
            }

            ps.setLong(5, reportId);
            return ps.executeUpdate() == 1;
        }
    }
}
