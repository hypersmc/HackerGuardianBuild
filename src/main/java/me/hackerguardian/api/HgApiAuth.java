package me.hackerguardian.api;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** HMAC-SHA256 authentication and nonce replay protection for the HG API. */
public final class HgApiAuth {

    private final Map<String, String> keyIdToSecret;
    private final boolean requireAuth;
    private final long allowedSkewMs;
    private final long nonceTtlMs;
    private final ConcurrentHashMap<String, Long> seenNonces = new ConcurrentHashMap<>();

    public HgApiAuth(Map<String, String> keyIdToSecret, boolean requireAuth, long allowedSkewMs, long nonceTtlMs) {
        this.keyIdToSecret = keyIdToSecret == null ? Map.of() : Map.copyOf(keyIdToSecret);
        this.requireAuth = requireAuth;
        this.allowedSkewMs = Math.max(1000L, allowedSkewMs);
        this.nonceTtlMs = Math.max(this.allowedSkewMs, nonceTtlMs);
    }

    public boolean isRequireAuth() {
        return requireAuth;
    }

    public AuthResult verify(String method, String path, byte[] body,
                             String keyId, String timestamp, String nonce, String signatureHex) {
        if (!requireAuth) return AuthResult.success();

        if (isBlank(method) || isBlank(path)) {
            return AuthResult.fail(400, "INVALID_REQUEST", "Invalid request method/path");
        }
        if (isBlank(keyId) || isBlank(timestamp) || isBlank(nonce) || isBlank(signatureHex)) {
            return AuthResult.fail(401, "AUTH_REQUIRED", "Missing HMAC authentication headers");
        }

        String secret = keyIdToSecret.get(keyId);
        if (secret == null || secret.isEmpty()) {
            return AuthResult.fail(401, "AUTH_INVALID", "Unknown API key");
        }

        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (Exception e) {
            return AuthResult.fail(401, "AUTH_INVALID", "Invalid timestamp");
        }

        long now = System.currentTimeMillis();
        if (Math.abs(now - ts) > allowedSkewMs) {
            return AuthResult.fail(401, "AUTH_EXPIRED", "Timestamp outside allowed skew");
        }

        String bodyHash = sha256Hex(body == null ? new byte[0] : body);
        String base = method.toUpperCase(Locale.ROOT) + "\n"
                + path + "\n"
                + timestamp + "\n"
                + nonce + "\n"
                + bodyHash;

        String expectedHex = hmacSha256Hex(secret, base);
        if (!constantTimeEquals(expectedHex, signatureHex.toLowerCase(Locale.ROOT))) {
            return AuthResult.fail(401, "AUTH_INVALID", "Signature invalid");
        }

        // Consume a nonce only after the signature itself is valid so anonymous
        // traffic cannot poison the replay cache for a legitimate API key.
        String nonceKey = keyId + ":" + nonce;
        cleanupNonces(now);
        Long existing = seenNonces.putIfAbsent(nonceKey, now);
        if (existing != null) {
            return AuthResult.fail(401, "AUTH_REPLAY", "Nonce already used");
        }

        return AuthResult.success();
    }

    private void cleanupNonces(long now) {
        for (Map.Entry<String, Long> entry : seenNonces.entrySet()) {
            if (now - entry.getValue() > nonceTtlMs) {
                seenNonces.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            return toHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            return "";
        }
    }

    private static String hmacSha256Hex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return toHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format("%02x", value));
        return out.toString();
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static final class AuthResult {
        private final boolean ok;
        private final int status;
        private final String code;
        private final String message;

        private AuthResult(boolean ok, int status, String code, String message) {
            this.ok = ok;
            this.status = status;
            this.code = code;
            this.message = message;
        }

        public boolean ok() { return ok; }
        public int status() { return status; }
        public String code() { return code; }
        public String message() { return message; }

        public static AuthResult success() {
            return new AuthResult(true, 200, "OK", "ok");
        }

        public static AuthResult fail(int status, String code, String message) {
            return new AuthResult(false, status, code, message);
        }
    }
}
