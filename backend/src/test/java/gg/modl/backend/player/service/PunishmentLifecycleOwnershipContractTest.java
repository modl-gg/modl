package gg.modl.backend.player.service;

import static gg.modl.backend.player.PunishmentStore.BAN;
import static gg.modl.backend.player.PunishmentStore.KICK;
import static gg.modl.backend.player.PunishmentStore.MUTE;
import static gg.modl.backend.player.PunishmentStore.NOW;
import static gg.modl.backend.player.PunishmentStore.draft;
import static gg.modl.backend.player.service.PunishmentTypesFixture.CONFIGURED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import gg.modl.backend.player.data.punishment.PunishmentIssuerKind;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.scheduling.ScheduledDatabaseJobs;
import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.iplookup.service.AddressForwardingMonitor;
import gg.modl.backend.iplookup.service.IpLookupService;
import gg.modl.backend.player.PlayerService;
import gg.modl.backend.player.PunishmentStore;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentNote;
import gg.modl.backend.player.data.punishment.PunishmentState;
import gg.modl.backend.player.data.punishment.PunishmentStatus;
import gg.modl.backend.player.data.punishment.ResolvedCategory;
import gg.modl.backend.player.dto.request.CreateEvidenceRequest;
import gg.modl.backend.player.dto.request.CreateNoteRequest;
import gg.modl.backend.player.dto.request.CreatePunishmentRequest;
import gg.modl.backend.player.dto.response.SimplePunishmentView;
import gg.modl.backend.player.repository.PunishmentRecord;
import gg.modl.backend.player.service.PunishmentQueryService.PunishmentOperationStatus;
import gg.modl.backend.role.service.MinecraftIssuerAuthorizer;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.backend.ticket.repository.TicketRepository;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;

class PunishmentLifecycleOwnershipContractTest {
    private static final long DAY = Duration.ofDays(1).toMillis();
    private static final PunishmentIssuer OWNER = PunishmentIssuer.staff("Owner", null);

    private final Server server = TestServers.server("Demo", "owner@example.com");
    private final PunishmentTypesFixture types = new PunishmentTypesFixture();

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void aStaffPardonedLinkedBanIsNotIssuedAgainAtTheNextLogin(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            PunishmentServices services = services(database);
            PunishmentStore store = services.store();
            String alt = givenAltBlockedParent(store, "main", "alt", "PARENTID");
            String linkedId = login(database, services, alt).getFirst().id();

            services.lifecycle().pardonPunishment(server, linkedId, "Moderator", null, "shared house", null);

            assertThat(login(database, services, alt)).isEmpty();
            assertThat(store.punishments().findLinkedBans("PARENTID")).singleElement()
                .satisfies(linked -> assertThat(linked.punishment().isPardoned()).isTrue());
        }
    }

    @SqlContractTest
    void theLoginOfAnAltWhoseLinkedBanStaffPardonedTakesNoWriteUnit(DialectTarget target) {
        AtomicInteger commits = new AtomicInteger();
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, attempt -> commits.incrementAndGet())) {
            PunishmentServices services = services(database);
            String alt = givenAltBlockedParent(services.store(), "main", "alt", "PARENTID");
            String linkedId = login(database, services, alt).getFirst().id();
            services.lifecycle().pardonPunishment(server, linkedId, "Moderator", null, "shared house", null);
            Player snapshot = Player.builder().id(alt).minecraftUuid(PunishmentStore.uuidOf(alt)).build();
            int before = commits.get();

            assertThat(services.lifecycle().enforceAltBlockingBans(server, snapshot)).isEmpty();

            assertThat(commits.get()).isEqualTo(before);
        }
    }

    @SqlContractTest
    void aLoginRacingAParentPardonNeverLeavesTheAltBanned(DialectTarget target) throws Exception {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentServices services = services(database);
            PunishmentStore store = services.store();
            for (int round = 0; round < 8; round++) {
                String parentId = "PARENT0" + round;
                String alt = givenAltBlockedParent(store, "main" + round, "alt" + round, parentId);
                Player snapshot = Player.builder().id(alt).minecraftUuid(PunishmentStore.uuidOf(alt)).build();
                CountDownLatch start = new CountDownLatch(1);
                try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                    Future<?> login = executor.submit(() -> {
                        start.await();
                        return services.lifecycle().enforceAltBlockingBans(server, snapshot);
                    });
                    Future<?> pardon = executor.submit(() -> {
                        start.await();
                        return services.lifecycle().pardonPunishment(server, parentId, "Moderator", null, "appeal", null);
                    });
                    start.countDown();
                    login.get();
                    pardon.get();
                }

                assertThat(store.punishments().findLinkedBans(parentId))
                    .allSatisfy(linked -> assertThat(services.statusCalculator().isPunishmentActive(linked.punishment())).isFalse());
                assertThat(login(database, services, alt)).isEmpty();
            }
        }
    }

    @SqlContractTest
    void aPardonStartsTheQueuedMuteOfAnOnlinePlayer(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            PunishmentServices services = services(database);
            PunishmentStore store = services.store();
            store.addPlayers(List.of("muted"), true);
            store.issue("muted", draft("ACTIVEMU", MUTE, null, NOW, null), ResolvedCategory.MUTE);
            store.issue("muted", draft("WAITINGM", MUTE, DAY, null, PunishmentStatus.UNSTARTED), ResolvedCategory.MUTE);

            services.lifecycle().pardonPunishment(server, "ACTIVEMU", "Moderator", null, "done", null);

            assertThat(store.punishment("WAITINGM").data().status()).isNull();
            verify(services.realtime()).punishmentsPromoted(eq(server), argThat(promoted -> ids(promoted).equals(List.of("WAITINGM"))));
        }
    }

    @SqlContractTest
    void anExpiredMuteHandsOverToTheQueuedOneWhileThePlayerIsOnline(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            PunishmentServices services = services(database);
            PunishmentStore store = services.store();
            store.addPlayers(List.of("muted"), true);
            store.issue("muted", draft("ACTIVEMU", MUTE, DAY, NOW, null), ResolvedCategory.MUTE);
            store.issue("muted", draft("WAITINGM", MUTE, DAY, null, PunishmentStatus.UNSTARTED), ResolvedCategory.MUTE);
            QueuedPunishmentPromotionJob job = new QueuedPunishmentPromotionJob(store.punishments(), promotion(database, services),
                mock(InstanceContext.class), mock(ScheduledDatabaseJobs.class), database.clock());

            assertThat(job.promoteBehindExpired(server)).isZero();
            database.clock().advance(Duration.ofDays(1).plusSeconds(1));

            assertThat(job.promoteBehindExpired(server)).isEqualTo(1);
            assertThat(store.punishment("WAITINGM").data().status()).isNull();
            assertThat(job.promoteBehindExpired(server)).isZero();
        }
    }

    @SqlContractTest
    void aQueuedMuteIsNeitherPushedNorStartedByAnAcknowledgement(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            PunishmentServices services = services(database);
            String player = services.store().addPlayer("muted");
            UUID uuid = PunishmentStore.uuidOf(player);
            String current = services.lifecycle().createPunishment(server, uuid, request(MUTE, DAY));
            services.lifecycle().acknowledgePunishment(server, uuid, current, true);

            String queued = services.lifecycle().createPunishment(server, uuid, request(MUTE, DAY));

            verify(services.realtime(), never()).punishmentIssued(eq(server), argThat(issued -> issued.punishment().getId().equals(queued)));
            verify(services.realtime()).punishmentQueued(eq(server), argThat(issued -> issued.punishment().getId().equals(queued)));
            assertThat(services.lifecycle().acknowledgePunishment(server, uuid, queued, true).status())
                .isEqualTo(PunishmentOperationStatus.NO_OP);
            Punishment waiting = services.store().punishment(queued);
            assertThat(waiting.getStarted()).isNull();
            assertThat(waiting.data().status()).isEqualTo(PunishmentStatus.UNSTARTED);
        }
    }

    @SqlContractTest
    void aQueuedMuteWithAChangedDurationRunsOnlyFromItsPromotion(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            PunishmentServices services = services(database);
            PunishmentStore store = services.store();
            store.addPlayers(List.of("muted"), true);
            store.issue("muted", draft("ACTIVEMU", MUTE, null, NOW, null), ResolvedCategory.MUTE);
            store.issue("muted", draft("WAITINGM", MUTE, 3 * DAY, null, PunishmentStatus.UNSTARTED), ResolvedCategory.MUTE);

            services.mutations().changeDuration(server, "WAITINGM", DAY, "Moderator", null, Shortenings.PERMITTED);

            Punishment waiting = store.punishment("WAITINGM");
            assertThat(waiting.getStarted()).isNull();
            assertThat(waiting.data().status()).isEqualTo(PunishmentStatus.UNSTARTED);

            database.clock().advance(Duration.ofDays(2));
            assertThat(services.statusCalculator().getEffectiveExpiry(store.punishment("WAITINGM")))
                .isEqualTo(database.clock().instant().plus(Duration.ofDays(1)));

            services.lifecycle().pardonPunishment(server, "ACTIVEMU", "Moderator", null, "done", null);

            Punishment promoted = store.punishment("WAITINGM");
            assertThat(promoted.data().status()).isNull();
            assertThat(promoted.getStarted()).isNull();
            assertThat(store.activeUntil("WAITINGM")).isEqualTo(PunishmentState.MAX);

            database.clock().advance(Duration.ofHours(1));
            services.lifecycle().acknowledgePunishment(server, PunishmentStore.uuidOf("muted"), "WAITINGM", true);
            assertThat(store.punishment("WAITINGM").getStarted()).isEqualTo(database.clock().instant());
            assertThat(store.activeUntil("WAITINGM")).isEqualTo(database.clock().instant().plus(Duration.ofDays(1)));
        }
    }

    @SqlContractTest
    void aDurationChangeOnABanThatHasNotStartedLeavesItsClockStoppedUntilItIsEnforced(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            PunishmentServices services = services(database);
            PunishmentStore store = services.store();
            store.addPlayers(List.of("offline"), false);
            store.issue("offline", draft("OFFLINEB", BAN, 7 * DAY, null, null), ResolvedCategory.BAN);

            services.mutations().changeDuration(server, "OFFLINEB", 3 * DAY, "Moderator", null, Shortenings.PERMITTED);

            assertThat(store.punishment("OFFLINEB").getStarted()).isNull();
            assertThat(store.activeUntil("OFFLINEB")).isEqualTo(PunishmentState.MAX);

            database.clock().advance(Duration.ofDays(5));
            services.lifecycle().acknowledgePunishment(server, PunishmentStore.uuidOf("offline"), "OFFLINEB", true);
            assertThat(store.punishment("OFFLINEB").getStarted()).isEqualTo(database.clock().instant());
            assertThat(store.activeUntil("OFFLINEB")).isEqualTo(database.clock().instant().plus(Duration.ofDays(3)));
        }
    }

    @SqlContractTest
    void anAcknowledgementCannotOvertakeAnOlderMuteThatHasNotStarted(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentServices services = services(database);
            String player = services.store().addPlayer("muted");
            UUID uuid = PunishmentStore.uuidOf(player);
            services.store().issue(player, draft("AAAAAAAA", MUTE, DAY, null, PunishmentStatus.UNSTARTED), ResolvedCategory.MUTE);
            services.store().issue(player, draft("ZZZZZZZZ", MUTE, DAY, null, PunishmentStatus.UNSTARTED), ResolvedCategory.MUTE);

            assertThat(services.lifecycle().acknowledgePunishment(server, uuid, "ZZZZZZZZ", true).status())
                .isEqualTo(PunishmentOperationStatus.NO_OP);
            assertThat(services.store().punishment("ZZZZZZZZ").getStarted()).isNull();
            assertThat(services.lifecycle().acknowledgePunishment(server, uuid, "AAAAAAAA", true).status())
                .isEqualTo(PunishmentOperationStatus.SUCCESS);
        }
    }

    @SqlContractTest
    void aFailedExecutionLeavesThePunishmentToBeDeliveredAgain(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            PunishmentServices services = services(database);
            String player = services.store().addPlayer("kicked");
            UUID uuid = PunishmentStore.uuidOf(player);
            String kick = services.lifecycle().createPunishment(server, uuid, request(KICK, null));

            assertThat(services.lifecycle().acknowledgePunishment(server, uuid, kick, false).status())
                .isEqualTo(PunishmentOperationStatus.NO_OP);

            assertThat(services.store().punishment(kick).getStarted()).isNull();
            assertThat(login(database, services, player)).extracting(SimplePunishmentView::id).contains(kick);
            assertThat(services.lifecycle().acknowledgePunishment(server, uuid, kick, true).status())
                .isEqualTo(PunishmentOperationStatus.SUCCESS);
            assertThat(services.store().punishment(kick).getStarted()).isNotNull();
        }
    }

    @SqlContractTest
    void aBanPardonLeavesAMuteAlone(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            PunishmentServices services = services(database);
            String player = services.store().addPlayer("muted");
            services.store().issue(player, draft("MUTEMUTE", MUTE, null, NOW, null), ResolvedCategory.MUTE);

            assertThatThrownBy(() -> services.lifecycle().pardonPunishment(server, "MUTEMUTE", "Mod", null, null, "ban"))
                .isInstanceOfSatisfying(ValidationException.class,
                    rejection -> assertThat(rejection.getMachineCode()).isEqualTo(PardonTargets.TYPE_MISMATCH));
            assertThat(services.store().punishment("MUTEMUTE").hasEndedEnforcement()).isFalse();

            assertThat(services.lifecycle().pardonPunishment(server, "MUTEMUTE", "Mod", null, null, "mute").success()).isTrue();
        }
    }

    @SqlContractTest
    void aCreationOverItsBoundsWritesNothing(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentServices services = services(database);
            String player = services.store().addPlayer("target");
            UUID uuid = PunishmentStore.uuidOf(player);
            List<CreateEvidenceRequest> evidence = Collections.nCopies(51,
                new CreateEvidenceRequest("seen", "text", null, null, null, null));
            List<CreatePunishmentRequest> oversized = List.of(
                new CreatePunishmentRequest("Mod", null, PunishmentIssuerKind.STAFF, BAN, null, evidence, null, null, null, null, "x", null),
                new CreatePunishmentRequest("Mod", null, PunishmentIssuerKind.STAFF, BAN, null, null, Collections.nCopies(51, "REPORT-1"), null, null, null, "x", null),
                new CreatePunishmentRequest("Mod", null, PunishmentIssuerKind.STAFF, BAN, List.of(new CreateNoteRequest("n".repeat(4001))),
                    null, null, null, null, null, "x", null),
                new CreatePunishmentRequest("Mod", null, PunishmentIssuerKind.STAFF, 999, null, null, null, null, null, null, "x", null));

            for (CreatePunishmentRequest request : oversized) {
                assertThatThrownBy(() -> services.lifecycle().createPunishment(server, uuid, request))
                    .isInstanceOf(ValidationException.class);
            }
            assertThat(services.store().punishments().findByPlayer(player)).isEmpty();
        }
    }

    @SqlContractTest
    void thePluginToggleRefusesAnOptionTheTypeDoesNotOffer(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            PunishmentServices services = services(database);
            String player = services.store().addPlayer("cheater");
            services.store().issue(player, draft("CHEATING", CONFIGURED, DAY, NOW, null), ResolvedCategory.BAN);

            assertThatThrownBy(() -> services.mutations().toggleOption(server, "CHEATING", "ALT_BLOCKING", true, "Mod", null, Shortenings.PERMITTED))
                .isInstanceOf(ValidationException.class);
            assertThat(services.store().punishment("CHEATING").data().altBlocking()).isFalse();
        }
    }

    @SqlContractTest
    void aRolledBackQueuedPunishmentLeavesTheQueue(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            PunishmentServices services = services(database);
            String player = services.store().addPlayer("queued");
            services.store().issue(player, draft("QUEUEDBA", BAN, DAY, null, PunishmentStatus.UNSTARTED), ResolvedCategory.BAN);

            services.lifecycle().rollbackPunishment(server, "QUEUEDBA", OWNER, "mistake");

            assertThat(services.store().punishment("QUEUEDBA").data().status()).isNull();
            assertThat(services.store().punishments().findEnforcementRelevant(player, database.clock().instant())).isEmpty();
        }
    }

    @SqlContractTest
    void aDurationOfZeroIsNotedAsPermanent(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentServices services = services(database);
            String player = services.store().addPlayer("muted");
            services.store().issue(player, draft("MUTEMUTE", MUTE, DAY, NOW, null), ResolvedCategory.MUTE);

            services.mutations().changeDuration(server, "MUTEMUTE", 0L, "Mod", null, Shortenings.PERMITTED);

            assertThat(services.store().punishment("MUTEMUTE").getNotes()).extracting(PunishmentNote::text)
                .contains("changed duration to permanent");
        }
    }

    private String givenAltBlockedParent(PunishmentStore store, String main, String alt, String parentId) {
        store.addPlayer(main);
        store.addPlayer(alt);
        store.link(main, alt);
        Punishment parent = draft(parentId, BAN, null, NOW, null);
        parent.data().setAltBlocking(true);
        store.issue(main, parent, ResolvedCategory.BAN);
        return alt;
    }

    private PunishmentServices services(SqlTestDatabase database) {
        return PunishmentServices.on(database, PunishmentStore.on(database), types.types(), types.thresholds());
    }

    private static PunishmentPromotion promotion(SqlTestDatabase database, PunishmentServices services) {
        return new PunishmentPromotion(database.unitOfWork(), database.clock(), services.store().punishments(),
            services.store().lock(), services.statusCalculator(), services.realtime());
    }

    private List<SimplePunishmentView> login(SqlTestDatabase database, PunishmentServices services, String player) {
        MinecraftPlayerService players = new MinecraftPlayerService(
            mock(PlayerService.class),
            services.store().players(),
            services.store().punishments(),
            mock(TicketRepository.class),
            services.statusCalculator(),
            types.types(),
            services.lifecycle(),
            mock(AccountLinkingService.class),
            services.issuerNameResolver(),
            mock(IpLookupService.class),
            mock(AddressForwardingMonitor.class),
            mock(MinecraftIssuerAuthorizer.class),
            database.clock());
        return players.login(server, PunishmentStore.uuidOf(player), "Player_" + player, null, null, null).activePunishments();
    }

    private static CreatePunishmentRequest request(int ordinal, Long duration) {
        return new CreatePunishmentRequest("Mod", null, PunishmentIssuerKind.STAFF, ordinal, null, null, null, null, null, null, "Rule broken", duration);
    }

    private static List<String> ids(List<PunishmentRecord> records) {
        List<String> ids = new ArrayList<>();
        records.forEach(record -> ids.add(record.punishment().getId()));
        return ids;
    }
}
