package me.hackerguardian.api;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HgApiAuthTest {

    private static final String KEY = "panel-1";
    private static final String SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void authenticatesExactRequestTargetAndRejectsNonceReplay() throws Exception {
        HgApiAuth auth = new HgApiAuth(Map.of(KEY, SECRET), true, 60_000L, 300_000L);
        long now = System.currentTimeMillis();
        String timestamp = Long.toString(now);
        String nonce = "nonce-1";
        String target = "/v1/replays?page=1&server=pvp";
        byte[] body = new byte[0];
        String signature = signature("GET", target, timestamp, nonce, body);

        HgApiAuth.AuthResult first = auth.verify("GET", target, body, KEY, timestamp, nonce, signature);
        assertTrue(first.ok());

        HgApiAuth.AuthResult replay = auth.verify("GET", target, body, KEY, timestamp, nonce, signature);
        assertEquals(401, replay.status());
        assertEquals("AUTH_REPLAY", replay.code());
    }

    @Test
    void queryMutationInvalidatesSignature() throws Exception {
        HgApiAuth auth = new HgApiAuth(Map.of(KEY, SECRET), true, 60_000L, 300_000L);
        String timestamp = Long.toString(System.currentTimeMillis());
        String nonce = "nonce-query";
        byte[] body = new byte[0];
        String signedTarget = "/v1/replays?page=1&server=pvp";
        String signature = signature("GET", signedTarget, timestamp, nonce, body);

        HgApiAuth.AuthResult mutated = auth.verify(
                "GET",
                "/v1/replays?page=1&server=survival",
                body,
                KEY,
                timestamp,
                nonce,
                signature
        );

        assertEquals(401, mutated.status());
        assertEquals("AUTH_INVALID", mutated.code());
    }

    @Test
    void invalidSignatureDoesNotPoisonNonce() throws Exception {
        HgApiAuth auth = new HgApiAuth(Map.of(KEY, SECRET), true, 60_000L, 300_000L);
        String timestamp = Long.toString(System.currentTimeMillis());
        String nonce = "nonce-poison";
        String target = "/v1/health";
        byte[] body = new byte[0];

        HgApiAuth.AuthResult invalid = auth.verify(
                "GET", target, body, KEY, timestamp, nonce,
                "00".repeat(32)
        );
        assertEquals("AUTH_INVALID", invalid.code());

        String validSignature = signature("GET", target, timestamp, nonce, body);
        assertTrue(auth.verify("GET", target, body, KEY, timestamp, nonce, validSignature).ok());
    }

    private static String signature(String method,
                                    String target,
                                    String timestamp,
                                    String nonce,
                                    byte[] body) throws Exception {
        String base = method + "\n"
                + target + "\n"
                + timestamp + "\n"
                + nonce + "\n"
                + hex(MessageDigest.getInstance("SHA-256").digest(body));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return hex(mac.doFinal(base.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format("%02x", value));
        return out.toString();
    }
}
