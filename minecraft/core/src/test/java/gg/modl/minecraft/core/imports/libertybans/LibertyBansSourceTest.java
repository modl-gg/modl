package gg.modl.minecraft.core.imports.libertybans;

import gg.modl.minecraft.core.imports.source.PunishmentKind;
import gg.modl.minecraft.core.imports.source.PunishmentScope;
import gg.modl.minecraft.core.imports.source.SourceInventory;
import gg.modl.minecraft.core.imports.source.SourcePlayer;
import gg.modl.minecraft.core.imports.source.SourcePunishment;
import gg.modl.minecraft.core.imports.source.SourceReader;
import gg.modl.minecraft.core.imports.sql.JdbcDriver;
import gg.modl.minecraft.core.imports.sql.SourceFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static gg.modl.minecraft.core.imports.sql.SourceFixtures.ALICE;
import static gg.modl.minecraft.core.imports.sql.SourceFixtures.BOB;
import static gg.modl.minecraft.core.imports.sql.SourceFixtures.DAY;
import static gg.modl.minecraft.core.imports.sql.SourceFixtures.HOUR;
import static gg.modl.minecraft.core.imports.sql.SourceFixtures.MOD;
import static gg.modl.minecraft.core.imports.sql.SourceFixtures.NOW;
import static gg.modl.minecraft.core.imports.sql.SourceFixtures.punishment;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibertyBansSourceTest {
    private static final String CONSOLE = "00000000-0000-0000-0000-000000000000";
    private static final int PLAYER = 0, ADDRESS = 1;
    private static final int BAN = 0, MUTE = 1, WARN = 2, KICK = 3;
    private static final long SECOND = 1000;

    @TempDir
    Path root;

    @BeforeEach
    void createDatabase() throws Exception {
        Path folder = root.resolve("plugins").resolve("LibertyBans");
        SourceFixtures.write(folder.resolve("sql.yml"), "rdms-vendor: 'HSQLDB'\n");
        String url = "jdbc:hsqldb:file:" + folder.resolve("internal").resolve("hypersql").resolve("punishments-database");
        try (Connection connection = SourceFixtures.connect(JdbcDriver.HSQLDB, url, "SA")) {
            SourceFixtures.runScript(connection, "/gg/modl/minecraft/core/imports/libertybans/schema.sql");
            name(connection, ALICE, "Alice", NOW - DAY);
            name(connection, BOB, "Bob", NOW - DAY);
            name(connection, MOD, "Mod", NOW - DAY);
            address(connection, ALICE, "198.51.100.7", NOW - DAY);
            address(connection, BOB, "198.51.100.8", NOW - 400 * DAY);
            victim(connection, 1, PLAYER, ALICE, "0.0.0.0");
            victim(connection, 2, PLAYER, BOB, "0.0.0.0");
            victim(connection, 3, ADDRESS, CONSOLE, "198.51.100.7");
            victim(connection, 4, ADDRESS, CONSOLE, "203.0.113.9");
            execute(connection, "INSERT INTO \"libertybans_scopes\" VALUES (1, 1, 'lobby')");
            punish(connection, 1, BAN, 1, CONSOLE, "Cheating", NOW - 3 * DAY, 0, null, "bans");
            punish(connection, 2, MUTE, 2, MOD, "Spam", NOW - 2 * DAY, NOW + DAY, null, "mutes");
            punish(connection, 3, BAN, 2, MOD, "Mistake", NOW - 5 * DAY, 0, null, null);
            punish(connection, 4, WARN, 1, MOD, "Language", NOW - DAY, 0, null, "warns");
            punish(connection, 5, KICK, 1, CONSOLE, "AFK", NOW - DAY, 0, null, null);
            punish(connection, 6, BAN, 3, CONSOLE, "Alts", NOW - DAY, 0, null, "bans");
            punish(connection, 7, BAN, 4, CONSOLE, "Proxy", NOW - DAY, 0, null, "bans");
            punish(connection, 8, BAN, 2, MOD, "Lobby only", NOW - DAY, NOW - HOUR, 1, null);
            execute(connection, "SHUTDOWN");
        }
    }

    private static void name(Connection connection, String uuid, String name, long at) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO \"libertybans_names\" (\"uuid\", \"name\", \"updated\") VALUES (?, ?, ?)")) {
            statement.setObject(1, UUID.fromString(uuid));
            statement.setString(2, name);
            statement.setLong(3, at / SECOND);
            statement.executeUpdate();
        }
    }

    private static void address(Connection connection, String uuid, String address, long at) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO \"libertybans_addresses\" (\"uuid\", \"address\", \"updated\") VALUES (?, ?, ?)")) {
            statement.setObject(1, UUID.fromString(uuid));
            statement.setBytes(2, InetAddress.getByName(address).getAddress());
            statement.setLong(3, at / SECOND);
            statement.executeUpdate();
        }
    }

    private static void victim(Connection connection, int id, int type, String uuid, String address) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO \"libertybans_victims\" (\"id\", \"type\", \"uuid\", \"address\") VALUES (?, ?, ?, ?)")) {
            statement.setInt(1, id);
            statement.setInt(2, type);
            statement.setObject(3, UUID.fromString(uuid));
            statement.setBytes(4, InetAddress.getByName(address).getAddress());
            statement.executeUpdate();
        }
    }

    private static void punish(Connection connection, long id, int type, int victim, String operator, String reason,
                               long start, long end, Integer scope, String activeTable) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO \"libertybans_punishments\" "
                + "(\"id\", \"type\", \"operator\", \"reason\", \"scope\", \"start\", \"end\", \"scope_id\") VALUES (?, ?, ?, ?, '', ?, ?, ?)")) {
            statement.setLong(1, id);
            statement.setInt(2, type);
            statement.setObject(3, UUID.fromString(operator));
            statement.setString(4, reason);
            statement.setLong(5, start / SECOND);
            statement.setLong(6, end / SECOND);
            statement.setObject(7, scope);
            statement.executeUpdate();
        }
        execute(connection, "INSERT INTO \"libertybans_history\" VALUES (" + id + ", " + victim + ")");
        if (activeTable != null) {
            execute(connection, "INSERT INTO \"libertybans_" + activeTable + "\" VALUES (" + id + ", " + victim + ")");
        }
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private LibertyBansSource source() {
        return new LibertyBansSource(SourceFixtures.context(root));
    }

    @Test
    void theInventoryCountsEveryKindAndAddressBansWithoutAnOwner() throws Exception {
        try (SourceReader reader = source().open()) {
            SourceInventory inventory = reader.inventory();

            assertTrue(source().isPresent());
            assertEquals(3, inventory.getPlayers());
            assertEquals(5, inventory.getBans());
            assertEquals(1, inventory.getMutes());
            assertEquals(1, inventory.getWarnings());
            assertEquals(1, inventory.getKicks());
            assertEquals(2, inventory.getIpWide());
            assertEquals(1, inventory.getServerScoped());
            assertEquals(1, inventory.getWithoutAccount());
        }
    }

    @Test
    void punishmentsKeepStateDurationsAndOperators() throws Exception {
        try (SourceReader reader = source().open()) {
            Map<String, SourcePlayer> players = SourceFixtures.players(reader);
            SourcePlayer alice = players.get(ALICE);
            SourcePlayer bob = players.get(BOB);

            SourcePunishment ban = punishment(alice, "libertybans-1");
            assertTrue(ban.isInForce());
            assertEquals("Console", ban.getIssuerName());
            assertEquals(0, ban.getDuration());
            SourcePunishment mute = punishment(bob, "libertybans-2");
            assertTrue(mute.isInForce());
            assertEquals("Mod", mute.getIssuerName());
            SourceFixtures.assertMinutes(3 * DAY, mute.getDuration());
            SourcePunishment revoked = punishment(bob, "libertybans-3");
            assertTrue(revoked.isEndedEarly());
            assertFalse(revoked.isInForce());
            assertEquals(PunishmentKind.WARNING, punishment(alice, "libertybans-4").getKind());
            assertEquals(PunishmentKind.KICK, punishment(alice, "libertybans-5").getKind());
            SourcePunishment scoped = punishment(bob, "libertybans-8");
            assertEquals(PunishmentScope.SERVER_SCOPED, scoped.getScope());
            assertFalse(scoped.isInForce());
            assertFalse(scoped.isLifted());
        }
    }

    @Test
    void anAddressBanGoesToTheOnlyAccountOfThatAddressWithoutAltBlocking() throws Exception {
        try (SourceReader reader = source().open()) {
            Map<String, SourcePlayer> players = SourceFixtures.players(reader);

            SourcePunishment alts = punishment(players.get(ALICE), "libertybans-6");
            assertEquals(PunishmentScope.IP_WIDE, alts.getScope());
            assertTrue(alts.isInForce());
            assertTrue(alts.isAttributedByAddress());
            assertFalse(alts.blocksAltsWhenWidened());
            assertTrue(players.values().stream().flatMap(player -> player.getPunishments().stream())
                    .noneMatch(punishment -> punishment.getId().equals("libertybans-7")));
        }
    }

    @Test
    void anAddressBanOfAnAddressTwoAccountsUsedBelongsToNoAccount() throws Exception {
        String url = "jdbc:hsqldb:file:" + root.resolve("plugins").resolve("LibertyBans").resolve("internal")
                .resolve("hypersql").resolve("punishments-database");
        try (Connection connection = SourceFixtures.connect(JdbcDriver.HSQLDB, url, "SA")) {
            address(connection, BOB, "198.51.100.7", NOW - 2 * DAY);
            execute(connection, "SHUTDOWN");
        }
        try (SourceReader reader = source().open()) {
            Map<String, SourcePlayer> players = SourceFixtures.players(reader);

            assertEquals(2, reader.inventory().getWithoutAccount());
            assertTrue(players.values().stream().flatMap(player -> player.getPunishments().stream())
                    .noneMatch(punishment -> punishment.getId().equals("libertybans-6")));
        }
    }

    @Test
    void namesAndAddressesBecomeHistory() throws Exception {
        try (SourceReader reader = source().open()) {
            Map<String, SourcePlayer> players = SourceFixtures.players(reader);

            SourcePlayer alice = players.get(ALICE);
            assertEquals("Alice", alice.getUsernames().get(0).getName());
            assertEquals("198.51.100.7", alice.getAddresses().get(0).getIp());
            assertTrue(players.get(MOD).getPunishments().isEmpty());
            assertEquals(1, reader.historyEntriesBefore(NOW - 365 * DAY));
        }
    }
}
