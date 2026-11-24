package gg.modl.minecraft.core.imports.banmanager;

import gg.modl.minecraft.core.imports.source.PunishmentKind;
import gg.modl.minecraft.core.imports.source.PunishmentScope;
import gg.modl.minecraft.core.imports.source.Removal;
import gg.modl.minecraft.core.imports.source.SourceException;
import gg.modl.minecraft.core.imports.source.SourceInventory;
import gg.modl.minecraft.core.imports.source.SourcePlayer;
import gg.modl.minecraft.core.imports.source.SourcePunishment;
import gg.modl.minecraft.core.imports.source.SourceReader;
import gg.modl.minecraft.core.imports.sql.JdbcDriver;
import gg.modl.minecraft.core.imports.sql.SourceFixtures;
import gg.modl.minecraft.core.imports.support.PlayerKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;

import static gg.modl.minecraft.core.imports.sql.SourceFixtures.ALICE;
import static gg.modl.minecraft.core.imports.sql.SourceFixtures.BOB;
import static gg.modl.minecraft.core.imports.sql.SourceFixtures.DAY;
import static gg.modl.minecraft.core.imports.sql.SourceFixtures.HOUR;
import static gg.modl.minecraft.core.imports.sql.SourceFixtures.MOD;
import static gg.modl.minecraft.core.imports.sql.SourceFixtures.NOW;
import static gg.modl.minecraft.core.imports.sql.SourceFixtures.punishment;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BanManagerSourceTest {
    private static final String CONSOLE = "44444444-4444-4444-8444-444444444444";
    private static final long SECOND = 1000;

    @TempDir
    Path root;

    @BeforeEach
    void createDatabase() throws Exception {
        Path folder = root.resolve("plugins").resolve("BanManager");
        SourceFixtures.write(folder.resolve("config.yml"), """
                databases:
                  local:
                    storageType: 'h2'
                    name: 'local_bans'
                    user: 'root'
                    password: ''
                """);
        SourceFixtures.write(folder.resolve("console.yml"), "uuid: " + CONSOLE + "\nname: Console\n");
        String url = "jdbc:h2:file:" + folder.resolve("local_bans") + ";mode=MySQL;DB_CLOSE_ON_EXIT=TRUE;FILE_LOCK=NO;IGNORECASE=TRUE";
        try (Connection connection = SourceFixtures.connect(JdbcDriver.H2_LEGACY, url, "")) {
            SourceFixtures.runScript(connection, "/gg/modl/minecraft/core/imports/banmanager/schema.sql");
            player(connection, CONSOLE, "Console", "127.0.0.1", NOW);
            player(connection, ALICE, "Alice", "198.51.100.7", NOW - DAY);
            player(connection, BOB, "Bob", "198.51.100.8", NOW - 2 * DAY);
            player(connection, MOD, "Mod", "198.51.100.9", NOW);
            insert(connection, "INSERT INTO bm_player_bans (player_id, reason, actor_id, created, updated, expires, silent) VALUES (?, ?, ?, ?, ?, ?, 0)",
                    uuid(ALICE), "Cheating", uuid(CONSOLE), seconds(NOW - 3 * DAY), seconds(NOW - 3 * DAY), 0L);
            insert(connection, "INSERT INTO bm_player_ban_records (player_id, reason, expired, actor_id, pastActor_id, pastCreated, created, createdReason, silent) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)",
                    uuid(BOB), "Old", seconds(NOW - 9 * DAY), uuid(CONSOLE), uuid(MOD), seconds(NOW - 10 * DAY), seconds(NOW - 9 * DAY), "");
            insert(connection, "INSERT INTO bm_player_ban_records (player_id, reason, expired, actor_id, pastActor_id, pastCreated, created, createdReason, silent) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)",
                    uuid(BOB), "Mistake", 0L, uuid(MOD), uuid(MOD), seconds(NOW - 5 * DAY), seconds(NOW - 4 * DAY), "Appeal accepted");
            insert(connection, "INSERT INTO bm_player_mutes (player_id, reason, actor_id, created, updated, expires, soft, silent, onlineOnly, pausedRemaining) VALUES (?, ?, ?, ?, ?, ?, 0, 0, 0, 0)",
                    uuid(BOB), "Spam", uuid(MOD), seconds(NOW - 2 * DAY), seconds(NOW - 2 * DAY), seconds(NOW + DAY));
            insert(connection, "INSERT INTO bm_player_warnings (player_id, reason, actor_id, created, expires, `read`, points) VALUES (?, ?, ?, ?, 0, 1, 1)",
                    uuid(ALICE), "Language", uuid(MOD), seconds(NOW - DAY));
            insert(connection, "INSERT INTO bm_player_kicks (player_id, reason, actor_id, created) VALUES (?, ?, ?, ?)",
                    uuid(ALICE), "AFK", uuid(CONSOLE), seconds(NOW - DAY));
            insert(connection, "INSERT INTO bm_player_notes (player_id, message, actor_id, created) VALUES (?, ?, ?, ?)",
                    uuid(BOB), "Watch him", uuid(MOD), seconds(NOW - DAY));
            insert(connection, "INSERT INTO bm_player_history (player_id, name, ip, `join`, `leave`) VALUES (?, ?, ?, ?, ?)",
                    uuid(ALICE), "Alice", ip("198.51.100.7"), seconds(NOW - DAY), seconds(NOW - DAY + HOUR));
            insert(connection, "INSERT INTO bm_player_history (player_id, name, ip, `join`, `leave`) VALUES (?, ?, ?, ?, ?)",
                    uuid(BOB), "Bobby", ip("198.51.100.8"), seconds(NOW - 400 * DAY), seconds(NOW - 400 * DAY + HOUR));
            insert(connection, "INSERT INTO bm_ip_bans (ip, reason, actor_id, created, updated, expires, silent) VALUES (?, ?, ?, ?, ?, 0, 0)",
                    ip("198.51.100.7"), "Alts", uuid(CONSOLE), seconds(NOW - DAY), seconds(NOW - DAY));
            insert(connection, "INSERT INTO bm_ip_bans (ip, reason, actor_id, created, updated, expires, silent) VALUES (?, ?, ?, ?, ?, 0, 0)",
                    ip("203.0.113.9"), "Proxy", uuid(CONSOLE), seconds(NOW - DAY), seconds(NOW - DAY));
            insert(connection, "INSERT INTO bm_ip_range_bans (fromIp, toIp, reason, actor_id, created, updated, expires, silent) VALUES (?, ?, ?, ?, ?, ?, 0, 0)",
                    ip("203.0.113.0"), ip("203.0.113.255"), "Range", uuid(CONSOLE), seconds(NOW - DAY), seconds(NOW - DAY));
        }
    }

    private static void player(Connection connection, String uuid, String name, String ip, long seen) throws Exception {
        insert(connection, "INSERT INTO bm_players (id, name, ip, lastSeen) VALUES (?, ?, ?, ?)", uuid(uuid), name, ip(ip),
                seconds(seen));
    }

    private static void insert(Connection connection, String sql, Object... values) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            statement.executeUpdate();
        }
    }

    private static byte[] uuid(String uuid) {
        return PlayerKeys.bytesOf(uuid);
    }

    private static byte[] ip(String address) throws Exception {
        return InetAddress.getByName(address).getAddress();
    }

    private static long seconds(long millis) {
        return millis / SECOND;
    }

    private BanManagerSource source(boolean running) {
        return new BanManagerSource(SourceFixtures.context(root, name -> running && "BanManager".equals(name)));
    }

    @Test
    void theInventoryCountsTablesAndLeavesOutRangesAndTheConsole() throws Exception {
        try (SourceReader reader = source(false).open()) {
            SourceInventory inventory = reader.inventory();

            assertEquals(3, inventory.getPlayers());
            assertEquals(6, inventory.getBans());
            assertEquals(1, inventory.getMutes());
            assertEquals(1, inventory.getWarnings());
            assertEquals(1, inventory.getKicks());
            assertEquals(1, inventory.getNotes());
            assertEquals(2, inventory.getIpWide());
            assertEquals(2, inventory.getWithoutAccount());
        }
    }

    @Test
    void recordsTellExpiryFromAnUnbanWithItsActorAndReason() throws Exception {
        try (SourceReader reader = source(false).open()) {
            Map<String, SourcePlayer> players = SourceFixtures.players(reader);
            SourcePlayer bob = players.get(BOB);

            SourcePunishment expired = punishment(bob, "banmanager-banrecord-1");
            assertNull(expired.getRemoval());
            assertEquals("Mod", expired.getIssuerName());
            SourceFixtures.assertMinutes(DAY, expired.getDuration());
            Removal removal = punishment(bob, "banmanager-banrecord-2").getRemoval();
            assertNotNull(removal);
            assertEquals("Mod", removal.getBy());
            assertEquals("Appeal accepted", removal.getReason());
            assertEquals(NOW - 4 * DAY, removal.getAt());
        }
    }

    @Test
    void activeRowsAreInForceWithTheirKinds() throws Exception {
        try (SourceReader reader = source(false).open()) {
            Map<String, SourcePlayer> players = SourceFixtures.players(reader);
            SourcePlayer alice = players.get(ALICE);

            SourcePunishment ban = punishment(alice, "banmanager-ban-1");
            assertTrue(ban.isInForce());
            assertEquals("Console", ban.getIssuerName());
            SourcePunishment mute = punishment(players.get(BOB), "banmanager-mute-1");
            assertTrue(mute.isInForce());
            SourceFixtures.assertMinutes(3 * DAY, mute.getDuration());
            assertEquals(PunishmentKind.WARNING, punishment(alice, "banmanager-warning-1").getKind());
            assertEquals(PunishmentKind.KICK, punishment(alice, "banmanager-kick-1").getKind());
            assertEquals(PunishmentKind.NOTE, punishment(players.get(BOB), "banmanager-note-1").getKind());
        }
    }

    @Test
    void anIpBanGoesToTheOnlyAccountOfTheAddressWithoutAltBlockingAndHistoryIsKept() throws Exception {
        try (SourceReader reader = source(false).open()) {
            Map<String, SourcePlayer> players = SourceFixtures.players(reader);
            SourcePlayer alice = players.get(ALICE);

            SourcePunishment alts = punishment(alice, "banmanager-ipban-1");
            assertEquals(PunishmentScope.IP_WIDE, alts.getScope());
            assertTrue(alts.isInForce());
            assertTrue(alts.isAttributedByAddress());
            assertFalse(alts.blocksAltsWhenWidened());
            assertEquals("198.51.100.7", alice.getAddresses().get(0).getIp());
            assertTrue(players.get(BOB).getUsernames().stream().anyMatch(name -> name.getName().equals("Bobby")));
            assertFalse(players.containsKey("44444444-4444-4444-8444-444444444444"));
            assertEquals(1, reader.historyEntriesBefore(NOW - 365 * DAY));
        }
    }

    @Test
    void anIpBanOfAnAddressTwoAccountsUsedBelongsToNoAccount() throws Exception {
        Path database = root.resolve("plugins").resolve("BanManager").resolve("local_bans");
        try (Connection connection = SourceFixtures.connect(JdbcDriver.H2_LEGACY, "jdbc:h2:file:" + database
                + ";mode=MySQL;FILE_LOCK=NO;IGNORECASE=TRUE", "")) {
            insert(connection, "INSERT INTO bm_player_history (player_id, name, ip, `join`, `leave`) VALUES (?, ?, ?, ?, ?)",
                    uuid(BOB), "Bob", ip("198.51.100.7"), seconds(NOW - 2 * DAY), seconds(NOW - 2 * DAY + HOUR));
        }
        try (SourceReader reader = source(false).open()) {
            Map<String, SourcePlayer> players = SourceFixtures.players(reader);

            assertEquals(3, reader.inventory().getWithoutAccount());
            assertTrue(players.values().stream().flatMap(player -> player.getPunishments().stream())
                    .noneMatch(punishment -> punishment.getId().equals("banmanager-ipban-1")));
        }
    }

    @Test
    void aRunningBanManagerIsReadThroughItsOwnConnectionOnly() {
        SourceException refused = assertThrows(SourceException.class, () -> source(true).open());

        assertEquals("import.database_failed", refused.getMessageKey());
    }
}
