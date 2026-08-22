package me.hackerguardian.api.reports;

import com.sun.net.httpserver.HttpExchange;
import me.hackerguardian.api.HgApiAuth;
import me.hackerguardian.api.HgApiHttp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stable GET report routes for the web investigation UI. */
public final class ReportsV1ApiHandler {

    private static final String BASE = "/v1/reports";

    private final HgApiAuth auth;
    private final ReportRepository repository;

    public ReportsV1ApiHandler(HgApiAuth auth, ReportRepository repository) {
        this.auth = auth;
        this.repository = repository;
    }

    public void handle(HttpExchange exchange) {
        try {
            HgApiHttp.AuthenticatedRequest request = HgApiHttp.authenticate(exchange, auth);
            if (!HgApiHttp.requireAuthenticated(exchange, request)) return;
            if (!HgApiHttp.requireGet(exchange, request)) return;

            if (BASE.equals(request.path())) {
                list(exchange, request);
                return;
            }
            if (request.path().startsWith(BASE + "/")) {
                String tail = request.path().substring((BASE + "/").length());
                if (!tail.contains("/")) {
                    long id = parsePositiveLong(tail);
                    if (id <= 0) {
                        HgApiHttp.writeError(exchange, 400, "INVALID_REPORT_ID", "Invalid report id");
                        return;
                    }
                    detail(exchange, id);
                    return;
                }
            }
            HgApiHttp.writeError(exchange, 404, "NOT_FOUND", "Report route not found");
        } catch (java.io.IOException tooLarge) {
            HgApiHttp.writeError(exchange, 413, "REQUEST_TOO_LARGE", tooLarge.getMessage());
        } catch (Exception e) {
            HgApiHttp.writeError(exchange, 500, "INTERNAL_ERROR", "Report API request failed");
        }
    }

    private void list(HttpExchange exchange, HgApiHttp.AuthenticatedRequest request) throws Exception {
        String status = valueOr(request.query("status"), "all");
        String query = request.query("q");
        int page = parsePositiveInt(request.query("page"), 1);
        int perPage = parsePositiveInt(request.query("per_page"), 25);

        ReportRepository.Page<ReportDto> result = repository.list(status, query, page, perPage);
        List<Map<String, Object>> reports = new ArrayList<>();
        for (ReportDto report : result.data) reports.add(reportMap(report));

        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("page", result.page);
        data.put("per_page", result.perPage);
        data.put("total", result.total);
        data.put("pages", result.pages());
        data.put("reports", reports);
        HgApiHttp.writeOk(exchange, 200, data);
    }

    private void detail(HttpExchange exchange, long reportId) throws Exception {
        ReportDto report = repository.get(reportId);
        if (report == null) {
            HgApiHttp.writeError(exchange, 404, "NOT_FOUND", "Report not found");
            return;
        }

        List<Map<String, Object>> comments = new ArrayList<>();
        for (ReportCommentDto comment : repository.getComments(reportId)) {
            comments.add(Map.of(
                    "id", comment.id,
                    "report_id", comment.reportId,
                    "commenter_uuid", comment.commenterUuid,
                    "commenter_name", comment.commenterName,
                    "comment", comment.comment,
                    "created_at", comment.createdAt
            ));
        }

        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.putAll(reportMap(report));
        data.put("comments", comments);
        // Explicit empty link collections are part of the v1 shape. The current
        // DB schema has no report<->replay/detection link table yet, so the API
        // must not pretend that unrelated player data is linked evidence.
        data.put("replays", List.of());
        data.put("detections", List.of());
        HgApiHttp.writeOk(exchange, 200, data);
    }

    private static Map<String, Object> reportMap(ReportDto report) {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("id", report.id);
        data.put("server_name", null);
        data.put("reported", Map.of("uuid", report.reportedUuid, "name", report.reportedName));
        data.put("reporter", Map.of("uuid", report.reporterUuid, "name", report.reporterName));
        data.put("reason", report.reason);
        data.put("status", report.status);
        data.put("created_at", report.createdAt);
        data.put("updated_at", report.updatedAt);
        data.put("resolved_by_uuid", report.resolvedByUuid);
        data.put("resolved_at", report.resolvedAt);
        data.put("linked_replays", 0);
        data.put("linked_detections", 0);
        return data;
    }

    private static long parsePositiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : -1L;
        } catch (Exception ignored) {
            return -1L;
        }
    }

    private static int parsePositiveInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
