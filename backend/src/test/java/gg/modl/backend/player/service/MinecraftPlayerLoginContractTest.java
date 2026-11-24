package gg.modl.backend.player.service;

import static gg.modl.backend.database.jooq.Tables.PLAYER_LINKS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENTS;
import static gg.modl.backend.player.repository.PlayerFixtures.NOW;
import static gg.modl.backend.player.repository.PlayerFixtures.uuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.database.WriteAttemptHook;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.iplookup.http.IpLookupTransport;
import gg.modl.backend.iplookup.model.IpFacts;
import gg.modl.backend.iplookup.model.IpLookup;
import gg.modl.backend.iplookup.model.IpLookupProvider;
import gg.modl.backend.iplookup.model.VpnStatus;
import gg.modl.backend.iplookup.provider.IpLookupClients;
import gg.modl.backend.iplookup.repository.JooqIpLookupRepository;
import gg.modl.backend.iplookup.service.AddressForwardingMonitor;
import gg.modl.backend.iplookup.service.IpLookupBackoff;
import gg.modl.backend.iplookup.service.IpLookupService;
import gg.modl.backend.iplookup.settings.PlayerAddressSettings;
import gg.modl.backend.iplookup.settings.PlayerAddressSettingsStore;
import gg.modl.backend.log.service.LogService;
import gg.modl.backend.player.PlayerService;
import gg.modl.backend.player.data.IPEntry;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentModification;
import gg.modl.backend.player.data.punishment.PunishmentModificationType;
import gg.modl.backend.player.data.punishment.ResolvedCategory;
import gg.modl.backend.player.dto.response.PlayerLoginResult;
import gg.modl.backend.player.dto.response.SimplePunishmentView;
import gg.modl.backend.player.repository.PlayerFixtures;
import gg.modl.backend.player.repository.PlayerParts;
import gg.modl.backend.player.repository.PlayerRepositories;
import gg.modl.backend.role.service.MinecraftIssuerAuthorizer;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.OffenderThresholdSettingsService;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.backend.ticket.repository.TicketRepository;
import gg.modl.backend.ticket.service.TicketService;
import gg.modl.backend.webhook.WebhookPublisher;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.io.TempDir;

class MinecraftPlayerLoginContractTest {
    private static final String HOME = "203.0.113.7";
    private static final int STATEMENT_GUARD = 15;
    private static final int BAN = 2;
    private static final int STEADY_STATE_COMMITS = 1;
    private static final int REFUSED_LOGIN_WITHDRAWAL_COMMITS = 1;
    private static final List<PunishmentType> TYPES = List.of(
        PunishmentType.builder().ordinal(0).name("Kick").build(),
        PunishmentType.builder().ordinal(BAN).name("Ban").build(),
        PunishmentType.builder().ordinal(Punishment.LINKED_BAN_TYPE_ORDINAL).name("Linked Ban").build());

    private final Server server = TestServers.server("server", "admin@example.com");
    private final PunishmentTypeService types = mock(PunishmentTypeService.class);
    private final AtomicInteger statements = new AtomicInteger();

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void onlyTheLoginThatCreatesThePlayerAnswers201(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            Login login = login(database);

            PlayerLoginResult first = login.as("Steve", HOME);
            PlayerLoginResult second = login.as("Steve", HOME);

            assertThat(first.status()).isEqualTo(201);
            assertThat(second.status()).isEqualTo(200);
        }
    }

    @SqlContractTest
    void aCachedLookupIsAppliedToTheAddressAtLogin(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            Login login = login(database);
            lookups(database).save(lookup(HOME, VpnStatus.YES, "US"));

            login.as("Steve", HOME);

            IPEntry stored = PlayerRepositories.on(database).players()
                .findByMinecraftUuid(uuid("Steve").toString(), PlayerParts.IPS)
                .orElseThrow().getIpAddresses().getFirst();
            assertThat(stored.getCountry()).isEqualTo("US");
            assertThat(stored.isProxy()).isTrue();
        }
    }

    @SqlContractTest
    void anAltLinkedOnlyByAnUnverifiedAddressIsLinkedButNotBanned(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            Login login = login(database);
            login.as("Main", HOME);
            altBlockingBan(database, "Main");

            PlayerLoginResult result = login.as("Alt", HOME);

            assertThat(result.activePunishments()).isEmpty();
            assertThat(database.dsl().fetchCount(PLAYER_LINKS)).isEqualTo(2);
        }
    }

    @SqlContractTest
    void playersArrivingFromALoopbackAddressAreNeverLinked(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            Login login = login(database);
            login.as("Main", "127.0.0.1");
            altBlockingBan(database, "Main");

            PlayerLoginResult result = login.as("Other", "127.0.0.1");

            assertThat(result.activePunishments()).isEmpty();
            assertThat(database.dsl().fetchCount(PLAYER_LINKS)).isZero();
        }
    }

    @SqlContractTest
    void anAltOfAnAltBlockedPlayerIsBannedAtItsLoginExactlyOnce(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            Login login = login(database);
            lookups(database).save(lookup(HOME, VpnStatus.NO, "NL"));
            login.as("Main", HOME);
            String parentId = altBlockingBan(database, "Main").getId();

            PlayerLoginResult first = login.as("Alt", HOME);
            PlayerLoginResult second = login.as("Alt", HOME);

            assertThat(first.activePunishments()).extracting(SimplePunishmentView::category).containsExactly("BAN");
            assertThat(second.activePunishments()).extracting(SimplePunishmentView::category).containsExactly("BAN");
            assertThat(database.dsl().fetchCount(PUNISHMENTS, PUNISHMENTS.LINKED_BAN_ID.eq(parentId))).isEqualTo(1);
            assertThat(database.dsl().fetchCount(PLAYER_LINKS)).isEqualTo(2);
        }
    }

    @SqlContractTest
    void anInnocentPlayerOnTheSameProxyIsNotLinkedOrBanned(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            Login login = login(database);
            lookups(database).save(lookup("198.51.100.20", VpnStatus.YES, "DE"));
            login.as("Main", HOME);
            login.as("Main", "198.51.100.20");
            altBlockingBan(database, "Main");
            login.as("Innocent", "192.0.2.9");

            PlayerLoginResult result = login.as("Innocent", "198.51.100.20");

            assertThat(result.activePunishments()).isEmpty();
            assertThat(database.dsl().fetchCount(PLAYER_LINKS)).isZero();
        }
    }

    @SqlContractTest
    void aBanOfADeletedTypeIsStillDeliveredAndStillBlocksAlts(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            Login login = login(database);
            lookups(database).save(lookup(HOME, VpnStatus.NO, "NL"));
            login.as("Main", HOME);
            PlayerRepositories repositories = PlayerRepositories.on(database);
            String id = repositories.players().findByMinecraftUuid(uuid("Main").toString()).orElseThrow().getId();
            Punishment ban = PlayerFixtures.punishment(18, ResolvedCategory.BAN, NOW, NOW, null);
            ban.data().setAltBlocking(true);
            PlayerFixtures.insert(repositories.punishments(), id, ban);
            when(types.getPunishmentTypeName(server, 18)).thenReturn("Griefing (deleted)");

            assertThat(login.as("Main", HOME).activePunishments()).singleElement().satisfies(view -> {
                assertThat(view.category()).isEqualTo("BAN");
                assertThat(view.type()).isEqualTo("Griefing (deleted)");
            });
            assertThat(login.as("Alt", HOME).activePunishments()).extracting(SimplePunishmentView::category).containsExactly("BAN");
        }
    }

    @SqlContractTest
    void anUnstartedTimedBanIsDeliveredHoweverLateThePlayerReturns(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            Login login = login(database);
            login.as("Offline", null);
            PlayerRepositories repositories = PlayerRepositories.on(database);
            String id = repositories.players().findByMinecraftUuid(uuid("Offline").toString()).orElseThrow().getId();
            Punishment ban = PlayerFixtures.insert(repositories.punishments(), id,
                PlayerFixtures.punishment(BAN, ResolvedCategory.BAN, NOW, null, Duration.ofDays(1).toMillis()));
            database.clock().advance(Duration.ofDays(2));

            assertThat(login.as("Offline", null).activePunishments())
                .extracting(SimplePunishmentView::id).containsExactly(ban.getId());
        }
    }

    @SqlContractTest
    void aRolledBackKickIsNotDeliveredWhileAnUndeliveredOneStillIs(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            Login login = login(database);
            login.as("Kicked", null);
            PlayerRepositories repositories = PlayerRepositories.on(database);
            String id = repositories.players().findByMinecraftUuid(uuid("Kicked").toString()).orElseThrow().getId();
            Punishment waiting = PlayerFixtures.insert(repositories.punishments(), id,
                PlayerFixtures.punishment(0, ResolvedCategory.NONE, NOW, null, null));
            Punishment rolledBack = PlayerFixtures.punishment(0, ResolvedCategory.NONE, NOW, null, null);
            rolledBack.getModifications().add(new PunishmentModification(
                "modification", PunishmentModificationType.ROLLBACK.name(), NOW, "Admin", null, "", null, null, null));
            PlayerFixtures.insert(repositories.punishments(), id, rolledBack);

            assertThat(login.as("Kicked", null).activePunishments())
                .extracting(SimplePunishmentView::id).containsExactly(waiting.getId());
        }
    }

    @SqlContractTest
    void aSteadyStateLoginStaysWithinTheStatementGuard(DialectTarget target) {
        AtomicInteger commits = new AtomicInteger();
        WriteAttemptHook countingCommits = attempt -> commits.incrementAndGet();
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, countingCommits)) {
            Login login = login(database);
            login.as("Plain", "192.0.2.1");

            statements.set(0);
            commits.set(0);
            login.as("Plain", "192.0.2.1");

            assertThat(statements.get()).isPositive().isLessThanOrEqualTo(STATEMENT_GUARD);
            assertThat(commits).hasValue(STEADY_STATE_COMMITS);
        }
    }

    @SqlContractTest
    void aRefusedLoginWithExistingLinksWritesNoLinkRowAndOnlyWithdrawsItsPresence(DialectTarget target) {
        AtomicInteger commits = new AtomicInteger();
        WriteAttemptHook countingCommits = attempt -> commits.incrementAndGet();
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, countingCommits)) {
            Login login = login(database);
            lookups(database).save(lookup(HOME, VpnStatus.NO, "NL"));
            login.as("Main", HOME);
            login.as("Alt", HOME);
            altBlockingBan(database, "Main");
            login.as("Alt", HOME);
            List<?> links = database.dsl().selectFrom(PLAYER_LINKS)
                .orderBy(PLAYER_LINKS.PLAYER_ID).fetch().map(row -> row.intoList());

            database.clock().advance(Duration.ofMinutes(5));
            statements.set(0);
            commits.set(0);
            login.as("Alt", HOME);

            assertThat(statements.get()).isLessThanOrEqualTo(STATEMENT_GUARD);
            assertThat(commits).hasValue(STEADY_STATE_COMMITS + REFUSED_LOGIN_WITHDRAWAL_COMMITS);
            assertThat(database.dsl().selectFrom(PLAYER_LINKS).orderBy(PLAYER_LINKS.PLAYER_ID).fetch().map(row -> row.intoList()))
                .isEqualTo(links);
        }
    }

    @SqlContractTest
    void aNameRestrictionDeniesTheSameNameAndEndsWhenTheNameChanges(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            Login login = login(database);
            login.as("BadName", null);
            Punishment restriction = restriction(database, "BadName", NOW);

            assertThat(login.as("BadName", null).activePunishments())
                .extracting(SimplePunishmentView::id).containsExactly(restriction.getId());

            assertThat(login.renamed("BadName", "GoodName", null).activePunishments()).isEmpty();
            assertThat(PlayerRepositories.on(database).punishments().findById(restriction.getId()).orElseThrow()
                .punishment().hasEndedEnforcement()).isTrue();
        }
    }

    @SqlContractTest
    void aSkinRestrictionEndsOnlyWhenAnotherSkinIsKnown(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            Login login = login(database);
            login.renamed("Skinned", "Skinned", "bad-skin");
            PlayerRepositories repositories = PlayerRepositories.on(database);
            String id = repositories.players().findByMinecraftUuid(uuid("Skinned").toString()).orElseThrow().getId();
            Punishment restriction = PlayerFixtures.punishment(11, ResolvedCategory.BAN, NOW, NOW, null);
            restriction.data().setBlockedSkin("bad-skin");
            PlayerFixtures.insert(repositories.punishments(), id, restriction);

            assertThat(login.renamed("Skinned", "Skinned", null).activePunishments()).hasSize(1);
            assertThat(login.renamed("Skinned", "Skinned", "bad-skin").activePunishments()).hasSize(1);
            assertThat(login.renamed("Skinned", "Skinned", "good-skin").activePunishments()).isEmpty();
        }
    }

    @SqlContractTest
    void aBanQueuedBehindARestrictionStartsAtTheLoginThatLiftsIt(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            Login login = login(database);
            login.as("BadName", null);
            restriction(database, "BadName", NOW);
            PlayerRepositories repositories = PlayerRepositories.on(database);
            String id = repositories.players().findByMinecraftUuid(uuid("BadName").toString()).orElseThrow().getId();
            Punishment queued = PlayerFixtures.punishment(BAN, ResolvedCategory.BAN, NOW, null, Duration.ofDays(1).toMillis());
            queued.data().setStatus("Unstarted");
            PlayerFixtures.insert(repositories.punishments(), id, queued);

            assertThat(login.renamed("BadName", "GoodName", null).activePunishments())
                .extracting(SimplePunishmentView::id).containsExactly(queued.getId());
        }
    }

    @SqlContractTest
    void anAltBlockedPlayerWhoLiftsARestrictionIsStillCaughtByAltBlocking(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            Login login = login(database);
            lookups(database).save(lookup(HOME, VpnStatus.NO, "NL"));
            login.as("Main", HOME);
            login.as("BadName", HOME);
            altBlockingBan(database, "Main");
            restriction(database, "BadName", NOW);

            assertThat(login.renamed("BadName", "GoodName", null).activePunishments())
                .extracting(SimplePunishmentView::typeOrdinal).containsExactly(Punishment.LINKED_BAN_TYPE_ORDINAL);
        }
    }

    private Punishment restriction(SqlTestDatabase database, String name, Instant issued) {
        PlayerRepositories repositories = PlayerRepositories.on(database);
        String id = repositories.players().findByMinecraftUuid(uuid(name).toString()).orElseThrow().getId();
        Punishment restriction = PlayerFixtures.punishment(10, ResolvedCategory.BAN, issued, issued, null);
        restriction.data().setBlockedName(name);
        return PlayerFixtures.insert(repositories.punishments(), id, restriction);
    }

    private Punishment altBlockingBan(SqlTestDatabase database, String name) {
        PlayerRepositories repositories = PlayerRepositories.on(database);
        String id = repositories.players().findByMinecraftUuid(uuid(name).toString()).orElseThrow().getId();
        Punishment ban = PlayerFixtures.punishment(BAN, ResolvedCategory.BAN, NOW, NOW, null);
        ban.data().setAltBlocking(true);
        return PlayerFixtures.insert(repositories.punishments(), id, ban);
    }

    private Login login(SqlTestDatabase database) {
        when(types.getPunishmentTypes(server)).thenReturn(TYPES);
        when(types.getPunishmentTypeByOrdinal(any(), anyInt())).thenAnswer(invocation -> {
            int ordinal = invocation.getArgument(1);
            return TYPES.stream().filter(type -> type.getOrdinal() == ordinal).findFirst();
        });
        when(types.getPunishmentTypeName(any(), anyInt())).thenReturn("Ban");

        DSLContext counted = DSL.using(database.dsl().configuration().deriveAppending(new ExecuteListener() {
            @Override
            public void executeStart(ExecuteContext context) {
                statements.incrementAndGet();
            }
        }));
        PlayerRepositories repositories = PlayerRepositories.on(database, counted);
        PlayerAddressSettingsStore addressSettings = mock(PlayerAddressSettingsStore.class);
        when(addressSettings.current()).thenReturn(PlayerAddressSettings.EMPTY);
        IssuerNameResolver issuers = mock(IssuerNameResolver.class);
        PlayerStatusCalculator calculator = new PlayerStatusCalculator(
            types, mock(OffenderThresholdSettingsService.class), database.clock());
        PunishmentLifecycleService lifecycle = new PunishmentLifecycleService(
            database.unitOfWork(),
            database.clock(),
            repositories.players(),
            repositories.punishments(),
            repositories.lock(),
            mock(EvidenceFileAttachments.class),
            mock(TicketService.class),
            calculator,
            types,
            mock(PunishmentDurationCalculator.class),
            issuers,
            mock(PermissionService.class),
            mock(RoleAuthorization.class),
            mock(WebhookPublisher.class),
            mock(PunishmentRealtimePublisher.class),
            mock(LogService.class),
            new PunishmentPromotion(database.unitOfWork(), database.clock(), repositories.punishments(), repositories.lock(),
                calculator, mock(PunishmentRealtimePublisher.class)),
            mock(MinecraftIssuerAuthorizer.class),
            addressSettings);
        MinecraftPlayerService service = new MinecraftPlayerService(
            mock(PlayerService.class),
            repositories.players(),
            repositories.punishments(),
            mock(TicketRepository.class),
            calculator,
            types,
            lifecycle,
            new AccountLinkingService(repositories.players(), addressSettings, mock(AddressForwardingMonitor.class),
                database.clock()),
            issuers,
            new IpLookupService(new JooqIpLookupRepository(counted, database.unitOfWork(), database.upserts()),
                addressSettings, mock(IpLookupTransport.class), new IpLookupBackoff(database.clock()),
                new IpLookupClients(List.of()), List.of(), mock(OperatorNotice.class), database.clock()),
            mock(AddressForwardingMonitor.class),
            mock(MinecraftIssuerAuthorizer.class),
            database.clock());
        return new Login(service, server);
    }

    private static JooqIpLookupRepository lookups(SqlTestDatabase database) {
        return new JooqIpLookupRepository(database.dsl(), database.unitOfWork(), database.upserts());
    }

    private static IpLookup lookup(String ip, VpnStatus vpn, String country) {
        return new IpLookup(ip, IpLookupProvider.VPNAPI, new IpFacts(country, null, null, vpn, false), NOW);
    }

    private record Login(MinecraftPlayerService service, Server server) {
        PlayerLoginResult renamed(String formerName, String name, String skinHash) {
            return service.login(server, uuid(formerName), name, null, skinHash, null);
        }

        PlayerLoginResult as(String name, String ip) {
            return service.login(server, uuid(name), name, ip, null, null);
        }
    }
}
