package me.hackerguardian.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HgApiJsonTest {

    @Test
    void serializesNestedApiDataAndEscapesStrings() {
        String json = HgApiJson.toJson(HgApiResponse.ok(Map.of(
                "message", "line one\n\"quoted\"",
                "values", List.of(1, true, "x")
        )));

        assertTrue(json.startsWith("{\"ok\":true,\"data\":"));
        assertTrue(json.contains("\"message\":\"line one\\n\\\"quoted\\\"\""));
        assertTrue(json.contains("\"values\":[1,true,\"x\"]"));
        assertTrue(json.contains("\"api_version\":1"));
        assertTrue(json.contains("\"time_ms\":"));
    }

    @Test
    void nonFiniteNumbersNeverProduceInvalidJsonTokens() {
        String json = HgApiJson.toJson(Map.of(
                "nan", Double.NaN,
                "positive_infinity", Double.POSITIVE_INFINITY,
                "finite", 1.25
        ));

        assertTrue(json.contains("\"nan\":null"));
        assertTrue(json.contains("\"positive_infinity\":null"));
        assertTrue(json.contains("\"finite\":1.25"));
        assertFalse(json.contains("NaN"));
        assertFalse(json.contains("Infinity"));
    }
}
