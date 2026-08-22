package me.hackerguardian.api;

import java.util.LinkedHashMap;
import java.util.Map;

/** Canonical response envelope for every public HackerGuardian API v1 route. */
public final class HgApiResponse {

    public static final int API_VERSION = 1;

    private HgApiResponse() {}

    public static Map<String, Object> ok(Object data) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("data", data == null ? Map.of() : data);
        out.put("meta", meta());
        return out;
    }

    public static Map<String, Object> error(String code, String message) {
        LinkedHashMap<String, Object> error = new LinkedHashMap<>();
        error.put("code", normalizeCode(code));
        error.put("message", message == null || message.isBlank() ? "Request failed" : message);

        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", error);
        out.put("meta", meta());
        return out;
    }

    private static Map<String, Object> meta() {
        LinkedHashMap<String, Object> meta = new LinkedHashMap<>();
        meta.put("api_version", API_VERSION);
        meta.put("time_ms", System.currentTimeMillis());
        return meta;
    }

    private static String normalizeCode(String code) {
        if (code == null || code.isBlank()) return "INTERNAL_ERROR";
        return code.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_');
    }
}
