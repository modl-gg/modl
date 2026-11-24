package gg.modl.backend.player.service;

import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static gg.modl.backend.database.jooq.Tables.STAFF;
import static gg.modl.backend.player.PunishmentStore.BAN;
import static gg.modl.backend.player.PunishmentStore.MUTE;
import static gg.modl.backend.player.PunishmentStore.NOW;
import static gg.modl.backend.player.PunishmentStore.draft;
import static gg.modl.backend.player.service.PunishmentTypesFixture.DELETABLE;
import static gg.modl.backend.player.service.PunishmentTypesFixture.SINGLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.player.data.punishment.PunishmentIssuerKind;
import gg.modl.backend.audit.dto.response.StaffDetailsResponse;
import gg.modl.backend.audit.repository.StaffActivityRepository;
import gg.modl.backend.audit.service.AuditService;
import gg.modl.backend.audit.service.StaffPerformanceService;
import gg.modl.backend.database.jooq.tables.records.StaffRecord;
import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.iplookup.service.AddressForwardingMonitor;
import gg.modl.backend.iplookup.service.IpLookupService;
import gg.modl.backend.log.repository.AuditLogRepository;
import gg.modl.backend.log.repository.JooqAuditLogRepository;
import gg.modl.backend.player.PlayerService;
import gg.modl.backend.player.PunishmentStore;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentState;
import gg.modl.backend.player.data.punishment.PunishmentStatus;
import gg.modl.backend.player.data.punishment.ResolvedCategory;
import gg.modl.backend.player.dto.request.AddModificationRequest;
import gg.modl.backend.player.dto.request.CreatePunishmentRequest;
import gg.modl.backend.player.dto.response.PlayerLoginResult;
import gg.modl.backend.player.dto.response.SimplePunishmentView;
import gg.modl.backend.player.dto.response.SyncPunishmentEntry;
import gg.modl.backend.player.dto.response.SyncResult;
import gg.modl.backend.player.service.MinecraftSyncService.OnlinePlayerInput;
import gg.modl.backend.role.service.MinecraftIssuerAuthorizer;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.repository.JooqStaffRepository;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.staff.service.StaffService;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.backend.ticket.repository.TicketRepository;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.junit.jupiter.api.io.TempDir;

class PunishmentComposerContractTest {
    private static final String STAFF_IP = "203.0.113.7";
    private static final long DAY = Duration.ofDays(1).toMillis();
    private static final String OWNER = "owner@example.com";
    private static final PunishmentIssuer OWNER_ISSUER = PunishmentIssuer.staff(OWNER, null);
    private static final PunishmentIssuer MODERATOR = PunishmentIssuer.staff("Moderator", "staff-1");

    private final Server server = TestServers.server("Demo", OWNER);
    private final PunishmentTypesFixture types = new PunishmentTypesFixture();

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void anOfflinePlayerIsStillDeniedAfterTheNominalExpiryUntilTheBanStarts(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentServices services = services(database);
            String player = services.store().addPlayer("offline");
            String id = services.lifecycle().createPunishment(server, PunishmentStore.uuidOf(player),
                request(BAN, null, null, DAY));

            database.clock().advance(Duration.ofDays(2));
            assertThat(login(database, services, player)).extracting(SimplePunishmentView::id).containsExactly(id);

            assertThat(services.lifecycle().acknowledgePunishment(server, PunishmentStore.uuidOf(player), id, true).success()).isTrue();
            assertThat(services.store().activeUntil(id)).isEqualTo(NOW.plusMillis(3 * DAY));

            database.clock().advance(Duration.ofDays(1).plusMillis(1));
            assertThat(login(database, services, player)).isEmpty();
        }
    }

    @SqlContractTest
    void anUnstartedMuteReachesSyncUntilItStarts(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentServices services = services(database);
            String player = services.store().addPlayer("muted");
            String id = services.lifecycle().createPunishment(server, PunishmentStore.uuidOf(player),
                request(MUTE, null, null, DAY));

            database.clock().advance(Duration.ofDays(2));
            assertThat(idsOf(sync(database, services, List.of(player)).data().pendingPunishments())).containsExactly(id);

            services.lifecycle().acknowledgePunishment(server, PunishmentStore.uuidOf(player), id, true);
            assertThat(login(database, services, player)).extracting(SimplePunishmentView::id).containsExactly(id);

            database.clock().advance(Duration.ofDays(1).plusMillis(1));
            assertThat(login(database, services, player)).isEmpty();
            assertThat(idsOf(sync(database, services, List.of(player)).data().pendingPunishments())).isEmpty();
        }
    }

    @SqlContractTest
    void aSingleRollbackUnblocksThePlayerAndTheLinkedAltButCountsAsARollback(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentServices services = services(database);
            PunishmentStore store = services.store();
            String main = store.addPlayer("main");
            String alt = store.addPlayer("alt");
            store.link(main, alt);
            Punishment ban = draft("PARENTID", BAN, null, NOW, null);
            ban.data().setAltBlocking(true);
            ban.getAttachedTicketIds().add("REPORT-1");
            store.issue(main, ban, ResolvedCategory.BAN);

            assertThat(login(database, services, main)).hasSize(1);
            String linkedId = login(database, services, alt).getFirst().id();

            database.clock().advance(Duration.ofSeconds(5));
            assertThat(audit(database, services).rollbackPunishment(server, "PARENTID", "mistake", OWNER_ISSUER)).isTrue();

            Instant now = database.clock().instant();
            assertThat(login(database, services, main)).isEmpty();
            assertThat(login(database, services, alt)).isEmpty();
            assertThat(store.punishments().countActive(now)).isZero();
            assertThat(store.players().findActiveBanHolders(List.of(main, alt), now)).isEmpty();
            assertThat(store.punishments().findTicketIdsHeldByActiveOrUnstarted(List.of("REPORT-1"), now)).isEmpty();
            assertThat(store.modifiedAt("PARENTID")).isEqualTo(now);

            Punishment rolledBack = store.punishment("PARENTID");
            assertThat(rolledBack.isPardoned()).isFalse();
            assertThat(rolledBack.data().status()).isNull();
            assertThat(rolledBack.getModifications()).singleElement()
                .satisfies(modification -> assertThat(modification.type()).isEqualTo("ROLLBACK"));
            assertThat(store.punishment(linkedId).isPardoned()).isTrue();

            verify(services.realtime()).punishmentModified(eq(server),
                argThat(changed -> changed.punishment().getId().equals("PARENTID")));
            verify(services.realtime()).punishmentsPardoned(eq(server),
                argThat(changed -> changed.size() == 1 && changed.getFirst().punishment().getId().equals(linkedId)));

            assertThat(staffDetails(database, services, "PARENTID").punishments()).singleElement()
                .satisfies(detail -> assertThat(detail.rolledBack()).isTrue());
        }
    }

    @SqlContractTest
    void aBulkRollbackReleasesBansMutesAndQueuedPunishments(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentServices services = services(database);
            PunishmentStore store = services.store();
            String player = store.addPlayer("victim");
            store.issue(player, draft("BANBANBA", BAN, null, NOW, null), ResolvedCategory.BAN);
            store.issue(player, draft("MUTEMUTE", MUTE, null, NOW, null), ResolvedCategory.MUTE);
            store.issue(player, draft("QUEUEDBA", BAN, DAY, null, PunishmentStatus.UNSTARTED), ResolvedCategory.BAN);

            database.clock().advance(Duration.ofSeconds(5));
            assertThat(audit(database, services).rollbackAllPunishmentsByStaff(server, "Mod", "compromised", OWNER_ISSUER)).isEqualTo(3);

            SyncResult synced = sync(database, services, List.of(player));
            assertThat(idsOf(synced.data().recentlyModifiedPunishments()))
                .containsExactlyInAnyOrder("BANBANBA", "MUTEMUTE", "QUEUEDBA");
            assertThat(synced.data().pendingPunishments()).isEmpty();
            assertThat(login(database, services, player)).isEmpty();
            assertThat(store.punishment("QUEUEDBA").isUnstarted()).isFalse();
            verify(services.realtime(), times(3)).punishmentModified(eq(server), any());
        }
    }

    @SqlContractTest
    void aRollbackReleasesTheNextQueuedPunishmentInSync(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentServices services = services(database);
            PunishmentStore store = services.store();
            String player = store.addPlayer("queued");
            store.issue(player, draft("ACTIVEMU", MUTE, null, NOW, null), ResolvedCategory.MUTE);
            store.issue(player, draft("WAITINGM", MUTE, DAY, null, PunishmentStatus.UNSTARTED), ResolvedCategory.MUTE);

            database.clock().advance(Duration.ofSeconds(5));
            audit(database, services).rollbackPunishment(server, "ACTIVEMU", "mistake", OWNER_ISSUER);

            assertThat(idsOf(sync(database, services, List.of(player)).data().pendingPunishments())).containsExactly("WAITINGM");
            assertThat(store.punishment("WAITINGM").data().status()).isNull();
        }
    }

    @SqlContractTest
    void syncWithMoreThanTwoChunksMarksOnlyTheMissingPlayerOffline(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentServices services = services(database);
            List<String> players = new ArrayList<>();
            for (int index = 0; index < 1_200; index++) {
                players.add("p" + index);
            }
            services.store().addPlayers(players, true);

            sync(database, services, players);
            assertThat(database.dsl().fetchCount(PLAYERS, PLAYERS.IS_ONLINE.eq(false))).isZero();

            sync(database, services, players.subList(0, players.size() - 1));
            assertThat(database.dsl().select(PLAYERS.ID).from(PLAYERS).where(PLAYERS.IS_ONLINE.eq(false)).fetch(PLAYERS.ID))
                .containsExactly("p1199");
        }
    }

    @SqlContractTest
    void syncRunsTheSameStatementsForTenAndFourHundredPlayers(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            AtomicInteger statements = new AtomicInteger();
            DSLContext counted = database.dsl().configuration().deriveAppending(new StatementCounter(statements)).dsl();
            PunishmentServices services = PunishmentServices.on(database, PunishmentStore.on(database, counted),
                types.types(), types.thresholds());
            List<String> players = new ArrayList<>();
            addBannedOnlinePlayers(services.store(), players, 10);
            statements.set(0);
            sync(database, services, players);
            int forTen = statements.get();

            addBannedOnlinePlayers(services.store(), players, 400);
            statements.set(0);
            sync(database, services, players);

            assertThat(statements.get()).isEqualTo(forTen);
        }
    }

    @SqlContractTest
    void aTwoFactorDeliveryQueuedAfterTheReadWaitsForTheNextSync(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentServices services = services(database);
            UUID first = addStaff(database, "staff-a", true);
            UUID late = addStaff(database, "staff-b", false);
            StaffRepository staff = spy(new JooqStaffRepository(database.dsl(), database.unitOfWork(),
                database.insertIfAbsent(), database.jsonColumns(), database.dialect()));
            doAnswer(call -> {
                Object pending = call.callRealMethod();
                database.unitOfWork().write(() -> database.dsl().update(STAFF)
                    .set(STAFF.TWO_FACTOR_PENDING_DELIVERY, true)
                    .where(STAFF.ID.eq("staff-b"))
                    .execute());
                return pending;
            }).doCallRealMethod().when(staff).findWithPendingTwoFactorDelivery();
            MinecraftSyncService sync = syncService(database, services, staff);

            assertThat(runSync(sync, database, List.of()).data().staff2faVerifications())
                .containsExactly(Map.of("minecraftUuid", first.toString(), "ip", STAFF_IP));
            assertThat(runSync(sync, database, List.of()).data().staff2faVerifications())
                .containsExactly(Map.of("minecraftUuid", late.toString(), "ip", STAFF_IP));
        }
    }

    @SqlContractTest
    void concurrentLoginsCreateOneLinkedBan(DialectTarget target) throws Exception {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentServices services = services(database);
            PunishmentStore store = services.store();
            String main = store.addPlayer("main");
            String alt = store.addPlayer("alt");
            store.link(main, alt);
            Punishment parent = draft("PARENTID", BAN, 3 * DAY, NOW, null);
            parent.data().setAltBlocking(true);
            store.issue(main, parent, ResolvedCategory.BAN);
            Player snapshot = Player.builder().id(alt).minecraftUuid(PunishmentStore.uuidOf(alt)).build();

            int logins = 8;
            CountDownLatch start = new CountDownLatch(1);
            List<Future<List<String>>> results = new ArrayList<>();
            List<String> created = new ArrayList<>();
            try (ExecutorService executor = Executors.newFixedThreadPool(logins)) {
                for (int index = 0; index < logins; index++) {
                    results.add(executor.submit(() -> {
                        start.await();
                        return services.lifecycle().enforceAltBlockingBans(server, snapshot);
                    }));
                }
                start.countDown();
                for (Future<List<String>> result : results) {
                    created.addAll(result.get());
                }
            }

            assertThat(created).hasSize(1);
            assertThat(store.punishments().findLinkedBans("PARENTID")).singleElement().satisfies(linked -> {
                assertThat(linked.punishment().getId()).isEqualTo(created.getFirst());
                assertThat(linked.punishment().enforcementCategory()).isEqualTo(ResolvedCategory.BAN);
                assertThat(linked.punishment().data().duration()).isEqualTo(3 * DAY);
                assertThat(linked.punishment().data().linkedBanParentUuid()).isEqualTo(PunishmentStore.uuidOf(main).toString());
            });
        }
    }

    @SqlContractTest
    void linkedBanIdTakesTheBareIdOfAnExistingPunishmentAndCascades(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentServices services = services(database);
            PunishmentStore store = services.store();
            String main = store.addPlayer("main");
            String alt = store.addPlayer("alt");
            Punishment parent = draft("PARENTID", BAN, null, NOW, null);
            parent.data().setAltBlocking(true);
            store.issue(main, parent, ResolvedCategory.BAN);
            UUID altUuid = PunishmentStore.uuidOf(alt);

            for (Object invalid : List.<Object>of("PARENTID - Player_main", "MISSING1", "x".repeat(500), 42)) {
                assertThatThrownBy(() -> services.lifecycle().createPanelPunishment(server, altUuid, MODERATOR,
                    request(Punishment.LINKED_BAN_TYPE_ORDINAL, null, Map.of("linkedBanId", invalid), null)))
                    .isInstanceOf(ValidationException.class);
            }
            assertThat(store.punishments().findByPlayer(alt)).isEmpty();

            String linked = services.lifecycle().createPanelPunishment(server, altUuid, MODERATOR,
                request(Punishment.LINKED_BAN_TYPE_ORDINAL, null, Map.of("linkedBanId", "PARENTID"), null));
            assertThat(store.punishments().findLinkedBans("PARENTID")).singleElement()
                .satisfies(found -> assertThat(found.punishment().getId()).isEqualTo(linked));

            services.lifecycle().pardonPunishment(server, "PARENTID", "Moderator", null, "appeal", null);
            assertThat(store.punishment(linked).isPardoned()).isTrue();
        }
    }

    @SqlContractTest
    void thePanelOmittingDurationStatusAndLevelGetsTheBackendDecision(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentServices services = services(database);
            String player = services.store().addPlayer("repeat");
            UUID uuid = PunishmentStore.uuidOf(player);

            String first = services.lifecycle().createPanelPunishment(server, uuid, MODERATOR, request(SINGLE, null, null, null));
            String second = services.lifecycle().createPanelPunishment(server, uuid, MODERATOR, request(SINGLE, "single", null, null));

            Punishment firstOffense = services.store().punishment(first);
            assertThat(firstOffense.data().offenseLevel()).isEqualTo("first");
            assertThat(firstOffense.data().status()).isEqualTo("low");
            assertThat(firstOffense.data().duration()).isEqualTo(PunishmentTypesFixture.SINGLE_FIRST);
            assertThat(firstOffense.enforcementCategory()).isEqualTo(ResolvedCategory.BAN);

            Punishment repeated = services.store().punishment(second);
            assertThat(repeated.data().offenseLevel()).isEqualTo("medium");
            assertThat(repeated.data().duration()).isEqualTo(PunishmentTypesFixture.SINGLE_MEDIUM);
            assertThat(repeated.data().status()).isEqualTo(PunishmentStatus.UNSTARTED);
        }
    }

    @SqlContractTest
    void aPanelPardonCascadesToLinkedBans(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentServices services = services(database);
            for (String type : List.of("MANUAL_PARDON", "APPEAL_ACCEPT", "SYSTEM_PARDON")) {
                LinkedPair pair = givenLinkedBan(services, type);

                services.mutations().addModification(server, PunishmentStore.uuidOf(pair.main()), pair.parentId(),
                    new AddModificationRequest(type, "Moderator", "staff-1", null, "Reviewed", null), Shortenings.PERMITTED);

                Punishment parent = services.store().punishment(pair.parentId());
                assertThat(parent.data().status()).isEqualTo(PunishmentStatus.PARDONED);
                assertThat(parent.getModifications()).singleElement().satisfies(modification -> {
                    assertThat(modification.type()).isEqualTo(type);
                    assertThat(modification.reason()).isEqualTo("Reviewed");
                });
                assertThat(services.store().punishment(pair.linkedId()).isPardoned()).isTrue();
                assertThat(login(database, services, pair.main())).isEmpty();
                assertThat(login(database, services, pair.alt())).isEmpty();
            }
            verify(services.realtime(), times(3)).punishmentsPardoned(eq(server), any());
            verify(services.realtime(), times(3)).punishmentPardoned(eq(server), any());
        }
    }

    @SqlContractTest
    void aPanelDurationChangeCascadesToLinkedBans(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentServices services = services(database);
            List<AddModificationRequest> changes = List.of(
                new AddModificationRequest("MANUAL_DURATION_CHANGE", "Moderator", null, 3 * DAY, "Reviewed", null),
                new AddModificationRequest("APPEAL_DURATION_CHANGE", "Moderator", null, 2 * DAY, "Appealed", null),
                new AddModificationRequest("MANUAL_DURATION_CHANGE", "Moderator", null, 0L, "Permanent", null),
                new AddModificationRequest("APPEAL_DURATION_CHANGE", "Moderator", null, null, "Permanent", null));
            for (int index = 0; index < changes.size(); index++) {
                LinkedPair pair = givenLinkedBan(services, "case" + index);
                AddModificationRequest change = changes.get(index);

                services.mutations().addModification(server, PunishmentStore.uuidOf(pair.main()), pair.parentId(), change, Shortenings.PERMITTED);

                long effective = change.effectiveDuration() != null ? change.effectiveDuration() : -1L;
                Instant expected = effective > 0 ? database.clock().instant().plusMillis(effective) : PunishmentState.MAX;
                assertThat(services.store().activeUntil(pair.parentId())).isEqualTo(expected);
                assertThat(services.store().activeUntil(pair.linkedId())).isEqualTo(expected);
                assertThat(services.store().punishment(pair.parentId()).data().duration()).isEqualTo(effective);
                assertThat(services.store().punishment(pair.linkedId()).data().duration()).isEqualTo(effective);
            }
        }
    }

    @SqlContractTest
    void aPanelModificationLeavesLinkedBansOfOtherKindsAlone(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentServices services = services(database);
            LinkedPair pair = givenLinkedBan(services, "rejected");

            services.mutations().addModification(server, PunishmentStore.uuidOf(pair.main()), pair.parentId(),
                new AddModificationRequest("APPEAL_REJECT", "Moderator", null, null, "Upheld", null), Shortenings.PERMITTED);

            assertThat(services.store().punishment(pair.linkedId()).getModifications()).isEmpty();
            assertThat(login(database, services, pair.alt())).hasSize(1);
        }
    }

    @SqlContractTest
    void switchingOffAltBlockingLiftsAutomaticLinkedBansAndSwitchingItOnBansAltsAgain(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentServices services = services(database);
            LinkedPair pair = givenLinkedBan(services, "option");
            PunishmentStore store = services.store();
            UUID main = PunishmentStore.uuidOf(pair.main());

            services.mutations().addModification(server, main, pair.parentId(),
                new AddModificationRequest("SET_ALT_BLOCKING_FALSE", "Moderator", null, null, "Reviewed", null), Shortenings.PERMITTED);

            assertThat(store.punishment(pair.parentId()).data().altBlocking()).isFalse();
            assertThat(store.punishment(pair.parentId()).hasEndedEnforcement()).isFalse();
            assertThat(store.punishment(pair.linkedId()).isPardoned()).isTrue();
            assertThat(login(database, services, pair.alt())).isEmpty();
            assertThat(login(database, services, pair.main())).hasSize(1);

            services.mutations().addModification(server, main, pair.parentId(),
                new AddModificationRequest("SET_ALT_BLOCKING_TRUE", "Moderator", null, null, "Reviewed", null), Shortenings.PERMITTED);

            assertThat(store.punishment(pair.parentId()).data().altBlocking()).isTrue();
            assertThat(login(database, services, pair.alt())).hasSize(1);
        }
    }

    @SqlContractTest
    void liftingLinkedBansByTheOptionIsAShorteningAndNeedsThePardon(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentServices services = services(database);
            LinkedPair pair = givenLinkedBan(services, "guarded");

            assertThatThrownBy(() -> services.mutations().toggleOption(server, pair.parentId(), "ALT_BLOCKING", false,
                "Moderator", null, Shortenings.REFUSED))
                .isInstanceOfSatisfying(ForbiddenException.class,
                    refused -> assertThat(refused.getMachineCode()).isEqualTo(PunishmentShortening.NEEDS_PARDON));

            assertThat(services.store().punishment(pair.parentId()).data().altBlocking()).isTrue();
            assertThat(services.store().punishment(pair.linkedId()).isPardoned()).isFalse();
            assertThat(login(database, services, pair.alt())).hasSize(1);
        }
    }

    @SqlContractTest
    void thePluginToggleLiftsAutomaticLinkedBansToo(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentServices services = services(database);
            LinkedPair pair = givenLinkedBan(services, "toggle");

            services.mutations().toggleOption(server, pair.parentId(), "ALT_BLOCKING", false, "Moderator", null, Shortenings.PERMITTED);

            assertThat(services.store().punishment(pair.parentId()).data().altBlocking()).isFalse();
            assertThat(services.store().punishment(pair.linkedId()).isPardoned()).isTrue();
        }
    }

    @SqlContractTest
    void switchingOffAltBlockingKeepsALinkedBanThatStaffIssued(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentServices services = services(database);
            PunishmentStore store = services.store();
            String main = store.addPlayer("main");
            String alt = store.addPlayer("alt");
            Punishment parent = draft("PARENTID", BAN, null, NOW, null);
            parent.data().setAltBlocking(true);
            store.issue(main, parent, ResolvedCategory.BAN);
            Punishment manual = draft("MANUAL01", Punishment.LINKED_BAN_TYPE_ORDINAL, null, NOW, null);
            manual.data().setLinkedBanId("PARENTID");
            store.issue(alt, manual, ResolvedCategory.BAN);

            services.mutations().addModification(server, PunishmentStore.uuidOf(main), "PARENTID",
                new AddModificationRequest("SET_ALT_BLOCKING_FALSE", "Moderator", null, null, "Reviewed", null), Shortenings.PERMITTED);

            assertThat(store.punishment("MANUAL01").isPardoned()).isFalse();
        }
    }

    @SqlContractTest
    void switchingStatWipingChangesWhatTheNextLoginWipes(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentServices services = services(database);
            PunishmentStore store = services.store();
            String player = store.addPlayer("wiped");
            store.issue(player, draft("EXPIRED1", BAN, DAY, NOW.minusMillis(2 * DAY), null), ResolvedCategory.BAN);
            UUID uuid = PunishmentStore.uuidOf(player);
            assertThat(loginResult(database, services, player).pendingStatWipes()).isEmpty();

            services.mutations().addModification(server, uuid, "EXPIRED1",
                new AddModificationRequest("SET_WIPING_TRUE", "Moderator", null, null, "Reviewed", null), Shortenings.PERMITTED);

            assertThat(store.punishment("EXPIRED1").data().wipeAfterExpiry()).isTrue();
            assertThat(loginResult(database, services, player).pendingStatWipes()).singleElement()
                .satisfies(wipe -> assertThat(wipe).containsEntry("punishmentId", "EXPIRED1"));

            services.mutations().addModification(server, uuid, "EXPIRED1",
                new AddModificationRequest("SET_WIPING_FALSE", "Moderator", null, null, "Reviewed", null), Shortenings.PERMITTED);

            assertThat(store.punishment("EXPIRED1").data().wipeAfterExpiry()).isFalse();
            assertThat(loginResult(database, services, player).pendingStatWipes()).isEmpty();
            assertThat(services.mutations().acknowledgeStatWipe(server, "EXPIRED1").success()).isFalse();
        }
    }

    @SqlContractTest
    void deletingATypeChangesNoPunishment(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentServices services = services(database);
            PunishmentStore store = services.store();
            String banned = store.addPlayer("banned");
            String alt = store.addPlayer("alt");
            store.link(banned, alt);
            String id = services.lifecycle().createPunishment(server, PunishmentStore.uuidOf(banned),
                request(DELETABLE, "lenient", Map.of("altBlocking", true), null));
            Punishment before = store.punishment(id);
            Instant activeUntil = store.activeUntil(id);

            types.delete(DELETABLE);

            assertThat(store.punishment(id)).isEqualTo(before);
            assertThat(store.activeUntil(id)).isEqualTo(activeUntil);
            assertThat(login(database, services, banned)).singleElement().satisfies(view -> {
                assertThat(view.category()).isEqualTo("BAN");
                assertThat(view.type()).isEqualTo("Griefing (deleted)");
            });
            assertThat(login(database, services, alt)).singleElement()
                .satisfies(view -> assertThat(view.category()).isEqualTo("BAN"));
            assertThat(store.punishments().countActiveByCategory(database.clock().instant()))
                .containsEntry(ResolvedCategory.BAN, 2L);
        }
    }

    @SqlContractTest
    void anUnknownTypeIsRejected(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentServices services = services(database);
            String player = services.store().addPlayer("p1");

            assertThatThrownBy(() -> services.lifecycle().createPunishment(server, PunishmentStore.uuidOf(player),
                request(999, null, null, null))).isInstanceOf(ValidationException.class);
            assertThat(services.store().punishments().findByPlayer(player)).isEmpty();
        }
    }

    @SqlContractTest
    void creationAndDurationChangeTreatZeroAndNegativeAsPermanent(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PunishmentServices services = services(database);
            PunishmentStore store = services.store();
            int index = 0;
            for (long duration : List.of(0L, -1L, -DAY)) {
                String player = store.addPlayer("player" + index++);
                UUID uuid = PunishmentStore.uuidOf(player);
                String created = services.lifecycle().createPanelPunishment(server, uuid, MODERATOR,
                    request(BAN, null, null, duration));
                assertThat(store.activeUntil(created)).isEqualTo(PunishmentState.MAX);

                String timed = services.lifecycle().createPunishment(server, uuid, request(MUTE, null, null, DAY));
                Instant startedBefore = store.punishment(timed).getStarted();
                assertThat(services.mutations().changeDuration(server, timed, duration, "Moderator", null, Shortenings.PERMITTED).success()).isTrue();
                assertThat(store.activeUntil(timed)).isEqualTo(PunishmentState.MAX);
                assertThat(store.punishment(timed).getStarted()).isEqualTo(startedBefore);
            }
        }
    }

    @SqlContractTest
    void aRetriedCreationWritesAndPublishesOnce(DialectTarget target) {
        RetryInjection retries = new RetryInjection();
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, retries)) {
            PunishmentServices services = services(database);
            String player = services.store().addPlayer("retried");

            String id = services.lifecycle().createPunishment(server, PunishmentStore.uuidOf(player),
                request(BAN, null, null, DAY));

            assertThat(retries.injected()).isPositive();
            assertThat(services.store().punishments().findByPlayer(player)).singleElement().satisfies(created -> {
                assertThat(created.getId()).isEqualTo(id);
                assertThat(created.data().reason()).isEqualTo("Rule broken");
                assertThat(created.getNotes()).isEmpty();
            });
            verify(services.realtime()).punishmentIssued(eq(server), argThat(issued -> issued.punishment().getId().equals(id)));
        }
    }

    private PunishmentServices services(SqlTestDatabase database) {
        return PunishmentServices.on(database, PunishmentStore.on(database), types.types(), types.thresholds());
    }

    private AuditService audit(SqlTestDatabase database, PunishmentServices services) {
        StaffService staff = mock(StaffService.class);
        when(staff.getStaffByUsername(any(), any())).thenReturn(Optional.empty());
        return new AuditService(database.unitOfWork(), services.store().punishments(),
            new JooqAuditLogRepository(database.dsl(), database.unitOfWork(), database.sequencer(), database.jsonColumns()),
            types.types(), staff, services.lifecycle(), services.mutations(), services.issuerNameResolver(), database.clock());
    }

    private StaffDetailsResponse staffDetails(SqlTestDatabase database, PunishmentServices services, String punishmentId) {
        StaffActivityRepository activity = mock(StaffActivityRepository.class);
        when(activity.findPunishmentDetails(any(), any(), any()))
            .thenReturn(List.of(services.store().punishments().findById(punishmentId).orElseThrow()));
        StaffService staff = mock(StaffService.class);
        when(staff.getStaffByUsername(any(), any())).thenReturn(Optional.empty());
        return new StaffPerformanceService(database.unitOfWork(), activity, services.store().punishments(), mock(AuditLogRepository.class),
            mock(StaffRepository.class), types.types(), staff, mock(PermissionService.class), database.clock())
            .getStaffDetails(server, "Mod", "all");
    }

    private List<SimplePunishmentView> login(SqlTestDatabase database, PunishmentServices services, String player) {
        return loginResult(database, services, player).activePunishments();
    }

    private PlayerLoginResult loginResult(SqlTestDatabase database, PunishmentServices services, String player) {
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
        return players.login(server, PunishmentStore.uuidOf(player), "Player_" + player, null, null, null);
    }

    private SyncResult sync(SqlTestDatabase database, PunishmentServices services, List<String> online) {
        return runSync(syncService(database, services, mock(StaffRepository.class)), database, online);
    }

    private SyncResult runSync(MinecraftSyncService sync, SqlTestDatabase database, List<String> online) {
        List<OnlinePlayerInput> inputs = online.stream()
            .map(player -> new OnlinePlayerInput(PunishmentStore.uuidOf(player).toString(), "Player", null))
            .toList();
        return sync.sync(server, database.clock().instant().minusSeconds(30).toString(), inputs, null, List.of(), List.of());
    }

    private MinecraftSyncService syncService(SqlTestDatabase database, PunishmentServices services, StaffRepository staff) {
        return new MinecraftSyncService(
            database.unitOfWork(),
            database.clock(),
            services.store().players(),
            services.store().punishments(),
            staff,
            services.statusCalculator(),
            types.types(),
            services.lifecycle(),
            mock(MinecraftChatLogService.class),
            services.issuerNameResolver(),
            mock(SyncStaffEventService.class),
            mock(SyncActiveStaffService.class),
            mock(PublicUrls.class));
    }

    private LinkedPair givenLinkedBan(PunishmentServices services, String name) {
        PunishmentStore store = services.store();
        String main = store.addPlayer("main-" + name);
        String alt = store.addPlayer("alt-" + name);
        store.link(main, alt);
        String parentId = "PARENT-" + name;
        Punishment parent = draft(parentId, BAN, null, NOW, null);
        parent.data().setAltBlocking(true);
        store.issue(main, parent, ResolvedCategory.BAN);
        List<String> linked = services.lifecycle().enforceAltBlockingBans(server,
            Player.builder().id(alt).minecraftUuid(PunishmentStore.uuidOf(alt)).build());
        return new LinkedPair(main, alt, parentId, linked.getFirst());
    }

    private static void addBannedOnlinePlayers(PunishmentStore store, List<String> players, int total) {
        List<String> added = new ArrayList<>();
        for (int index = players.size(); index < total; index++) {
            added.add("p" + index);
        }
        store.addPlayers(added, true);
        added.forEach(player -> store.issue(player, draft("B-" + player, BAN, null, NOW, null), ResolvedCategory.BAN));
        players.addAll(added);
    }

    private static UUID addStaff(SqlTestDatabase database, String id, boolean pending) {
        UUID uuid = PunishmentStore.uuidOf(id);
        StaffRecord staff = new StaffRecord();
        staff.setId(id);
        staff.setEmail(id + "@example.com");
        staff.setUsername(id);
        staff.setAssignedMinecraftUuid(uuid.toString());
        staff.setTwoFactorPendingDelivery(pending);
        staff.setTwoFactorSessionIp(STAFF_IP);
        staff.setCreatedAt(NOW);
        staff.setUpdatedAt(NOW);
        database.unitOfWork().write(() -> database.dsl().insertInto(STAFF).set(staff).execute());
        return uuid;
    }

    private static CreatePunishmentRequest request(int ordinal, String severity, Map<String, Object> data, Long duration) {
        return new CreatePunishmentRequest("Mod", null, PunishmentIssuerKind.STAFF, ordinal, null, null, null, severity, null, data, "Rule broken", duration);
    }

    private static List<String> idsOf(List<SyncPunishmentEntry> entries) {
        return entries.stream().map(entry -> entry.punishment().id()).toList();
    }

    private record LinkedPair(String main, String alt, String parentId, String linkedId) {
    }

    private static final class StatementCounter implements ExecuteListener {
        private final AtomicInteger statements;

        private StatementCounter(AtomicInteger statements) {
            this.statements = statements;
        }

        @Override
        public void executeStart(ExecuteContext context) {
            statements.incrementAndGet();
        }
    }
}
