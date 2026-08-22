package me.hackerguardian.api;

import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small dependency-free JSON serializer used by the HG HTTP API. */
public final class HgApiJson {

    private HgApiJson() {}

    public static byte[] json(Object value) {
        return toJson(value).getBytes(StandardCharsets.UTF_8);
    }

    public static String toJson(Object value) {
        StringBuilder out = new StringBuilder(256);
        append(out, value);
        return out.toString();
    }

    private static void append(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
            return;
        }
        if (value instanceof String || value instanceof Character || value instanceof Enum<?>) {
            out.append('"').append(escape(String.valueOf(value))).append('"');
            return;
        }
        if (value instanceof Number number) {
            if (number instanceof Double d && !Double.isFinite(d)) {
                out.append("null");
            } else if (number instanceof Float f && !Float.isFinite(f)) {
                out.append("null");
            } else {
                out.append(number);
            }
            return;
        }
        if (value instanceof Boolean) {
            out.append(value);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) continue;
                if (!first) out.append(',');
                first = false;
                out.append('"').append(escape(String.valueOf(entry.getKey()))).append("\":");
                append(out, entry.getValue());
            }
            out.append('}');
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            out.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) out.append(',');
                first = false;
                append(out, item);
            }
            out.append(']');
            return;
        }
        if (value.getClass().isArray()) {
            out.append('[');
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                if (i > 0) out.append(',');
                append(out, Array.get(value, i));
            }
            out.append(']');
            return;
        }

        out.append('"').append(escape(String.valueOf(value))).append('"');
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }

    public static Map<String, Object> obj(Object... kv) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }
}
