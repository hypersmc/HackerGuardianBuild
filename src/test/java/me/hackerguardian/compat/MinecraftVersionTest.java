package me.hackerguardian.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftVersionTest {

    @Test
    void acceptsVerifiedHistoricalAndYearNumbering() {
        assertVerified("1.20", MinecraftVersion.Family.MC_1_20);
        assertVerified("1.20.6", MinecraftVersion.Family.MC_1_20);
        assertVerified("1.21.11", MinecraftVersion.Family.MC_1_21);
        assertVerified("26.1", MinecraftVersion.Family.MC_26);
        assertVerified("26.1.2", MinecraftVersion.Family.MC_26);
        assertVerified("26.2", MinecraftVersion.Family.MC_26);
    }

    @Test
    void understandsBukkitStyleVersionText() {
        MinecraftVersion version = MinecraftVersion.parse("git-Paper-123 (MC: 1.21.8)");
        assertEquals("1.21.8", version.normalized());
        assertEquals(MinecraftVersion.Family.MC_1_21, version.family());
        assertTrue(version.isVerifiedSupported());
    }

    @Test
    void rejectsLegacyServersAndFailClosesPacketSupportOnFutureVersions() {
        MinecraftVersion legacy = MinecraftVersion.parse("1.19.4");
        assertEquals(MinecraftVersion.SupportLevel.UNSUPPORTED, legacy.supportLevel());
        assertFalse(legacy.allowsCoreRuntime());

        MinecraftVersion futureDrop = MinecraftVersion.parse("26.3");
        assertEquals(MinecraftVersion.SupportLevel.UNVERIFIED_FUTURE, futureDrop.supportLevel());
        assertTrue(futureDrop.allowsCoreRuntime());
        assertFalse(futureDrop.isVerifiedSupported());

        MinecraftVersion futureYear = MinecraftVersion.parse("27.1");
        assertEquals(MinecraftVersion.SupportLevel.UNVERIFIED_FUTURE, futureYear.supportLevel());
    }

    @Test
    void doesNotPretendUnknownStringsAreSupported() {
        MinecraftVersion unknown = MinecraftVersion.parse("not-a-minecraft-version");
        assertEquals("unknown", unknown.normalized());
        assertEquals(MinecraftVersion.Family.UNKNOWN, unknown.family());
        assertEquals(MinecraftVersion.SupportLevel.UNSUPPORTED, unknown.supportLevel());
    }

    private static void assertVerified(String raw, MinecraftVersion.Family family) {
        MinecraftVersion version = MinecraftVersion.parse(raw);
        assertEquals(family, version.family());
        assertEquals(MinecraftVersion.SupportLevel.VERIFIED_SUPPORTED, version.supportLevel());
        assertTrue(version.isVerifiedSupported());
    }
}
