package me.hackerguardian.api;

import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared HTTP primitives for the Paper and proxy API hosts. */
public final class HgApiHttp {

    public static final String HEADER_KEY = "X-HG-Key";
    public static final String HEADER_TIMESTAMP = "X-HG-TS";
    public static final String HEADER_NONCE = "X-HG-Nonce";
    public static final String HEADER_SIGNATURE = "X-HG-Sig";
    public static final int DEFAULT_MAX_BODY_BYTES = 1024 * 1024;

    private HgApiHttp() {}

    public static AuthenticatedRequest authenticate(HttpExchange exchange, HgApiAuth auth) throws IOException {
        byte[] body = readBody(exchange.getRequestBody(), DEFAULT_MAX_BODY_BYTES);
        String path = normalizePath(exchange.getRequestURI().getPath());
        String method = exchange.getRequestMethod();

        HgApiAuth.AuthResult result = auth.verify(
                method,
                path,
                body,
                exchange.getRequestHeaders().getFirst(HEADER_KEY),
                exchange.getRequestHeaders().getFirst(HEADER_TIMESTAMP),
                exchange.getRequestHeaders().getFirst(HEADER_NONCE),
                exchange.getRequestHeaders().getFirst(HEADER_SIGNATURE)
        );
        return new AuthenticatedRequest(method, path, body, query(exchange.getRequestURI().getRawQuery()), result);
    }

    public static void writeOk(HttpExchange exchange, int status, Object data) {
        writeJson(exchange, status, HgApiResponse.ok(data));
    }

    public static void writeError(HttpExchange exchange, int status, String code, String message) {
        writeJson(exchange, status, HgApiResponse.error(code, message));
    }

    public static void writeJson(HttpExchange exchange, int status, Object body) {
        try {
            byte[] out = HgApiJson.json(body);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
        } catch (Exception ignored) {
        } finally {
            try { exchange.close(); } catch (Exception ignored) {}
        }
    }

    public static boolean requireGet(HttpExchange exchange, AuthenticatedRequest request) {
        if ("GET".equalsIgnoreCase(request.method())) return true;
        writeError(exchange, 405, "METHOD_NOT_ALLOWED", "Method not allowed");
        return false;
    }

    public static boolean requireAuthenticated(HttpExchange exchange, AuthenticatedRequest request) {
        if (request.auth().ok()) return true;
        writeError(exchange, request.auth().status(), request.auth().code(), request.auth().message());
        return false;
    }

    public static String normalizePath(String path) {
        if (path == null || path.isBlank()) return "/";
        String normalized = path;
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static byte[] readBody(InputStream input, int maxBytes) throws IOException {
        if (input == null) return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) throw new IOException("Request body exceeds " + maxBytes + " bytes");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static Map<String, String> query(String rawQuery) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) return out;
        for (String part : rawQuery.split("&")) {
            int equals = part.indexOf('=');
            String key = equals < 0 ? part : part.substring(0, equals);
            String value = equals < 0 ? "" : part.substring(equals + 1);
            try {
                key = URLDecoder.decode(key, StandardCharsets.UTF_8);
                value = URLDecoder.decode(value, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
            out.put(key, value);
        }
        return out;
    }

    public record AuthenticatedRequest(
            String method,
            String path,
            byte[] body,
            Map<String, String> query,
            HgApiAuth.AuthResult auth
    ) {
        public String query(String name) {
            return query == null || name == null ? null : query.get(name);
        }
    }
}
