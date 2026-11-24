package gg.modl.backend.player.repository;

import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_LINKS;
import static gg.modl.backend.player.repository.PlayerFixtures.NOW;
import static gg.modl.backend.player.repository.PlayerFixtures.login;
import static gg.modl.backend.player.repository.PlayerFixtures.proxy;
import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.iplookup.model.IpFacts;
import gg.modl.backend.iplookup.model.IpLookup;
import gg.modl.backend.iplookup.model.IpLookupProvider;
import gg.modl.backend.iplookup.model.VpnStatus;
import gg.modl.backend.iplookup.repository.JooqIpLookupRepository;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;

class PlayerLinkingContractTest {
    private static final int LIMIT = 1000;
    private static final String HOME = "203.0.113.7";
    private static final String OFFICE = "203.0.113.8";
    private static final String VPN = "198.51.100.20";

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void playersSharingAnAddressAreCandidatesHoweverFarApart(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepository players = PlayerRepositories.on(database).players();
            String first = login(players, "first", HOME, NOW.minus(Duration.ofDays(200)));
            String second = login(players, "second", HOME, NOW);

            assertThat(players.findLinkCandidates(second, List.of(HOME), LIMIT))
                .containsExactly(LinkCandidate.unverified(first));
            assertThat(players.findLinkCandidates(first, List.of(HOME), LIMIT))
                .containsExactly(LinkCandidate.unverified(second));
        }
    }

    @SqlContractTest
    void onlyTheGivenAddressesAreConsidered(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepository players = PlayerRepositories.on(database).players();
            String player = login(players, "player", HOME, NOW);
            login(players, "player", OFFICE, NOW);
            String coworker = login(players, "coworker", OFFICE, NOW);
            login(players, "housemate", HOME, NOW);

            assertThat(players.findLinkCandidates(player, List.of(OFFICE), LIMIT))
                .containsExactly(LinkCandidate.unverified(coworker));
            assertThat(players.findLinkCandidates(player, List.of(), LIMIT)).isEmpty();
        }
    }

    @SqlContractTest
    void anAddressAVpnCheckClearedMarksTheCandidateClean(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepository players = PlayerRepositories.on(database).players();
            String player = login(players, "player", HOME, NOW);
            String sibling = login(players, "sibling", HOME, NOW);
            lookups(database).save(lookup(HOME, VpnStatus.NO));

            assertThat(players.findLinkCandidates(player, List.of(HOME), LIMIT))
                .containsExactly(new LinkCandidate(sibling, true));
        }
    }

    @SqlContractTest
    void anUnknownAddressNeverMarksTheCandidateClean(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepository players = PlayerRepositories.on(database).players();
            String player = login(players, "player", HOME, NOW);
            String sibling = login(players, "sibling", HOME, NOW);
            lookups(database).save(lookup(HOME, VpnStatus.UNKNOWN));

            assertThat(players.findLinkCandidates(player, List.of(HOME), LIMIT))
                .containsExactly(LinkCandidate.unverified(sibling));
        }
    }

    @SqlContractTest
    void anUnverifiedLinkIsUpgradedOnceAnAddressIsClearedAndNeverDowngraded(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepository players = PlayerRepositories.on(database).players();
            String player = login(players, "player", HOME, NOW);
            String sibling = login(players, "sibling", HOME, NOW);
            players.link(player, players.findLinkCandidates(player, List.of(HOME), LIMIT), NOW);
            assertThat(cleanLinks(database)).isZero();

            lookups(database).save(lookup(HOME, VpnStatus.NO));
            List<LinkCandidate> upgrade = players.findLinkCandidates(player, List.of(HOME), LIMIT);
            assertThat(upgrade).containsExactly(new LinkCandidate(sibling, true));
            assertThat(players.link(player, upgrade, NOW)).isZero();
            assertThat(cleanLinks(database)).isEqualTo(2);

            lookups(database).save(lookup(HOME, VpnStatus.YES));
            players.linkUnverified(player, List.of(sibling), NOW);
            assertThat(players.findLinkCandidates(player, List.of(HOME), LIMIT)).isEmpty();
            assertThat(cleanLinks(database)).isEqualTo(2);
        }
    }

    @SqlContractTest
    void addressSharesCountAccountsAndCarryTheVpnStatus(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepository players = PlayerRepositories.on(database).players();
            String player = login(players, "player", HOME, NOW);
            login(players, "player", VPN, proxy(), NOW);
            login(players, "player", OFFICE, NOW);
            login(players, "sibling", HOME, NOW);
            login(players, "parent", HOME, NOW);
            lookups(database).save(lookup(OFFICE, VpnStatus.NO));

            assertThat(players.addressShares(player)).containsExactly(
                new AddressShare(VPN, 1, VpnStatus.YES),
                new AddressShare(HOME, 3, VpnStatus.UNKNOWN),
                new AddressShare(OFFICE, 1, VpnStatus.NO));
        }
    }

    @SqlContractTest
    void playerIdsByAddressAreBoundedAndOrdered(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepository players = PlayerRepositories.on(database).players();
            List<String> ids = Stream.of("a", "b", "c").map(name -> login(players, name, HOME, NOW)).sorted().toList();

            assertThat(players.findPlayerIdsByAddress(HOME, 2)).containsExactlyElementsOf(ids.subList(0, 2));
            assertThat(players.findPlayerIdsByAddress("not an address", 2)).isEmpty();
        }
    }

    @SqlContractTest
    void candidatesAreUniqueSortedAndBounded(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepository players = PlayerRepositories.on(database).players();
            String player = login(players, "player", HOME, NOW);
            login(players, "player", OFFICE, NOW);
            List<String> others = Stream.of("a", "b", "c").map(name -> {
                String id = login(players, name, HOME, NOW);
                login(players, name, OFFICE, NOW);
                return id;
            }).sorted().toList();

            assertThat(players.findLinkCandidates(player, List.of(HOME, OFFICE), LIMIT)).extracting(LinkCandidate::playerId)
                .containsExactlyElementsOf(others);
            assertThat(players.findLinkCandidates(player, List.of(HOME, OFFICE), 2)).extracting(LinkCandidate::playerId)
                .containsExactlyElementsOf(others.subList(0, 2));
        }
    }

    @SqlContractTest
    void linkingIsSymmetricAndLeavesNoCandidateInEitherDirection(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepository players = PlayerRepositories.on(database).players();
            String first = login(players, "first", HOME, NOW);
            String second = login(players, "second", HOME, NOW);

            assertThat(players.linkUnverified(first, List.of(second), NOW)).isEqualTo(1);

            assertThat(database.dsl().fetchCount(PLAYER_LINKS)).isEqualTo(2);
            assertThat(players.findLinkCandidates(first, List.of(HOME), LIMIT)).isEmpty();
            assertThat(players.findLinkCandidates(second, List.of(HOME), LIMIT)).isEmpty();
            Player linked = players.findByIds(List.of(second), PlayerParts.LINKS).getFirst();
            assertThat(linked.data().linkedAccountUuids()).containsExactly(PlayerFixtures.uuid("first").toString());
            assertThat(linked.data().lastLinkedUpdate()).isEqualTo(NOW);
        }
    }

    @SqlContractTest
    void relinkingChangesNoRow(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepository players = PlayerRepositories.on(database).players();
            String first = login(players, "first", HOME, NOW);
            String second = login(players, "second", HOME, NOW);
            players.linkUnverified(first, List.of(second), NOW);

            assertThat(players.linkUnverified(second, List.of(first, second), NOW.plusSeconds(60))).isZero();

            assertThat(database.dsl().fetchCount(PLAYER_LINKS, PLAYER_LINKS.LINKED_AT.ne(NOW))).isZero();
            assertThat(database.dsl().fetchCount(PLAYERS, PLAYERS.LAST_LINKED_UPDATE_AT.ne(NOW))).isZero();
        }
    }

    @SqlContractTest
    void oppositeLinksRunningTogetherStoreOnePairWithoutFailing(DialectTarget target) throws Exception {
        RetryInjection retry = new RetryInjection();
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, retry)) {
            PlayerRepository players = PlayerRepositories.on(database).players();
            String first = login(players, "first", HOME, NOW);
            String second = login(players, "second", HOME, NOW);

            List<Callable<Integer>> links = List.of(
                () -> players.linkUnverified(first, List.of(second), NOW),
                () -> players.linkUnverified(second, List.of(first), NOW));
            int newlyLinked = 0;
            try (var executor = Executors.newFixedThreadPool(links.size())) {
                for (Future<Integer> result : executor.invokeAll(links)) {
                    newlyLinked += result.get();
                }
            }

            assertThat(newlyLinked).isEqualTo(1);
            assertThat(database.dsl().fetchCount(PLAYER_LINKS)).isEqualTo(2);
        }
    }

    private static JooqIpLookupRepository lookups(SqlTestDatabase database) {
        return new JooqIpLookupRepository(database.dsl(), database.unitOfWork(), database.upserts());
    }

    private static IpLookup lookup(String ip, VpnStatus vpn) {
        return new IpLookup(ip, IpLookupProvider.PROXYCHECK, new IpFacts("NL", null, null, vpn, false), NOW);
    }

    private static int cleanLinks(SqlTestDatabase database) {
        return database.dsl().fetchCount(PLAYER_LINKS, PLAYER_LINKS.SHARED_CLEAN_ADDRESS.isTrue());
    }
}
