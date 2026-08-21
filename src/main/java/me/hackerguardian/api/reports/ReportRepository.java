package me.hackerguardian.api.reports;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ReportRepository {

    private final DataSource ds;

    public ReportRepository(DataSource ds) {
        this.ds = ds;
    }

    public static final class Page<T> {
        public final int page;
        public final int perPage;
        public final long total;
        public final List<T> data;

        public Page(int page, int perPage, long total, List<T> data) {
            this.page = page;
            this.perPage = perPage;
            this.total = total;
            this.data = data;
        }

        public long pages() {
            if (perPage <= 0) return 1;
            return (total + perPage - 1) / perPage;
        }
    }

    public Page<ReportDto> list(String status, String q, int page, int perPage) throws SQLException {
        page = Math.max(1, page);
        perPage = clamp(perPage, 1, 100);
        int offset = (page - 1) * perPage;

        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        if (status != null && !status.isBlank() && !"all".equalsIgnoreCase(status)) {
            where.append(" AND status = ? ");
            params.add(status.toUpperCase(Locale.ROOT));
        }

        if (q != null && !q.isBlank()) {
            String query = q.trim();
            String like = "%" + query + "%";
            Long reportId = tryParseLong(query);

            where.append(" AND (reported_name LIKE ? OR reporter_name LIKE ? OR reported_uuid LIKE ? OR reporter_uuid LIKE ?");
            params.add(like);
            params.add(like);
            params.add(like);
            params.add(like);

            if (reportId != null) {
                where.append(" OR id = ?");
                params.add(reportId);
            }
            where.append(") ");
        }

        long total;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM hg_reports" + where)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                total = rs.getLong(1);
            }
        }

        List<ReportDto> out = new ArrayList<>();
        String sql = "SELECT * FROM hg_reports" + where + " ORDER BY created_at DESC LIMIT ? OFFSET ?";
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(perPage);
        pageParams.add(offset);

        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, pageParams);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapReport(rs));
            }
        }

        return new Page<>(page, perPage, total, out);
    }

    public ReportDto get(long id) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM hg_reports WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return mapReport(rs);
            }
        }
    }

    public List<ReportCommentDto> getComments(long reportId) throws SQLException {
        List<ReportCommentDto> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM hg_report_comments WHERE report_id = ? ORDER BY created_at ASC"
             )) {
            ps.setLong(1, reportId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ReportCommentDto(
                            rs.getLong("id"),
                            rs.getLong("report_id"),
                            rs.getString("commenter_uuid"),
                            rs.getString("commenter_name"),
                            rs.getString("comment"),
                            rs.getLong("created_at")
                    ));
                }
            }
        }
        return out;
    }

    public long create(String reportedUuid, String reportedName,
                       String reporterUuid, String reporterName,
                       String reason) throws SQLException {

        long now = System.currentTimeMillis();

        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO hg_reports (reported_uuid, reported_name, reporter_uuid, reporter_name, reason, status, created_at, updated_at) " +
                             "VALUES (?, ?, ?, ?, ?, 'OPEN', ?, ?)",
                     Statement.RETURN_GENERATED_KEYS
             )) {
            ps.setString(1, reportedUuid);
            ps.setString(2, reportedName);
            ps.setString(3, reporterUuid);
            ps.setString(4, reporterName);
            ps.setString(5, reason);
            ps.setLong(6, now);
            ps.setLong(7, now);
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
        }

        throw new SQLException("No generated key for report");
    }

    public long addComment(long reportId, String commenterUuid, String commenterName, String comment) throws SQLException {
        long now = System.currentTimeMillis();

        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                if (!exists(c, reportId)) throw new SQLException("Report not found");

                long commentId;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO hg_report_comments (report_id, commenter_uuid, commenter_name, comment, created_at) " +
                                "VALUES (?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS
                )) {
                    ps.setLong(1, reportId);
                    ps.setString(2, commenterUuid);
                    ps.setString(3, commenterName);
                    ps.setString(4, comment);
                    ps.setLong(5, now);
                    ps.executeUpdate();

                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("No generated key for comment");
                        commentId = keys.getLong(1);
                    }
                }

                try (PreparedStatement ps = c.prepareStatement("UPDATE hg_reports SET updated_at=? WHERE id=?")) {
                    ps.setLong(1, now);
                    ps.setLong(2, reportId);
                    ps.executeUpdate();
                }

                c.commit();
                return commentId;
            } catch (Exception e) {
                c.rollback();
                if (e instanceof SQLException) throw (SQLException) e;
                throw new SQLException("Failed to add report comment", e);
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    public boolean setStatus(long reportId, String status, String resolverUuid) throws SQLException {
        long now = System.currentTimeMillis();

        if ("CLOSED".equalsIgnoreCase(status)) {
            try (Connection c = ds.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "UPDATE hg_reports SET status='CLOSED', updated_at=?, resolved_by_uuid=?, resolved_at=? WHERE id=?"
                 )) {
                ps.setLong(1, now);
                ps.setString(2, resolverUuid);
                ps.setLong(3, now);
                ps.setLong(4, reportId);
                return ps.executeUpdate() == 1;
            }
        }

        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE hg_reports SET status='OPEN', updated_at=?, resolved_by_uuid=NULL, resolved_at=NULL WHERE id=?"
             )) {
            ps.setLong(1, now);
            ps.setLong(2, reportId);
            return ps.executeUpdate() == 1;
        }
    }

    private boolean exists(Connection c, long reportId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM hg_reports WHERE id=?")) {
            ps.setLong(1, reportId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static ReportDto mapReport(ResultSet rs) throws SQLException {
        Long resolvedAt = rs.getObject("resolved_at") == null ? null : rs.getLong("resolved_at");

        return new ReportDto(
                rs.getLong("id"),
                rs.getString("reported_uuid"),
                rs.getString("reported_name"),
                rs.getString("reporter_uuid"),
                rs.getString("reporter_name"),
                rs.getString("reason"),
                rs.getString("status"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"),
                rs.getString("resolved_by_uuid"),
                resolvedAt
        );
    }

    private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object v = params.get(i);
            int idx = i + 1;
            if (v instanceof Integer) ps.setInt(idx, (Integer) v);
            else if (v instanceof Long) ps.setLong(idx, (Long) v);
            else ps.setString(idx, String.valueOf(v));
        }
    }

    private static Long tryParseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
