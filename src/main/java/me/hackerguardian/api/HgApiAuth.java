package me.hackerguardian.api;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class HgApiAuth {

    private final Map<String, String> keyIdToSecret;
    private final boolean requireAuth;
    private final long allowedSkewMs;
    private final long nonceTtlMs;

    // Replay protection
    private final ConcurrentHashMap<String, Long> seenNonces = new ConcurrentHashMap<>();

    public HgApiAuth(Map<String, String> keyIdToSecret, boolean requireAuth, long allowedSkewMs, long nonceTtlMs) {
        this.keyIdToSecret = keyIdToSecret;
        this.requireAuth = requireAuth;
        this.allowedSkewMs = allowedSkewMs;
        this.nonceTtlMs = nonceTtlMs;
    }

    public boolean isRequireAuth() {
        return requireAuth;
    }

    public AuthResult verify(String method, String path, byte[] body,
                             String keyId, String timestamp, String nonce, String signatureHex) {

        if (!requireAuth) return AuthResult.success();

        if (isBlank(keyId) || isBlank(timestamp) || isBlank(nonce) || isBlank(signatureHex)) {
            return AuthResult.fail(401, "Missing auth headers");
        }

        String secret = keyIdToSecret.get(keyId);
        if (secret == null || secret.isEmpty()) {
            return AuthResult.fail(401, "Unknown key id");
        }

        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (Exception e) {
            return AuthResult.fail(401, "Invalid timestamp");
        }

        long now = System.currentTimeMillis();
        long diff = Math.abs(now - ts);
        if (diff > allowedSkewMs) {
            return AuthResult.fail(401, "Timestamp outside allowed skew");
        }

        // replay protection (keyId:nonce)
        String nonceKey = keyId + ":" + nonce;
        cleanupNonces(now);

        Long existing = seenNonces.putIfAbsent(nonceKey, now);
        if (existing != null) {
            return AuthResult.fail(401, "Replay detected (nonce already used)");
        }

        String bodyHash = sha256Hex(body == null ? new byte[0] : body);
        String base = method.toUpperCase(Locale.ROOT) + "\n" +
                path + "\n" +
                timestamp + "\n" +
                nonce + "\n" +
                bodyHash;

        String expectedHex = hmacSha256Hex(secret, base);

        if (!constantTimeEquals(expectedHex, signatureHex)) {
            return AuthResult.fail(401, "Signature invalid");
        }

        return AuthResult.success();
    }

    private void cleanupNonces(long now) {
        for (Map.Entry<String, Long> e : seenNonces.entrySet()) {
            if (now - e.getValue() > nonceTtlMs) {
                seenNonces.remove(e.getKey(), e.getValue());
            }
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(data);
            return toHex(dig);
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
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        int diff = x.length ^ y.length;
        for (int i = 0; i < Math.min(x.length, y.length); i++) diff |= (x[i] ^ y[i]);
        return diff == 0;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    public static final class AuthResult {
        private final boolean ok;
        private final int status;
        private final String message;

        private AuthResult(boolean ok, int status, String message) {
            this.ok = ok;
            this.status = status;
            this.message = message;
        }

        public boolean ok() { return ok; }
        public int status() { return status; }
        public String message() { return message; }

        public static AuthResult success() {
            return new AuthResult(true, 200, "ok");
        }

        public static AuthResult fail(int status, String msg) {
            return new AuthResult(false, status, msg);
        }
    }
}
