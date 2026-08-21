package me.hackerguardian.database;

import me.hackerguardian.api.reports.ReportDto;
import me.hackerguardian.main.moderation.punish.ModerationActionType;
import me.hackerguardian.main.moderation.punish.PunishScope;
import me.hackerguardian.main.moderation.punish.PunishmentRepository;
import me.hackerguardian.main.moderation.punish.PunishmentType;
import me.hackerguardian.main.replay.ReplayStorage;
import me.hackerguardian.main.replay.ReplayTriggerType;
import me.hackerguardian.main.report.ReportRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the same HackerGuardian schema/CRUD smoke test against both SQL engines.
 * The tests skip locally unless the HG_TEST_* environment variables are set.
 */
public class DatabaseCompatibilityTest {

    @Test
    void mysqlCompatibility() throws Exception {
        verifyProvider(DatabaseType.MYSQL, "HG_TEST_MYSQL_");
    }

    @Test
    void postgresqlCompatibility() throws Exception {
        verifyProvider(DatabaseType.POSTGRESQL, "HG_TEST_POSTGRES_");
    }

    private void verifyProvider(DatabaseType type, String prefix) throws Exception {
        String host = System.getenv(prefix + "HOST");
        Assumptions.assumeTrue(host != null && !host.isBlank(),
                "Database integration environment is not configured for " + type);

        DatabaseSettings settings = new DatabaseSettings(
                type,
                host,
                intEnv(prefix + "PORT", type.defaultPort()),
                requiredEnv(prefix + "DATABASE"),
                requiredEnv(prefix + "USERNAME"),
                requiredEnv(prefix + "PASSWORD"),
                false,
                4,
                1,
                10_000L,
                5_000L,
                60_000L,
                600_000L
        );

        HikariDatabase database = new HikariDatabase(settings);
        database.start();
        try {
            DataSource ds = database.dataSource();

            try (Connection c = database.connection()) {
                assertEquals(type, SqlSchema.detectType(c));
                CoreDatabaseSchema.ensure(c);
            }

            PunishmentRepository punishments = new PunishmentRepository(ds);
            punishments.ensureTables();

            ReportRepository reports = new ReportRepository(ds);
            reports.ensureTables();

            ReplayStorage replays = new ReplayStorage(ds, "ci-" + type.name().toLowerCase());
            replays.ensureTables();

            verifyPunishments(ds, punishments);
            long reportId = verifyReports(reports);
            verifyReportApi(ds, reportId);
            verifyReplays(replays);
        } finally {
            database.close();
        }
    }

    private void verifyPunishments(DataSource ds, PunishmentRepository repo) throws Exception {
        String targetUuid = UUID.randomUUID().toString();
        String actorUuid = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();

        long banId = repo.createPunishment(
                PunishmentType.BAN,
                targetUuid,
                "Target",
                actorUuid,
                "Tester",
                "integration-test",
                now,
                null,
                PunishScope.WIDE,
                null
        );
        assertTrue(banId > 0);
        assertTrue(repo.getActivePunishment(PunishmentType.BAN, targetUuid, now, true, "ci").isPresent());

        String ip = "203.0.113." + (Math.abs(targetUuid.hashCode()) % 200 + 1);
        long firstIpBan = repo.createIpBan(
                ip, actorUuid, "Tester", "integration-test", now, null, PunishScope.WIDE, null
        );
        assertTrue(firstIpBan > 0);
        assertTrue(repo.deactivateIpBan(ip));

        // This specifically guards against the old UNIQUE(ip, active) schema design,
        // which prevented more than one historical inactive ban for the same IP.
        long secondIpBan = repo.createIpBan(
                ip, actorUuid, "Tester", "integration-test-2", now + 1, null, PunishScope.WIDE, null
        );
        assertTrue(secondIpBan > 0);
        assertNotEquals(firstIpBan, secondIpBan);

        long logId = repo.logAction(
                ModerationActionType.BAN,
                targetUuid,
                "Target",
                null,
                actorUuid,
                "Tester",
                "integration-test",
                now,
                null,
                banId,
                PunishScope.WIDE,
                null
        );
        assertTrue(logId > 0);

        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT scope, scope_server FROM hg_moderation_log WHERE id = ?"
             )) {
            ps.setLong(1, logId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("WIDE", rs.getString("scope"));
                assertEquals(null, rs.getString("scope_server"));
            }
        }
    }

    private long verifyReports(ReportRepository repo) throws Exception {
        long now = System.currentTimeMillis();
        long reportId = repo.createReport(
                UUID.randomUUID().toString(),
                "Reported",
                UUID.randomUUID().toString(),
                "Reporter",
                "integration-test",
                now
        );
        assertTrue(reportId > 0);
        assertTrue(repo.getReport(reportId).isPresent());

        repo.addComment(
                reportId,
                UUID.randomUUID().toString(),
                "Reviewer",
                "portable comment",
                now + 1
        );
        assertEquals(1, repo.listComments(reportId).size());
        assertTrue(repo.setStatus(reportId, "CLOSED", UUID.randomUUID().toString(), now + 2));
        assertEquals("CLOSED", repo.getReport(reportId).orElseThrow().status());
        return reportId;
    }

    private void verifyReportApi(DataSource ds, long reportId) throws Exception {
        me.hackerguardian.api.reports.ReportRepository apiRepo =
                new me.hackerguardian.api.reports.ReportRepository(ds);

        me.hackerguardian.api.reports.ReportRepository.Page<ReportDto> page =
                apiRepo.list("all", String.valueOf(reportId), 1, 10);

        assertTrue(page.total >= 1);
        assertFalse(page.data.isEmpty());
        assertTrue(page.data.stream().anyMatch(report -> report.id == reportId));
    }

    private void verifyReplays(ReplayStorage storage) throws Exception {
        long now = System.currentTimeMillis();
        long replayId = storage.createReplay(
                UUID.randomUUID().toString(),
                "ReplayUser",
                now,
                ReplayTriggerType.MANUAL,
                "integration-test",
                null,
                1,
                "gzip"
        );
        assertTrue(replayId > 0);

        byte[] chunk = new byte[]{1, 2, 3, 4};
        storage.appendChunk(replayId, 0, 0, 1000, chunk);
        List<ReplayStorage.ReplayChunk> chunks = storage.getChunks(replayId);
        assertEquals(1, chunks.size());
        assertArrayEquals(chunk, ReplayStorage.gunzip(chunks.get(0).data));

        byte[] firstWorld = new byte[]{5, 6, 7};
        byte[] replacementWorld = new byte[]{8, 9, 10, 11};
        storage.upsertWorldChunk(replayId, "world", 0, 0, firstWorld);
        storage.upsertWorldChunk(replayId, "world", 0, 0, replacementWorld);

        List<ReplayStorage.WorldChunkSnapshot> worldChunks = storage.getWorldChunks(replayId);
        assertEquals(1, worldChunks.size());
        assertArrayEquals(replacementWorld, ReplayStorage.gunzip(worldChunks.get(0).data));

        storage.finishReplay(replayId, now + 2000);
        ReplayStorage.ReplayMeta meta = storage.getReplayMeta(replayId);
        assertNotNull(meta);
        assertNotNull(meta.endedAt);
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }

    private static int intEnv(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) return fallback;
        return Integer.parseInt(value);
    }
}
