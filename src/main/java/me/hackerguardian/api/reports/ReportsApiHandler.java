package me.hackerguardian.api.reports;

import me.hackerguardian.api.HgApiAuth;
import me.hackerguardian.api.reports.http.Json;

import java.nio.charset.StandardCharsets;
import java.util.*;

public final class ReportsApiHandler {

    private final HgApiAuth auth;
    private final ReportRepository repo;

    public ReportsApiHandler(HgApiAuth auth, ReportRepository repo) {
        this.auth = auth;
        this.repo = repo;
    }

    // Hook these into your router:
    // GET  /v1/reports
    // GET  /v1/reports/{id}
    // POST /v1/reports                      (optional)
    // POST /v1/reports/{id}/comments        (NEW)
    // POST /v1/reports/{id}/status          (NEW)

    public Response handle(Request req) {
        try {
            // ---- AUTH ----
            HgApiAuth.AuthResult ar = auth.verify(
                    req.method(),
                    req.path(),              // important: EXACT path used for signing
                    req.bodyBytes(),
                    req.header("x-hg-key"),
                    req.header("x-hg-ts"),
                    req.header("x-hg-nonce"),
                    req.header("x-hg-sig")
            );

            if (!ar.ok()) {
                return Response.json(ar.status(), Json.obj(Map.of(
                        "ok", "false",
                        "message", Json.esc(ar.message())
                )));
            }

            String method = req.method();
            String path = req.path();

            if (method == null) method = "";
            if (path == null) path = "";

            // normalize trailing slash
            if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);

            // ---- ROUTES ----
            if ("GET".equalsIgnoreCase(method) && "/v1/reports".equals(path)) return list(req);
            if ("POST".equalsIgnoreCase(method) && "/v1/reports".equals(path)) return create(req);

            if (path.startsWith("/v1/reports/")) {
                String tail = path.substring("/v1/reports/".length()); // "123" or "123/comments" etc
                String[] parts = tail.split("/");
                if (parts.length >= 1) {
                    long id = parseLong(parts[0]);
                    if (id <= 0) return badRequest("Invalid id");

                    if ("GET".equalsIgnoreCase(method) && parts.length == 1) return get(req, id);

                    if ("POST".equalsIgnoreCase(method) && parts.length == 2) {
                        String action = parts[1].toLowerCase(Locale.ROOT);
                        if ("comments".equals(action)) return addComment(req, id);
                        if ("status".equals(action)) return setStatus(req, id);
                    }
                }
            }

            return Response.json(404, Json.obj(Map.of(
                    "ok", "false",
                    "message", Json.esc("Not found")
            )));

        } catch (Exception e) {
            return Response.json(500, Json.obj(Map.of(
                    "ok", "false",
                    "message", Json.esc("Server error " + e)
            )));
        }
    }

    private Response list(Request req) throws Exception {
        String status = nz(req.query("status"), "open");
        String q = req.query("q");

        int page = (int) parseLong(nz(req.query("page"), "1"));
        int perPage = (int) parseLong(nz(req.query("per_page"), "25"));

        ReportRepository.Page<ReportDto> p = repo.list(status, q, page, perPage);

        List<String> items = new ArrayList<>();
        for (ReportDto r : p.data) {
            items.add(Json.obj(new LinkedHashMap<>() {{
                put("id", String.valueOf(r.id));
                put("reported_uuid", Json.esc(r.reportedUuid));
                put("reported_name", Json.esc(r.reportedName));
                put("reporter_uuid", Json.esc(r.reporterUuid));
                put("reporter_name", Json.esc(r.reporterName));
                put("reason", Json.esc(r.reason));
                put("status", Json.esc(r.status));
                put("created_at", String.valueOf(r.createdAt));
                put("updated_at", String.valueOf(r.updatedAt));
                put("resolved_by_uuid", r.resolvedByUuid == null ? "null" : Json.esc(r.resolvedByUuid));
                put("resolved_at", r.resolvedAt == null ? "null" : String.valueOf(r.resolvedAt));
            }}));
        }

        String body = Json.obj(new LinkedHashMap<>() {{
            put("ok", "true");
            put("page", String.valueOf(p.page));
            put("per_page", String.valueOf(p.perPage));
            put("total", String.valueOf(p.total));
            put("pages", String.valueOf(p.pages()));
            put("data", Json.arr(items));
        }});

        return Response.json(200, body);
    }

    private Response get(Request req, long id) throws Exception {
        ReportDto r = repo.get(id);
        if (r == null) {
            return Response.json(404, Json.obj(Map.of(
                    "ok", "false",
                    "message", Json.esc("Report not found")
            )));
        }

        List<ReportCommentDto> comments = repo.getComments(id);
        List<String> cItems = new ArrayList<>();
        for (ReportCommentDto c : comments) {
            cItems.add(Json.obj(new LinkedHashMap<>() {{
                put("id", String.valueOf(c.id));
                put("report_id", String.valueOf(c.reportId));
                put("commenter_uuid", Json.esc(c.commenterUuid));
                put("commenter_name", Json.esc(c.commenterName));
                put("comment", Json.esc(c.comment));
                put("created_at", String.valueOf(c.createdAt));
            }}));
        }

        String body = Json.obj(new LinkedHashMap<>() {{
            put("ok", "true");
            put("report", Json.obj(new LinkedHashMap<>() {{
                put("id", String.valueOf(r.id));
                put("reported_uuid", Json.esc(r.reportedUuid));
                put("reported_name", Json.esc(r.reportedName));
                put("reporter_uuid", Json.esc(r.reporterUuid));
                put("reporter_name", Json.esc(r.reporterName));
                put("reason", Json.esc(r.reason));
                put("status", Json.esc(r.status));
                put("created_at", String.valueOf(r.createdAt));
                put("updated_at", String.valueOf(r.updatedAt));
                put("resolved_by_uuid", r.resolvedByUuid == null ? "null" : Json.esc(r.resolvedByUuid));
                put("resolved_at", r.resolvedAt == null ? "null" : String.valueOf(r.resolvedAt));
            }}));
            put("comments", Json.arr(cItems));
        }});

        return Response.json(200, body);
    }

    // Optional (panel-submitted reports)
    private Response create(Request req) throws Exception {
        // IMPORTANT: call the method bodyBytes() (not interface fields)
        byte[] raw = req.bodyBytes() == null ? new byte[0] : req.bodyBytes();
        String s = new String(raw, StandardCharsets.UTF_8);

        String reportedUuid = pickJsonString(s, "reported_uuid");
        String reportedName = pickJsonString(s, "reported_name");
        String reporterUuid = pickJsonString(s, "reporter_uuid");
        String reporterName = pickJsonString(s, "reporter_name");
        String reason = pickJsonString(s, "reason");

        if (blank(reportedUuid) || blank(reportedName) || blank(reporterUuid) || blank(reporterName) || blank(reason)) {
            return badRequest("Missing fields");
        }

        long id = repo.create(reportedUuid, reportedName, reporterUuid, reporterName, reason);

        return Response.json(201, Json.obj(new LinkedHashMap<>() {{
            put("ok", "true");
            put("id", String.valueOf(id));
        }}));
    }

    // NEW: POST /v1/reports/{id}/comments
    // Expected JSON: { "commenter_uuid":"...", "commenter_name":"...", "comment":"..." }
    private Response addComment(Request req, long reportId) throws Exception {
        byte[] raw = req.bodyBytes() == null ? new byte[0] : req.bodyBytes();
        String s = new String(raw, StandardCharsets.UTF_8);

        String commenterUuid = pickJsonString(s, "commenter_uuid");
        String commenterName = pickJsonString(s, "commenter_name");
        String comment = pickJsonString(s, "comment");

        if (blank(commenterUuid) || blank(commenterName) || blank(comment)) {
            return badRequest("Missing fields");
        }

        long commentId = repo.addComment(reportId, commenterUuid, commenterName, comment);

        return Response.json(201, Json.obj(new LinkedHashMap<>() {{
            put("ok", "true");
            put("comment_id", String.valueOf(commentId));
        }}));
    }

    // NEW: POST /v1/reports/{id}/status
    // Expected JSON: { "status":"OPEN|CLOSED", "actor_uuid":"...", "actor_name":"..." }
    private Response setStatus(Request req, long reportId) throws Exception {
        byte[] raw = req.bodyBytes() == null ? new byte[0] : req.bodyBytes();
        String s = new String(raw, StandardCharsets.UTF_8);

        String status = pickJsonString(s, "status");
        String actorUuid = pickJsonString(s, "actor_uuid");
        String actorName = pickJsonString(s, "actor_name"); // optional

        if (blank(status) || blank(actorUuid)) {
            return badRequest("Missing fields");
        }

        status = status.trim().toUpperCase(Locale.ROOT);
        if (!"OPEN".equals(status) && !"CLOSED".equals(status)) {
            return badRequest("Invalid status");
        }

        boolean ok = repo.setStatus(reportId, status, actorUuid);
        if (!ok) {
            return Response.json(404, Json.obj(Map.of(
                    "ok", "false",
                    "message", Json.esc("Report not found")
            )));
        }

        // nice-to-have: auto comment
        if (!blank(actorName)) {
            repo.addComment(reportId, actorUuid, actorName, "Status changed to " + status);
        }

        String finalStatus = status;
        return Response.json(200, Json.obj(new LinkedHashMap<>() {{
            put("ok", "true");
            put("status", Json.esc(finalStatus));
        }}));
    }

    private Response badRequest(String msg) {
        return Response.json(400, Json.obj(Map.of(
                "ok", "false",
                "message", Json.esc(msg)
        )));
    }

    // --- helpers ---
    private static String nz(String v, String def) { return (v == null || v.isBlank()) ? def : v; }
    private static boolean blank(String v) { return v == null || v.trim().isEmpty(); }
    private static long parseLong(String s) { try { return Long.parseLong(s); } catch (Exception e) { return -1; } }

    // tiny (naive) JSON string extractor for simple payloads
    private static String pickJsonString(String json, String key) {
        if (json == null) return null;
        String k = "\"" + key + "\"";
        int i = json.indexOf(k);
        if (i < 0) return null;
        int colon = json.indexOf(':', i + k.length());
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }

    public interface Request {
        String method();
        String path();
        byte[] bodyBytes();
        String header(String name);
        String query(String name);
    }

    public static final class Response {
        public final int status;
        public final String contentType;
        public final byte[] body;

        private Response(int status, String contentType, byte[] body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }

        public static Response json(int status, String json) {
            return new Response(status, "application/json; charset=utf-8",
                    json.getBytes(StandardCharsets.UTF_8));
        }
    }
}