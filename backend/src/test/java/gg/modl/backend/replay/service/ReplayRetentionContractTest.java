package gg.modl.backend.replay.service;

import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENTS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_MODIFICATIONS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_TICKETS;
import static gg.modl.backend.database.jooq.Tables.REPLAYS;
import static gg.modl.backend.database.jooq.Tables.TICKETS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.database.UnitFrame;
import gg.modl.backend.database.jooq.tables.records.PlayersRecord;
import gg.modl.backend.database.jooq.tables.records.PunishmentModificationsRecord;
import gg.modl.backend.database.jooq.tables.records.PunishmentTicketsRecord;
import gg.modl.backend.database.jooq.tables.records.PunishmentsRecord;
import gg.modl.backend.database.jooq.tables.records.ReplaysRecord;
import gg.modl.backend.database.jooq.tables.records.TicketsRecord;
import gg.modl.backend.player.data.punishment.PunishmentModificationType;
import gg.modl.backend.player.data.punishment.PunishmentState;
import gg.modl.backend.player.repository.JooqPunishmentRepository;
import gg.modl.backend.replay.data.ReplayDocument;
import gg.modl.backend.replay.data.ReplayShare;
import gg.modl.backend.replay.repository.JooqReplayRepository;
import gg.modl.backend.replay.repository.JooqReplayShareRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.ReplayRetentionSettings;
import gg.modl.backend.settings.service.ReplayRetentionSettingsService;
import gg.modl.backend.storage.service.StorageDeletion;
import gg.modl.backend.storage.service.StorageDeletionService;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.backend.ticket.repository.JooqTicketRepository;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.io.TempDir;

class ReplayRetentionContractTest {
    private static final Instant NOW = SqlTestDatabase.START;
    private static final Instant EXPIRED = NOW.minus(Duration.ofDays(20));
    private static final long ONE_DAY = Duration.ofDays(1).toMillis();

    private final Server server = TestServers.server("server", "admin@example.com");
    private final List<String> deletedObjects = new ArrayList<>();

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void unresolvedTicketsAndActiveOrUnstartedPunishmentsHoldTheirReplays(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            Seed seed = new Seed(database);
            seed.replayWithTicket("open", "open");
            seed.replayWithTicket("unfinished", "unfinished");
            seed.replayWithTicket("closed", "closed");
            seed.replayWithPunishment("permanent", null, NOW.minusSeconds(60), null, PunishmentState.MAX);
            seed.replayWithPunishment("running", null, NOW.minusSeconds(60), ONE_DAY, NOW.plusSeconds(60));
            seed.replayWithPunishment("ends-now", null, NOW.minusSeconds(60), 60_000L, NOW);
            seed.replayWithPunishment("not-started", null, null, ONE_DAY, PunishmentState.MAX);
            seed.replayWithPunishment("expired", null, EXPIRED, ONE_DAY, NOW.minusMillis(1));
            seed.replayWithPunishment("unstarted", "Unstarted", null, ONE_DAY, PunishmentState.INACTIVE);
            List<String> pardoned = new ArrayList<>();
            for (PunishmentModificationType type : PunishmentModificationType.values()) {
                String id = "unstarted-" + type.name();
                seed.replayWithPunishment(id, "Unstarted", null, ONE_DAY, PunishmentState.INACTIVE);
                seed.modification(id, type);
                if (type.endsEnforcement()) {
                    pardoned.add(id);
                }
            }
            seed.replay("unreferenced", EXPIRED);
            seed.replay("recent", NOW.minus(Duration.ofDays(9)));

            long purged = task(database).purge(server, NOW);

            List<String> removed = new ArrayList<>(pardoned);
            removed.addAll(List.of("closed", "expired", "unreferenced"));
            assertThat(purged).isEqualTo(removed.size());
            List<String> kept = new ArrayList<>(List.of(
                "open", "unfinished", "permanent", "running", "ends-now", "not-started", "unstarted", "recent"));
            for (PunishmentModificationType type : PunishmentModificationType.values()) {
                if (!type.endsEnforcement()) {
                    kept.add("unstarted-" + type.name());
                }
            }
            assertThat(storedReplayIds(database)).containsExactlyInAnyOrderElementsOf(kept);
            assertThat(deletedObjects).containsExactlyInAnyOrderElementsOf(removed.stream().map(Seed::keyOf).toList());
        }
    }

    @SqlContractTest
    void aTimedPunishmentHoldsItsReplayUntilItHasRunFromItsLateStart(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            Seed seed = new Seed(database);
            seed.replayWithPunishment("offline", null, null, ONE_DAY, PunishmentState.MAX);
            database.clock().advance(Duration.ofDays(2));
            Instant startedAt = database.clock().instant();

            assertThat(task(database).purge(server, startedAt)).isZero();

            database.unitOfWork().write(() -> database.dsl().update(PUNISHMENTS)
                .set(PUNISHMENTS.STARTED_AT, startedAt)
                .set(PUNISHMENTS.ACTIVE_UNTIL_AT, startedAt.plusMillis(ONE_DAY))
                .where(PUNISHMENTS.ID.eq("offline"))
                .execute());
            database.clock().advance(Duration.ofDays(1));
            assertThat(task(database).purge(server, database.clock().instant())).isZero();

            database.clock().advance(Duration.ofMillis(1));
            assertThat(task(database).purge(server, database.clock().instant())).isEqualTo(1);
            assertThat(storedReplayIds(database)).isEmpty();
        }
    }

    @SqlContractTest
    void pagesOfHeldReplaysDoNotStarveTheOnesBehindThem(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            Seed seed = new Seed(database);
            int held = ReplayRetentionTask.PAGE_SIZE * 3;
            database.unitOfWork().write(() -> IntStream.range(0, held)
                .forEach(index -> seed.replayWithTicket("held-" + index, "open")));
            seed.replay("free", EXPIRED.plusSeconds(1));

            assertThat(task(database).purge(server, NOW)).isEqualTo(1);

            assertThat(storedReplayIds(database)).hasSize(held).doesNotContain("free");
            assertThat(deletedObjects).containsExactly(Seed.keyOf("free"));
        }
    }

    @SqlContractTest
    void aReplayWithoutAStoredObjectIsPurgedAndUnlinkedFromItsTicket(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            Seed seed = new Seed(database);
            seed.replayWithTicket("keyless", "closed");
            database.unitOfWork().write(() -> database.dsl().update(REPLAYS).setNull(REPLAYS.STORAGE_KEY).execute());

            assertThat(task(database).purge(server, NOW)).isEqualTo(1);

            assertThat(storedReplayIds(database)).isEmpty();
            assertThat(database.dsl().select(TICKETS.REPLAY_ID).from(TICKETS).fetchSingle().value1()).isNull();
            assertThat(database.dsl().select(TICKETS.REPLAY_URL).from(TICKETS).fetchSingle().value1()).isNull();
            assertThat(deletedObjects).isEmpty();
        }
    }

    @SqlContractTest
    void aLinkThatNeverExpiresHoldsItsReplayUntilItIsRevoked(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            Seed seed = new Seed(database);
            seed.replay("shared", EXPIRED);
            seed.replay("lapsed-link", EXPIRED);
            JooqReplayShareRepository shares = new JooqReplayShareRepository(database.dsl(), database.childRows());
            database.unitOfWork().write(() -> {
                shares.insertWithinLimit(new ReplayShare("s1", "shared", "a".repeat(64), "Moderator", EXPIRED, null, null, 0L, null),
                    25, 25, EXPIRED);
                shares.insertWithinLimit(new ReplayShare("s2", "lapsed-link", "b".repeat(64), "Moderator", EXPIRED,
                    NOW.minusSeconds(1), null, 0L, null), 25, 25, EXPIRED);
            });

            assertThat(task(database).purge(server, NOW)).isEqualTo(1);
            assertThat(storedReplayIds(database)).containsExactly("shared");

            database.unitOfWork().write(() -> shares.revoke("s1", NOW));
            assertThat(task(database).purge(server, NOW)).isEqualTo(1);
            assertThat(storedReplayIds(database)).isEmpty();
            assertThat(deletedObjects).containsExactlyInAnyOrder(Seed.keyOf("lapsed-link"), Seed.keyOf("shared"));
        }
    }

    private ReplayRetentionTask task(SqlTestDatabase database) {
        JooqReplayRepository replays = new JooqReplayRepository(database.dsl(), database.jsonColumns());
        JooqTicketRepository tickets = new JooqTicketRepository(database.dsl(), database.unitOfWork(), database.insertIfAbsent(),
            database.childRows(), database.jsonColumns());
        JooqPunishmentRepository punishments = new JooqPunishmentRepository(database.dsl(), database.unitOfWork(),
            database.insertIfAbsent(), database.childRows(), database.jsonColumns());
        ReplayRetentionSettingsService settings = mock(ReplayRetentionSettingsService.class);
        when(settings.getReplayRetentionSettings(server)).thenReturn(new ReplayRetentionSettings(true, 10));
        StorageDeletionService storage = mock(StorageDeletionService.class);
        doAnswer(invocation -> {
            assertThat(UnitFrame.current()).isEmpty();
            Collection<String> keys = invocation.getArgument(1);
            deletedObjects.addAll(keys);
            return StorageDeletion.NOTHING;
        }).when(storage).delete(any(), anyCollection());
        ReplayDeletionService deletion =
            new ReplayDeletionService(new ReplayRecordPurger(replays, tickets, database.unitOfWork()), storage);
        List<ReplayHold> holds = List.of(new OpenTicketReplayHold(tickets),
            new ActivePunishmentReplayHold(tickets, punishments, database.clock()),
            new OpenShareReplayHold(new JooqReplayShareRepository(database.dsl(), database.childRows()), database.clock()));
        return new ReplayRetentionTask(settings, replays, holds, deletion);
    }

    private static List<String> storedReplayIds(SqlTestDatabase database) {
        return database.dsl().select(REPLAYS.ID).from(REPLAYS).fetch(REPLAYS.ID);
    }

    private static final class Seed {
        private static final String PLAYER_ID = "player";

        private final SqlTestDatabase database;

        private Seed(SqlTestDatabase database) {
            this.database = database;
            PlayersRecord player = new PlayersRecord();
            player.setId(PLAYER_ID);
            player.setMinecraftUuid("3f8c9c5a-6b6e-4f2c-9b7f-1a2b3c4d5e6f");
            player.setUsername("Player");
            player.setUsernameLower("player");
            player.setCreatedAt(NOW);
            database.unitOfWork().write(() -> database.dsl().insertInto(PLAYERS).set(player).execute());
        }

        static String keyOf(String replayId) {
            return "modl/replays/" + replayId + ".modlreplay";
        }

        void replay(String id, Instant createdAt) {
            ReplaysRecord replay = new ReplaysRecord();
            replay.setId(id);
            replay.setStatus(ReplayDocument.STATUS_COMPLETE);
            replay.setStorageKey(keyOf(id));
            replay.setFileSize(1L);
            replay.setCreatedAt(createdAt);
            database.unitOfWork().write(() -> database.dsl().insertInto(REPLAYS).set(replay).execute());
        }

        void replayWithTicket(String id, String ticketStatus) {
            replay(id, EXPIRED);
            TicketsRecord ticket = new TicketsRecord();
            ticket.setId("ticket-" + id);
            ticket.setType("report");
            ticket.setStatus(ticketStatus);
            ticket.setReplayId(id);
            ticket.setReplayUrl("/replay?id=" + id);
            ticket.setPublicAccessKey("access-" + id);
            ticket.setCreatedAt(EXPIRED);
            ticket.setUpdatedAt(EXPIRED);
            database.unitOfWork().write(() -> database.dsl().insertInto(TICKETS).set(ticket).execute());
        }

        void replayWithPunishment(String id, @Nullable String status, @Nullable Instant startedAt, @Nullable Long durationMs,
                                  Instant activeUntil) {
            replayWithTicket(id, "closed");
            PunishmentsRecord punishment = new PunishmentsRecord();
            punishment.setId(id);
            punishment.setPlayerId(PLAYER_ID);
            punishment.setTypeOrdinal(2);
            punishment.setIssuedAt(EXPIRED);
            punishment.setStatus(status);
            punishment.setStartedAt(startedAt);
            punishment.setDurationMs(durationMs);
            punishment.setEnforcementCategory("BAN");
            punishment.setActiveUntilAt(activeUntil);
            database.unitOfWork().write(() -> {
                database.dsl().insertInto(PUNISHMENTS).set(punishment).execute();
                database.dsl().insertInto(PUNISHMENT_TICKETS).set(new PunishmentTicketsRecord(id, "ticket-" + id)).execute();
            });
        }

        void modification(String punishmentId, PunishmentModificationType type) {
            PunishmentModificationsRecord modification = new PunishmentModificationsRecord(
                punishmentId, 1, "modification", type.name(), "Staff", null, "reason", null, null, null, NOW);
            database.unitOfWork().write(() -> database.dsl().insertInto(PUNISHMENT_MODIFICATIONS).set(modification).execute());
        }
    }
}
