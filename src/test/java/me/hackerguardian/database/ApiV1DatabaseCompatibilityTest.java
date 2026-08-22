package me.hackerguardian.database;

import me.hackerguardian.api.learning.LearningPlayerStatusRepository;
import me.hackerguardian.api.moderation.ModerationApiRepository;
import me.hackerguardian.api.replays.ReplayApiRepository;
import me.hackerguardian.api.status.ServerStatusRepository;
import me.hackerguardian.main.detection.journal.DetectionEventRepository;
import me.hackerguardian.main.moderation.punish.ModerationActionType;
import me.hackerguardian.main.moderation.punish.PunishScope;
import me.hackerguardian.main.moderation.punish.PunishmentRepository;
import me.hackerguardian.main.moderation.punish.PunishmentType;
import me.hackerguardian.main.replay.ReplayStorage;
import me.hackerguardian.main.replay.ReplayTriggerType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the new API v1 read/status schema against both supported databases. */
class ApiV1DatabaseCompatibilityTest {

    @Test
    void mysqlApiRepositories() throws Exception {
        verify(DatabaseType.MYSQL, "HG_TEST_MYSQL_");
    }

    @Test
    void postgresqlApiRepositories() throws Exception {
        verify(DatabaseType.POSTGRESQL, "HG_TEST_POSTGRES_");
    }

    private void verify(DatabaseType type, String prefix) throws Exception {
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
            DataSource dataSource = database.dataSource();
            PunishmentRepository punishments = new PunishmentRepository(dataSource);
            punishments.ensureTables();
            new me.hackerguardian.main.report.ReportRepository(dataSource).ensureTables();
            ReplayStorage replayStorage = new ReplayStorage(dataSource, "api-ci-" + type.name().toLowerCase());
            replayStorage.ensureTables();

            ServerStatusRepository serverStatus = new ServerStatusRepository(dataSource);
            serverStatus.ensureTable();
            LearningPlayerStatusRepository learningStatus = new LearningPlayerStatusRepository(dataSource);
            learningStatus.ensureTable();
            DetectionEventRepository detectionEvents = new DetectionEventRepository(dataSource);
            detectionEvents.ensureTable();

            verifyServerStatus(serverStatus, type);
            verifyLearningStatus(learningStatus, type);
            verifyDetectionJournal(detectionEvents, type);
            verifyReplayApi(dataSource, replayStorage, type);
            verifyModerationApi(dataSource, punishments, type);
        } finally {
            database.close();
        }
    }

    private void verifyServerStatus(ServerStatusRepository repository, DatabaseType type) throws Exception {
        String name = "api-status-" + type.name().toLowerCase();
        long now = System.currentTimeMillis();
        repository.heartbeat(new ServerStatusRepository.Status(
                name,
                "test-version",
                "26.2",
                12,
                true,
                11,
                "snapshot|combat.reach-envelope,deterministic|mining.fast-break",
                true,
                3,
                42.5,
                1,
                true,
                now
        ));

        ServerStatusRepository.Status stored = repository.list().stream()
                .filter(status -> name.equals(status.serverName()))
                .findFirst()
                .orElseThrow();
        assertEquals(12, stored.playersOnline());
        assertTrue(stored.detectionEnabled());
        assertEquals(11, stored.trackedPlayers());
        assertTrue(stored.detectorIds().contains("mining.fast-break"));
        assertEquals(42.5, stored.learningActiveHours(), 1.0E-9);

        repository.markOffline(name);
        ServerStatusRepository.Status offline = repository.list().stream()
                .filter(status -> name.equals(status.serverName()))
                .findFirst()
                .orElseThrow();
        assertEquals(0L, offline.lastSeenMs());
        assertEquals(0, offline.playersOnline());
    }

    private void verifyLearningStatus(LearningPlayerStatusRepository repository, DatabaseType type) throws Exception {
        String server = "api-learning-" + type.name().toLowerCase();
        String player = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        repository.upsertAll(List.of(new LearningPlayerStatusRepository.PlayerStatus(
                server,
                player,
                "Learner",
                true,
                12.75,
                now - 100_000L,
                now,
                now - 10_000L,
                true,
                now
        )));

        LearningPlayerStatusRepository.PlayerStatus stored = repository.get(player, server);
        assertNotNull(stored);
        assertTrue(stored.trusted());
        assertTrue(stored.baselineMature());
        assertEquals(12.75, stored.activeHours(), 1.0E-9);
        assertTrue(repository.list(server, true, 10).stream()
                .anyMatch(status -> player.equals(status.playerUuid())));
    }

    private void verifyDetectionJournal(DetectionEventRepository repository, DatabaseType type) throws Exception {
        String player = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        repository.insert(new DetectionEventRepository.Event(
                eventId,
                "api-detection-" + type.name().toLowerCase(),
                now,
                player,
                "Suspect",
                0.88,
                0.97,
                "mining.fast-break",
                "BLOCK",
                "STRONG",
                0.95,
                0.98,
                Map.of("observed_ms", 120.0, "vanilla_minimum_ms", 600.0),
                null
        ));

        List<DetectionEventRepository.Event> recent = repository.recent(null, player, null, null, 10);
        assertFalse(recent.isEmpty());
        DetectionEventRepository.Event stored = recent.stream()
                .filter(event -> eventId.equals(event.eventId()))
                .findFirst()
                .orElseThrow();
        assertEquals("STRONG", stored.evidenceStrength());
        assertEquals(120.0, stored.metadata().get("observed_ms"), 1.0E-9);
    }

    private void verifyReplayApi(DataSource dataSource, ReplayStorage storage, DatabaseType type) throws Exception {
        String player = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        long replayId = storage.createReplay(
                player,
                "ReplayApiUser",
                now,
                ReplayTriggerType.DETECTION,
                "detector=mining.fast-break;strength=STRONG",
                null,
                1,
                "gzip"
        );
        storage.appendChunk(replayId, 0, now - 1000L, now + 1000L, new byte[]{1, 2, 3});
        storage.finishReplay(replayId, now + 1000L);

        ReplayApiRepository repository = new ReplayApiRepository(dataSource);
        ReplayApiRepository.Filters filters = new ReplayApiRepository.Filters();
        filters.playerUuid = player;
        filters.page = 1;
        filters.perPage = 10;
        ReplayApiRepository.Page page = repository.list(filters);
        assertTrue(page.replays().stream().anyMatch(replay -> replay.id() == replayId));

        ReplayApiRepository.ReplayRecord replay = repository.get(replayId);
        assertNotNull(replay);
        assertEquals("DETECTION", replay.triggerType());
        assertEquals(1, replay.chunkCount());
        assertNotNull(repository.chunk(replayId, 0));
    }

    private void verifyModerationApi(DataSource dataSource,
                                     PunishmentRepository punishments,
                                     DatabaseType type) throws Exception {
        String target = UUID.randomUUID().toString();
        String actor = UUID.randomUUID().toString();
        String server = "api-mod-" + type.name().toLowerCase();
        long now = System.currentTimeMillis();
        long banId = punishments.createPunishment(
                PunishmentType.BAN,
                target,
                "Target",
                actor,
                "Moderator",
                "api-integration-test",
                now,
                null,
                PunishScope.SERVER,
                server
        );
        punishments.logAction(
                ModerationActionType.BAN,
                target,
                "Target",
                null,
                actor,
                "Moderator",
                "api-integration-test",
                now,
                null,
                banId,
                PunishScope.SERVER,
                server
        );

        ModerationApiRepository repository = new ModerationApiRepository(dataSource);
        ModerationApiRepository.Filters filters = new ModerationApiRepository.Filters();
        filters.targetUuid = target;
        filters.server = server;
        filters.page = 1;
        filters.perPage = 10;
        ModerationApiRepository.Page page = repository.list(filters);
        assertTrue(page.total() >= 1);
        ModerationApiRepository.Action action = page.actions().stream()
                .filter(value -> target.equals(value.targetUuid()))
                .findFirst()
                .orElseThrow();
        assertEquals("BAN", action.type());
        assertEquals(Boolean.TRUE, action.active());
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
