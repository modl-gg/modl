package gg.modl.backend.player.repository;

import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_IPS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_LINKS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_NOTES;
import static gg.modl.backend.database.jooq.Tables.PLAYER_NOTIFICATIONS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_USERNAMES;
import static gg.modl.backend.database.jooq.Tables.STAFF;
import static gg.modl.backend.player.repository.PlayerFixtures.NOW;
import static gg.modl.backend.player.repository.PlayerFixtures.login;
import static gg.modl.backend.player.repository.PlayerFixtures.uuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.WriteAttemptHook;
import gg.modl.backend.database.jooq.tables.records.IpLookupsRecord;
import gg.modl.backend.database.jooq.tables.records.PlayerIpsRecord;
import gg.modl.backend.database.jooq.tables.records.PlayersRecord;
import gg.modl.backend.database.jooq.tables.records.StaffRecord;
import gg.modl.backend.iplookup.model.IpLookupProvider;
import gg.modl.backend.iplookup.model.VpnStatus;
import gg.modl.backend.player.data.IPEntry;
import gg.modl.backend.player.data.NoteEntry;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.PlayerNoteKind;
import gg.modl.backend.player.data.UsernameEntry;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.ResolvedCategory;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.io.TempDir;

class PlayerRepositoryContractTest {
    private static final String IP = "203.0.113.7";
    private static final long DAY_MILLIS = Duration.ofDays(1).toMillis();
    private static final int CONCURRENT_APPENDS = 8;

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void theFirstLoginCreatesThePlayerWithItsNameAndAddress(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepository players = PlayerRepositories.on(database).players();

            LoginRecord record = players.recordLogin(new LoginUpdate(uuid("Steve").toString(), "Steve", IP, null, "hub", "skin", NOW));

            assertThat(record.created()).isTrue();
            PlayersRecord stored = header(database, record.playerId());
            assertThat(stored.getMinecraftUuid()).isEqualTo(uuid("Steve").toString());
            assertThat(stored.getUsernameLower()).isEqualTo("steve");
            assertThat(stored.getIsOnline()).isTrue();
            assertThat(stored.getFirstJoinAt()).isEqualTo(NOW);
            assertThat(stored.getLastLoginAt()).isEqualTo(NOW);
            assertThat(stored.getLastServerName()).isEqualTo("hub");
            assertThat(stored.getLastSkinHash()).isEqualTo("skin");
            assertThat(database.dsl().fetchCount(PLAYER_USERNAMES)).isEqualTo(1);
            assertThat(address(database, record.playerId(), IP).getLoginCount()).isEqualTo(1L);
        }
    }

    @SqlContractTest
    void aRefusedLoginIsWithdrawnWithoutPlaytime(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepository players = PlayerRepositories.on(database).players();
            String id = login(players, "Steve", IP, null, NOW);

            assertThat(players.withdrawRefusedLogin(id, NOW)).isTrue();

            PlayersRecord stored = header(database, id);
            assertThat(stored.getIsOnline()).isFalse();
            assertThat(stored.getLastLogoutAt()).isEqualTo(NOW);
            assertThat(stored.getTotalPlaytimeSeconds()).isZero();
        }
    }

    @SqlContractTest
    void withdrawingAnOlderRefusedLoginLeavesANewerSessionOpen(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepository players = PlayerRepositories.on(database).players();
            String id = login(players, "Steve", IP, null, NOW);
            login(players, "Steve", IP, null, NOW.plusSeconds(5));

            assertThat(players.withdrawRefusedLogin(id, NOW)).isFalse();

            PlayersRecord stored = header(database, id);
            assertThat(stored.getIsOnline()).isTrue();
            assertThat(stored.getLastLoginAt()).isEqualTo(NOW.plusSeconds(5));
        }
    }

    @SqlContractTest
    void aRepeatedLoginAdvancesLastSeenAndCountAndKeepsTheFirstSighting(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepository players = PlayerRepositories.on(database).players();
            String id = login(players, "Steve", IP, new IpGeo("US", "Virginia", "AS1", true, false), NOW);
            Instant later = NOW.plus(Duration.ofHours(5));

            LoginRecord record = players.recordLogin(new LoginUpdate(uuid("Steve").toString(), "Steve", IP, null, null, null, later));

            assertThat(record.created()).isFalse();
            PlayerIpsRecord stored = address(database, id, IP);
            assertThat(stored.getFirstSeenAt()).isEqualTo(NOW);
            assertThat(stored.getLastSeenAt()).isEqualTo(later);
            assertThat(stored.getLoginCount()).isEqualTo(2L);
            assertThat(stored.getIsProxy()).isTrue();
            assertThat(stored.getCountry()).isEqualTo("US");
            assertThat(header(database, id).getFirstJoinAt()).isEqualTo(NOW);
        }
    }

    @SqlContractTest
    void aLoginKeepsTheSkinAndServerThatItDoesNotReport(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepository players = PlayerRepositories.on(database).players();
            String id = players.recordLogin(new LoginUpdate(uuid("Steve").toString(), "Steve", null, null, "hub", "skin", NOW)).playerId();

            login(players, "Steve", null, NOW.plusSeconds(1));

            assertThat(header(database, id).getLastServerName()).isEqualTo("hub");
            assertThat(header(database, id).getLastSkinHash()).isEqualTo("skin");
            assertThat(database.dsl().fetchCount(PLAYER_IPS)).isZero();
        }
    }

    @SqlContractTest
    void aLoginNeverFailsOnAnOverlongDisplayValue(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepository players = PlayerRepositories.on(database).players();
            int nameLimit = TextLimits.limit(PLAYERS.USERNAME);
            String longName = "😀".repeat(nameLimit + 20);
            IpGeo longGeo = new IpGeo("c".repeat(500), "r".repeat(500), "a".repeat(500), false, false);

            String id = players.recordLogin(new LoginUpdate(uuid("long").toString(), longName, IP, longGeo,
                "s".repeat(500), "k".repeat(500), NOW)).playerId();
            players.recordLogin(new LoginUpdate(uuid("long").toString(), longName, IP, null, null, null, NOW.plusSeconds(1)));

            PlayersRecord stored = header(database, id);
            assertThat(stored.getUsername()).isEqualTo("😀".repeat(nameLimit));
            assertThat(database.dsl().fetchCount(PLAYER_USERNAMES, PLAYER_USERNAMES.PLAYER_ID.eq(id))).isEqualTo(1);
            assertThat(stored.getLastServerName()).hasSize(TextLimits.limit(PLAYERS.LAST_SERVER_NAME));
            assertThat(stored.getLastSkinHash()).hasSize(TextLimits.limit(PLAYERS.LAST_SKIN_HASH));
            assertThat(address(database, id, IP).getCountry()).hasSize(TextLimits.limit(PLAYER_IPS.COUNTRY));
            assertThat(database.dsl().fetchCount(PLAYER_USERNAMES)).isEqualTo(1);
        }
    }

    @SqlContractTest
    void aLoginWithAnAddressTooLongToStoreStillSucceedsWithoutRecordingIt(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepository players = PlayerRepositories.on(database).players();

            LoginRecord record = players.recordLogin(
                new LoginUpdate(uuid("Steve").toString(), "Steve", "x".repeat(80), null, null, null, NOW));

            assertThat(record.created()).isTrue();
            assertThat(database.dsl().fetchCount(PLAYER_IPS)).isZero();
        }
    }

    @SqlContractTest
    void theUsernameHistoryEndsWithTheCurrentNameEvenWhenAnOldNameReturns(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepository players = PlayerRepositories.on(database).players();
            String uuid = uuid("renamer").toString();
            players.recordLogin(new LoginUpdate(uuid, "Alpha", null, null, null, null, NOW));
            players.recordLogin(new LoginUpdate(uuid, "Beta", null, null, null, null, NOW.plusSeconds(1)));
            players.recordLogin(new LoginUpdate(uuid, "ALPHA", null, null, null, null, NOW.plusSeconds(2)));

            Player player = players.findByMinecraftUuid(uuid, PlayerParts.USERNAMES).orElseThrow();

            assertThat(player.getUsernames()).extracting(UsernameEntry::username).containsExactly("Beta", "ALPHA");
            assertThat(player.getUsernames().getLast().date()).isEqualTo(NOW.plusSeconds(2));
            assertThat(database.dsl().fetchSingle(PLAYER_USERNAMES, PLAYER_USERNAMES.USERNAME_LOWER.eq("alpha")).getFirstSeenAt())
                .isEqualTo(NOW);
        }
    }

    @SqlContractTest
    void aRetriedLoginCountsOnce(DialectTarget target) {
        RetryInjection retry = new RetryInjection();
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, retry)) {
            String id = login(PlayerRepositories.on(database).players(), "Steve", IP, NOW);

            assertThat(retry.injected()).isPositive();
            assertThat(address(database, id, IP).getLoginCount()).isEqualTo(1L);
            assertThat(database.dsl().fetchCount(PLAYERS)).isEqualTo(1);
        }
    }

    @SqlContractTest
    void aFullAggregateRoundTrips(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepositories repositories = PlayerRepositories.on(database);
            PlayerRepository players = repositories.players();
            Map<String, Object> extra = new LinkedHashMap<>(Map.of(
                "dotted.key", Map.of("$nested", List.of("é", "😀")),
                "flag", true));
            Player player = Player.builder()
                .id("player000000001")
                .minecraftUuid(uuid("full"))
                .usernames(new ArrayList<>(List.of(new UsernameEntry("Old", NOW.minusSeconds(60)), new UsernameEntry("New", NOW))))
                .ipAddresses(new ArrayList<>(List.of(IPEntry.builder().ipAddress(IP).country("US").proxy(true)
                    .vpnStatus(VpnStatus.YES).firstLogin(NOW.minusSeconds(60)).lastLogin(NOW).loginCount(4).build())))
                .notes(new ArrayList<>(List.of(new NoteEntry("note-1", "watch", NOW, "Moderator", null, PlayerNoteKind.NOTE),
                    new NoteEntry("note-2", "spam", NOW, "Moderator", null, PlayerNoteKind.WARNING))))
                .data(extra)
                .build();
            player.data().setFirstJoin(NOW.minusSeconds(60));
            player.data().setTotalPlaytimeSeconds(90);

            assertThat(players.insertIfAbsent(player)).isTrue();
            Punishment punishment = PlayerFixtures.insert(repositories.punishments(), player.getId(),
                PlayerFixtures.punishment(2, ResolvedCategory.BAN, NOW, NOW, null));

            Player found = players.findByMinecraftUuid(uuid("full").toString().toUpperCase(Locale.ROOT), PlayerParts.values()).orElseThrow();

            assertThat(found.getId()).isEqualTo(player.getId());
            assertThat(found.getUsernames()).isEqualTo(player.getUsernames());
            assertThat(found.getIpAddresses()).isEqualTo(player.getIpAddresses());
            assertThat(found.getNotes()).isEqualTo(player.getNotes());
            assertThat(found.getPunishments()).extracting(Punishment::getId).containsExactly(punishment.getId());
            assertThat(found.data().firstJoin()).isEqualTo(NOW.minusSeconds(60));
            assertThat(found.data().totalPlaytimeSeconds().longValue()).isEqualTo(90L);
            assertThat(found.data().extra()).isEqualTo(player.data().extra());
        }
    }

    @SqlContractTest
    void withoutPartsThePlayerStillCarriesItsCurrentName(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepository players = PlayerRepositories.on(database).players();
            login(players, "Steve", IP, NOW);

            Player player = players.findByMinecraftUuid(uuid("Steve").toString()).orElseThrow();

            assertThat(player.getUsernames()).extracting(UsernameEntry::username).containsExactly("Steve");
            assertThat(player.getIpAddresses()).isEmpty();
        }
    }

    @SqlContractTest
    void insertIfAbsentLeavesAnExistingPlayerUntouched(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepository players = PlayerRepositories.on(database).players();
            String id = login(players, "Steve", null, NOW);
            Player other = Player.builder().minecraftUuid(uuid("Steve"))
                .usernames(List.of(new UsernameEntry("Impostor", NOW))).build();

            assertThat(players.insertIfAbsent(other)).isFalse();
            assertThat(header(database, id).getUsername()).isEqualTo("Steve");
            assertThat(database.dsl().fetchCount(PLAYER_USERNAMES)).isEqualTo(1);
        }
    }

    @SqlContractTest
    void batchLookupsReturnEveryRequestedPlayer(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepositories repositories = PlayerRepositories.on(database);
            List<String> ids = new ArrayList<>();
            List<String> uuids = new ArrayList<>();
            IntStream.range(0, 520).forEach(index -> {
                ids.add(login(repositories.players(), "player" + index, null, NOW));
                uuids.add(uuid("player" + index).toString());
            });
            PlayerFixtures.insert(repositories.punishments(), ids.getLast(),
                PlayerFixtures.punishment(1, ResolvedCategory.MUTE, NOW, NOW, null));

            List<Player> byUuid = repositories.players().findByMinecraftUuids(uuids, PlayerParts.PUNISHMENTS);

            assertThat(byUuid).extracting(Player::getId).containsExactlyElementsOf(ids.stream().sorted().toList());
            assertThat(byUuid.stream().mapToInt(player -> player.getPunishments().size()).sum()).isEqualTo(1);
            assertThat(repositories.players().findByIds(ids)).hasSize(520);
        }
    }

    @SqlContractTest
    void findByUsernamePrefersTheCurrentNameThenTheOnlinePlayer(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepository players = PlayerRepositories.on(database).players();
            String renamed = uuid("renamed").toString();
            players.recordLogin(new LoginUpdate(renamed, "Target", null, null, null, null, NOW.plusSeconds(10)));
            players.recordLogin(new LoginUpdate(renamed, "Elsewhere", null, null, null, null, NOW.plusSeconds(20)));
            String offline = uuid("offline").toString();
            players.recordLogin(new LoginUpdate(offline, "target", null, null, null, null, NOW.plusSeconds(5)));
            players.markOffline(offline, NOW.plusSeconds(6));

            assertThat(players.findByUsernameIgnoreCase("TARGET").orElseThrow().getMinecraftUuid().toString()).isEqualTo(offline);

            String online = uuid("online").toString();
            players.recordLogin(new LoginUpdate(online, "Target", null, null, null, null, NOW));

            assertThat(players.findByUsernameIgnoreCase("target").orElseThrow().getMinecraftUuid().toString()).isEqualTo(online);
            assertThat(players.findByUsernameIgnoreCase("targ")).isEmpty();
        }
    }

    @SqlContractTest
    void searchRanksExactBeforePrefixBeforeContainsOfThreeCharactersAndTreatsWildcardsLiterally(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepository players = PlayerRepositories.on(database).players();
            for (String name : List.of("aaRook", "Rook", "Rookie", "za_bz", "zaxbz", "50%off", "50xoff", "wow!")) {
                login(players, name, null, NOW);
            }

            assertThat(names(players.search("ROOK", 100))).containsExactly("Rook", "Rookie", "aaRook");
            assertThat(names(players.search("rook", 1))).containsExactly("Rook");
            assertThat(names(players.search("a_b", 100))).containsExactly("za_bz");
            assertThat(names(players.search("50%", 100))).containsExactly("50%off");
            assertThat(names(players.search("0%o", 100))).containsExactly("50%off");
            assertThat(names(players.search("ow!", 100))).containsExactly("wow!");
            assertThat(names(players.search("ok", 100))).isEmpty();
        }
    }

    @SqlContractTest
    void searchFindsAPastName(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepository players = PlayerRepositories.on(database).players();
            String uuid = uuid("renamed").toString();
            players.recordLogin(new LoginUpdate(uuid, "Before", null, null, null, null, NOW));
            players.recordLogin(new LoginUpdate(uuid, "After", null, null, null, null, NOW.plusSeconds(1)));

            assertThat(names(players.search("befo", 100))).containsExactly("After");
        }
    }

    @SqlContractTest
    void addressSharersExcludeThePlayerItselfAndHonourTheLimit(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepository players = PlayerRepositories.on(database).players();
            String self = login(players, "self", IP, NOW);
            String first = login(players, "first", IP, NOW);
            String second = login(players, "second", IP, NOW.plusSeconds(1));
            login(players, "elsewhere", "198.51.100.1", NOW);

            AddressSharers all = players.findRecentByIps(List.of(IP), self, 20);
            assertThat(all.players()).extracting(Player::getId).containsExactly(second, first);
            assertThat(all.truncated()).isFalse();
            AddressSharers one = players.findRecentByIps(List.of(IP), self, 1);
            assertThat(one.players()).extracting(Player::getId).containsExactly(second);
            assertThat(one.truncated()).isTrue();
            assertThat(players.findRecentByIps(List.of(), self, 20).players()).isEmpty();
        }
    }

    @SqlContractTest
    void aCrowdedAddressListsTheMostRecentlySeenSharersFirst(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepository players = PlayerRepositories.on(database).players();
            String self = login(players, "self", IP, NOW);
            List<String> byRecency = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                byRecency.addFirst(login(players, "sharer" + i, IP, NOW.plusSeconds(i + 1L)));
            }
            players.recordIp(byRecency.getLast(), "198.51.100.9", NOW.plusSeconds(100));

            AddressSharers recent = players.findRecentByIps(List.of(IP), self, 20);

            assertThat(recent.players()).extracting(Player::getId).containsExactlyElementsOf(byRecency.subList(0, 20));
            assertThat(recent.truncated()).isTrue();
        }
    }

    @SqlContractTest
    void updateIpGeoForAddressOverwritesEveryRowOfThatAddress(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepository players = PlayerRepositories.on(database).players();
            String first = login(players, "Steve", IP, NOW);
            String second = login(players, "Alex", IP, NOW);

            assertThat(players.updateIpGeoForAddress(IP, new IpGeo("CA", "Ontario", "AS456", true, true))).isEqualTo(2);
            assertThat(players.updateIpGeoForAddress("198.51.100.9", new IpGeo("CA", null, null, false, false))).isZero();

            for (String id : List.of(first, second)) {
                PlayerIpsRecord stored = address(database, id, IP);
                assertThat(stored.getCountry()).isEqualTo("CA");
                assertThat(stored.getRegion()).isEqualTo("Ontario");
                assertThat(stored.getAsn()).isEqualTo("AS456");
                assertThat(stored.getIsProxy()).isTrue();
                assertThat(stored.getIsHosting()).isTrue();
            }
        }
    }

    @SqlContractTest
    void eachAddressCarriesItsVpnStatusAndAnUnlookedAddressIsUnknown(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepository players = PlayerRepositories.on(database).players();
            login(players, "Steve", "198.51.100.1", NOW);
            login(players, "Steve", "198.51.100.2", new IpGeo("US", null, null, true, false), NOW.plusSeconds(1));
            login(players, "Steve", "198.51.100.3", NOW.plusSeconds(2));
            login(players, "Steve", "198.51.100.4", NOW.plusSeconds(3));
            database.unitOfWork().write(() -> {
                database.dsl().executeInsert(new IpLookupsRecord("198.51.100.3", IpLookupProvider.VPNAPI.id(), null, null, null,
                    VpnStatus.NO.name(), false, NOW));
                database.dsl().executeInsert(new IpLookupsRecord("198.51.100.4", IpLookupProvider.VPNAPI.id(), null, null, null,
                    VpnStatus.YES.name(), false, NOW));
            });

            Player player = players.findByMinecraftUuid(uuid("Steve").toString(), PlayerParts.IPS).orElseThrow();

            assertThat(player.getIpAddresses()).extracting(IPEntry::getIpAddress, IPEntry::getVpnStatus).containsExactly(
                tuple("198.51.100.1", VpnStatus.UNKNOWN),
                tuple("198.51.100.2", VpnStatus.YES),
                tuple("198.51.100.3", VpnStatus.NO),
                tuple("198.51.100.4", VpnStatus.YES));
        }
    }

    @SqlContractTest
    void recordUsernameAndRecordIpChangeNothingElse(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepository players = PlayerRepositories.on(database).players();
            String id = login(players, "Steve", null, NOW);
            players.markOffline(uuid("Steve").toString(), NOW);

            players.recordUsername(id, "Alex", NOW.plusSeconds(1));
            players.recordIp(id, IP, NOW.plusSeconds(2));

            PlayersRecord stored = header(database, id);
            assertThat(stored.getUsernameLower()).isEqualTo("alex");
            assertThat(stored.getIsOnline()).isFalse();
            assertThat(stored.getLastLoginAt()).isEqualTo(NOW);
            assertThat(address(database, id, IP).getFirstSeenAt()).isEqualTo(NOW.plusSeconds(2));
        }
    }

    @SqlContractTest
    void disconnectAddsPlaytimeAndUpdateServerMovesThePlayer(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepository players = PlayerRepositories.on(database).players();
            String id = login(players, "Steve", null, NOW);
            String uuid = uuid("Steve").toString();

            assertThat(players.updateLastServer(uuid, "survival")).isTrue();
            assertThat(players.markOffline(uuid, NOW.plusSeconds(30))).isTrue();
            assertThat(players.markOffline(uuid, NOW.plusSeconds(90))).isFalse();
            login(players, "Steve", null, NOW.plusSeconds(100));
            assertThat(players.markOffline(uuid, NOW.plusSeconds(112))).isTrue();

            PlayersRecord stored = header(database, id);
            assertThat(stored.getIsOnline()).isFalse();
            assertThat(stored.getLastLogoutAt()).isEqualTo(NOW.plusSeconds(112));
            assertThat(stored.getTotalPlaytimeSeconds()).isEqualTo(42L);
            assertThat(stored.getLastServerName()).isEqualTo("survival");
            assertThat(players.markOffline(uuid("nobody").toString(), NOW)).isFalse();
            assertThat(players.updateLastServer(uuid("nobody").toString(), "hub")).isFalse();
        }
    }

    @SqlContractTest
    void onlinePlayersAreFilteredByServerAndMarkedOfflineInChunks(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepository players = PlayerRepositories.on(database).players();
            List<String> hub = new ArrayList<>();
            IntStream.range(0, 510).forEach(index -> {
                String uuid = uuid("hub" + index).toString();
                players.recordLogin(new LoginUpdate(uuid, "hub" + index, null, null, "hub", null, NOW));
                hub.add(uuid);
            });
            String lobby = uuid("lobby").toString();
            players.recordLogin(new LoginUpdate(lobby, "lobby", null, null, "lobby", null, NOW));

            assertThat(players.findOnlineUuids("hub")).containsExactlyInAnyOrderElementsOf(hub);
            assertThat(players.findOnlineUuids(null)).hasSize(511);
            assertThat(players.findOnline(null, 5)).hasSize(5);
            assertThat(allOnlineInPagesOf(players, 200)).hasSize(511).doesNotHaveDuplicates();

            assertThat(players.markOfflineAll(hub, NOW.plusSeconds(7))).isEqualTo(510);
            assertThat(players.markOfflineAll(hub, NOW.plusSeconds(20))).isZero();
            assertThat(players.findOnlineUuids(null)).isEqualTo(Set.of(lobby));
            assertThat(players.findByMinecraftUuids(hub.subList(0, 1)).getFirst().data().totalPlaytimeSeconds())
                .isEqualTo(7L);
        }
    }

    @SqlContractTest
    void findOrCreateCreatesAMinimalPlayerForAnUnknownUuid(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepository players = PlayerRepositories.on(database).players();
            String known = login(players, "Known", null, NOW);
            String knownUuid = uuid("Known").toString().toUpperCase(Locale.ROOT);
            String unknownUuid = uuid("Stranger").toString();

            Map<String, String> ids = players.findOrCreateIdsByMinecraftUuid(
                Map.of(knownUuid, "Known", unknownUuid, "Stranger", "not-a-uuid", "Nobody"), NOW);

            assertThat(ids).containsEntry(knownUuid, known).containsKey(unknownUuid).doesNotContainKey("not-a-uuid");
            PlayersRecord created = header(database, ids.get(unknownUuid));
            assertThat(created.getUsername()).isEqualTo("Stranger");
            assertThat(created.getIsOnline()).isFalse();
            assertThat(created.getFirstJoinAt()).isNull();

            LoginRecord login = players.recordLogin(new LoginUpdate(unknownUuid, "Stranger", null, null, null, null, NOW.plusSeconds(1)));
            assertThat(login.created()).isFalse();
            assertThat(header(database, login.playerId()).getFirstJoinAt()).isEqualTo(NOW.plusSeconds(1));
        }
    }

    @SqlContractTest
    void concurrentNotesOfOnePlayerAllLandWithDistinctSequenceNumbers(DialectTarget target) throws Exception {
        AtomicInteger retries = new AtomicInteger();
        WriteAttemptHook countingRetries = attempt -> {
            if (attempt > 1) {
                retries.incrementAndGet();
            }
        };
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, countingRetries)) {
            PlayerRepository players = PlayerRepositories.on(database).players();
            String id = login(players, "Steve", null, NOW);
            List<Callable<Object>> appends = IntStream.range(0, CONCURRENT_APPENDS)
                .<Callable<Object>>mapToObj(index -> () -> {
                    players.addNote(id, new NoteEntry("note-" + index, "text", NOW, "Moderator", null, PlayerNoteKind.NOTE));
                    return index;
                })
                .toList();

            try (var executor = Executors.newFixedThreadPool(CONCURRENT_APPENDS)) {
                for (var append : executor.invokeAll(appends)) {
                    append.get();
                }
            }

            assertThat(database.dsl().select(PLAYER_NOTES.SEQ).from(PLAYER_NOTES).orderBy(PLAYER_NOTES.SEQ).fetch(PLAYER_NOTES.SEQ))
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
            assertThat(retries).hasValue(0);
        }
    }

    @SqlContractTest
    void notificationsAreOrderedExpireAndAreAcknowledgedById(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepository players = PlayerRepositories.on(database).players();
            String id = login(players, "Steve", null, NOW);
            Instant expires = NOW.plus(Duration.ofDays(90));
            players.addNotification(id, new PlayerNotification("b", Map.of("message", "second"), NOW.plusSeconds(1), expires));
            players.addNotification(id, new PlayerNotification("a", Map.of("message", "first"), NOW, expires));
            players.addNotification(id, new PlayerNotification("gone", Map.of(), NOW, NOW));
            players.addNotification(id, new PlayerNotification("", Map.of("message", "generated"), NOW.plusSeconds(2), expires));

            List<PlayerNotification> pending = players.findNotifications(List.of(id), NOW).get(id);

            assertThat(pending).hasSize(3);
            assertThat(pending.subList(0, 2)).extracting(PlayerNotification::id).containsExactly("a", "b");
            assertThat(pending.getLast().payload()).containsEntry("id", pending.getLast().id());
            assertThat(players.findNotifications(List.of(id), expires)).isEmpty();
            assertThat(players.acknowledgeNotifications(id, List.of("a", "missing"))).isEqualTo(1);
            assertThat(players.findNotifications(List.of(id), NOW).get(id)).extracting(PlayerNotification::id).doesNotContain("a");
        }
    }

    @SqlContractTest
    void banHoldersAndActiveCountsFollowTheActiveBoundary(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepositories repositories = PlayerRepositories.on(database);
            PlayerRepository players = repositories.players();
            PunishmentRepository punishments = repositories.punishments();
            String banned = login(players, "banned", null, NOW);
            String waiting = login(players, "waiting", null, NOW);
            String muted = login(players, "muted", null, NOW);
            PlayerFixtures.insert(punishments, banned, PlayerFixtures.punishment(2, ResolvedCategory.BAN, NOW, NOW, DAY_MILLIS));
            PlayerFixtures.insert(punishments, waiting, PlayerFixtures.punishment(2, ResolvedCategory.BAN, NOW, null, DAY_MILLIS));
            PlayerFixtures.insert(punishments, muted, PlayerFixtures.punishment(1, ResolvedCategory.MUTE, NOW, NOW, null));
            List<String> all = List.of(banned, waiting, muted);
            Instant expiry = NOW.plusMillis(DAY_MILLIS);

            assertThat(players.findActiveBanHolders(all, expiry)).containsExactlyInAnyOrder(banned, waiting);
            assertThat(players.findActiveBanHolders(all, expiry.plusMillis(1))).containsExactly(waiting);
            assertThat(players.countActiveByPlayer(all, NOW)).containsExactlyInAnyOrderEntriesOf(Map.of(
                banned, new ActiveCounts(1, 0),
                waiting, new ActiveCounts(1, 0),
                muted, new ActiveCounts(0, 1)));
        }
    }

    @SqlContractTest
    void theStaffPickerLeavesOutAssignedPlayers(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepository players = PlayerRepositories.on(database).players();
            login(players, "assigned", null, NOW);
            String older = login(players, "older", null, NOW.plusSeconds(1));
            String newer = login(players, "newer", null, NOW.plusSeconds(2));
            StaffRecord staff = new StaffRecord();
            staff.setId("staff1");
            staff.setEmail("staff@example.com");
            staff.setUsername("Staff");
            staff.setAssignedMinecraftUuid(uuid("assigned").toString());
            staff.setCreatedAt(NOW);
            staff.setUpdatedAt(NOW);
            database.unitOfWork().write(() -> database.dsl().insertInto(STAFF).set(staff).execute());

            assertThat(players.findAvailableForStaffAssignment(10)).containsExactly(
                new PlayerSummary(older, uuid("older").toString(), "older"),
                new PlayerSummary(newer, uuid("newer").toString(), "newer"));
        }
    }

    @SqlContractTest
    void pruningDeletesOnlyAddressesUnseenSinceTheCutoff(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepository players = PlayerRepositories.on(database).players();
            Instant cutoff = NOW.minus(Duration.ofDays(365));
            login(players, "stale1", "198.51.100.1", cutoff.minusMillis(1));
            login(players, "stale2", "198.51.100.2", cutoff.minusSeconds(60));
            login(players, "stale3", "198.51.100.3", cutoff.minusSeconds(90));
            String boundary = login(players, "boundary", "198.51.100.4", cutoff);
            String returning = login(players, "returning", "198.51.100.5", cutoff.minusSeconds(60));
            login(players, "returning", "198.51.100.5", NOW);

            IpPruneBatch first = players.pruneIpsUnseenSince(cutoff, NOW, null, 2);
            assertThat(first.deleted()).isEqualTo(2L);
            IpPruneBatch second = players.pruneIpsUnseenSince(cutoff, NOW, first.next().orElseThrow(), 2);
            assertThat(second.deleted()).isEqualTo(1L);
            assertThat(second.next()).isEmpty();
            assertThat(players.pruneIpsUnseenSince(cutoff, NOW, null, 2)).isEqualTo(new IpPruneBatch(0L, null));

            assertThat(database.dsl().select(PLAYER_IPS.PLAYER_ID).from(PLAYER_IPS).fetch(PLAYER_IPS.PLAYER_ID))
                .containsExactlyInAnyOrder(boundary, returning);
        }
    }

    @SqlContractTest
    void pruningResumesAfterTheCursorInsteadOfRescanningFromTheStart(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepository players = PlayerRepositories.on(database).players();
            Instant cutoff = NOW.minus(Duration.ofDays(365));
            login(players, "stale1", "198.51.100.1", cutoff.minusSeconds(1));
            login(players, "stale2", "198.51.100.2", cutoff.minusSeconds(1));
            IpHistoryKey last = database.dsl().select(PLAYER_IPS.PLAYER_ID, PLAYER_IPS.IP).from(PLAYER_IPS)
                .orderBy(PLAYER_IPS.PLAYER_ID.desc(), PLAYER_IPS.IP.desc()).limit(1)
                .fetchSingle(row -> new IpHistoryKey(row.value1(), row.value2()));

            assertThat(players.pruneIpsUnseenSince(cutoff, NOW, last, 10)).isEqualTo(new IpPruneBatch(0L, null));

            assertThat(database.dsl().fetchCount(PLAYER_IPS)).isEqualTo(2);
        }
    }

    @SqlContractTest
    void pruningSkipsRowsRetainedUntilLaterAndRemovesThemOnceThatPasses(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepository players = PlayerRepositories.on(database).players();
            Instant cutoff = NOW.minus(Duration.ofDays(365));
            Instant old = cutoff.minusSeconds(60);
            String retained = login(players, "retained", "198.51.100.1", old);
            String expired = login(players, "expired", "198.51.100.2", old);
            login(players, "standard", "198.51.100.3", old);
            String recent = login(players, "recent", "198.51.100.4", cutoff);
            retain(database, retained, NOW.plusSeconds(1));
            retain(database, expired, NOW.minusMillis(1));

            assertThat(players.pruneIpsUnseenSince(cutoff, NOW, null, 10).deleted()).isEqualTo(2L);

            assertThat(database.dsl().select(PLAYER_IPS.PLAYER_ID).from(PLAYER_IPS).fetch(PLAYER_IPS.PLAYER_ID))
                .containsExactlyInAnyOrder(retained, recent);
            assertThat(players.pruneIpsUnseenSince(cutoff, NOW.plusSeconds(2), null, 10).deleted()).isEqualTo(1L);
        }
    }

    @SqlContractTest
    void aLiveSightingEndsTheRetentionOfAnImportedAddress(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepository players = PlayerRepositories.on(database).players();
            String id = login(players, "Steve", IP, NOW.minus(Duration.ofDays(500)));
            retain(database, id, NOW.plus(Duration.ofDays(3650)));

            login(players, "Steve", IP, NOW);

            assertThat(address(database, id, IP).getRetainedUntilAt()).isNull();
        }
    }

    @SqlContractTest
    void deletingAPlayerCascadesToEveryChild(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            PlayerRepository players = PlayerRepositories.on(database).players();
            String id = login(players, "Steve", IP, NOW);
            String other = login(players, "Alex", IP, NOW);
            players.addNote(id, new NoteEntry("note", "text", NOW, "Moderator", null, PlayerNoteKind.WARNING));
            players.addNotification(id, new PlayerNotification("n", Map.of(), NOW, NOW.plusSeconds(60)));
            players.linkUnverified(id, List.of(other), NOW);

            database.unitOfWork().write(() -> database.dsl().deleteFrom(PLAYERS).where(PLAYERS.ID.eq(id)).execute());

            assertThat(database.dsl().fetchCount(PLAYER_NOTES)).isZero();
            assertThat(database.dsl().fetchCount(PLAYER_NOTIFICATIONS)).isZero();
            assertThat(database.dsl().fetchCount(PLAYER_LINKS)).isZero();
            assertThat(database.dsl().fetchCount(PLAYER_IPS, PLAYER_IPS.PLAYER_ID.eq(id))).isZero();
            assertThat(database.dsl().fetchCount(PLAYER_USERNAMES, PLAYER_USERNAMES.PLAYER_ID.eq(id))).isZero();
        }
    }

    private static List<String> allOnlineInPagesOf(PlayerRepository players, int pageSize) {
        List<String> ids = new ArrayList<>();
        String after = null;
        List<Player> page;
        do {
            page = players.findOnline(after, pageSize);
            page.forEach(player -> ids.add(player.getId()));
            after = page.isEmpty() ? after : page.getLast().getId();
        } while (page.size() == pageSize);
        return ids;
    }

        private static List<String> names(List<Player> players) {
        return players.stream().map(player -> player.getUsernames().getLast().username()).toList();
    }

    private static PlayersRecord header(SqlTestDatabase database, String id) {
        return database.dsl().fetchSingle(PLAYERS, PLAYERS.ID.eq(id));
    }

    private static void retain(SqlTestDatabase database, String playerId, Instant until) {
        database.unitOfWork().write(() -> database.dsl().update(PLAYER_IPS)
            .set(PLAYER_IPS.RETAINED_UNTIL_AT, until)
            .where(PLAYER_IPS.PLAYER_ID.eq(playerId))
            .execute());
    }

    private static PlayerIpsRecord address(SqlTestDatabase database, String id, String ip) {
        return database.dsl().fetchSingle(PLAYER_IPS, PLAYER_IPS.PLAYER_ID.eq(id).and(PLAYER_IPS.IP.eq(ip)));
    }
}
