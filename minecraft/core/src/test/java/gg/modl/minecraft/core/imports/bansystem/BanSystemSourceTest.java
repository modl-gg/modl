package gg.modl.minecraft.core.imports.bansystem;

import gg.modl.minecraft.core.imports.source.PunishmentKind;
import gg.modl.minecraft.core.imports.source.Removal;
import gg.modl.minecraft.core.imports.source.SourceInventory;
import gg.modl.minecraft.core.imports.source.SourcePlayer;
import gg.modl.minecraft.core.imports.source.SourcePunishment;
import gg.modl.minecraft.core.imports.source.SourceReader;
import gg.modl.minecraft.core.imports.sql.JdbcDriver;
import gg.modl.minecraft.core.imports.sql.SourceFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static gg.modl.minecraft.core.imports.sql.SourceFixtures.ALICE;
import static gg.modl.minecraft.core.imports.sql.SourceFixtures.BOB;
import static gg.modl.minecraft.core.imports.sql.SourceFixtures.DAY;
import static gg.modl.minecraft.core.imports.sql.SourceFixtures.HOUR;
import static gg.modl.minecraft.core.imports.sql.SourceFixtures.MOD;
import static gg.modl.minecraft.core.imports.sql.SourceFixtures.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BanSystemSourceTest {
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
            .withZone(ZoneId.systemDefault());
    private static final String BAN = "INSERT INTO `%s` (id, player, duration, creationdate, creator, reason, ip, type) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    @TempDir
    Path root;

    @BeforeEach
    void createDatabase() throws Exception {
        Path folder = root.resolve("plugins").resolve("BanSystem");
        SourceFixtures.write(folder.resolve("config.yml"), "mysql:\n  enable: false\n");
        SourceFixtures.write(root.resolve("usercache.json"),
                "[{\"name\":\"Mod\",\"uuid\":\"" + MOD + "\",\"expiresOn\":\"2027-01-01 00:00:00 +0000\"},"
                        + "{\"name\":\"Alice\",\"uuid\":\"" + ALICE + "\",\"expiresOn\":\"2027-01-01 00:00:00 +0000\"}]");
        try (Connection connection = SourceFixtures.connect(JdbcDriver.SQLITE, "jdbc:sqlite:" + folder.resolve("database.db"), "")) {
            SourceFixtures.runScript(connection, "/gg/modl/minecraft/core/imports/bansystem/schema.sql");
            ban(connection, true, "a1b2c", ALICE, -1, NOW - 3 * DAY, "CONSOLE", "Hacking", "198.51.100.7", "NETWORK");
            ban(connection, true, "b2c3d", BOB, DAY, NOW - 2 * DAY, MOD, "Spam", "", "CHAT");
            ban(connection, false, "c3d4e", BOB, -1, NOW - 5 * DAY, MOD, "Mistake", "", "NETWORK");
            ban(connection, false, "d4e5f", ALICE, HOUR, NOW - 10 * DAY, "CONSOLE", "Short", "198.51.100.7", "NETWORK");
            insert(connection, "INSERT INTO `unbans` (id, player, unbanner, creationdate, reason, type) VALUES (?, ?, ?, ?, ?, ?)",
                    "c3d4e", BOB, MOD, local(NOW - 4 * DAY), "Appeal accepted", "NETWORK");
            insert(connection, "INSERT INTO `unbans` (id, player, unbanner, creationdate, reason, type) VALUES (?, ?, ?, ?, ?, ?)",
                    "d4e5f", ALICE, "CONSOLE", local(NOW - 10 * DAY + 2 * HOUR), "Strafe abgelaufen", "NETWORK");
            insert(connection, "INSERT INTO `kicks` (player, creator, reason, creationdate) VALUES (?, ?, ?, ?)",
                    ALICE, "CONSOLE", "AFK", local(NOW - DAY));
            insert(connection, "INSERT INTO `kicks` (player, creator, reason, creationdate) VALUES (?, ?, ?, ?)",
                    ALICE, "CONSOLE", "AFK", local(NOW - DAY));
        }
    }

    private static void ban(Connection connection, boolean active, String id, String player, long duration, long created,
                            String creator, String reason, String ip, String type) throws Exception {
        insert(connection, String.format(BAN, "banhistories"), id, player, (double) duration, local(created), creator,
                reason, ip, type);
        if (active) {
            insert(connection, String.format(BAN, "bans"), id, player, (double) duration, local(created), creator, reason,
                    ip, type);
        }
    }

    private static void insert(Connection connection, String sql, Object... values) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            statement.executeUpdate();
        }
    }

    private static String local(long millis) {
        return LOCAL.format(Instant.ofEpochMilli(millis));
    }

    private BanSystemSource source() {
        return new BanSystemSource(SourceFixtures.context(root));
    }

    @Test
    void theInventoryCountsBansMutesAndKicks() throws Exception {
        try (SourceReader reader = source().open()) {
            SourceInventory inventory = reader.inventory();

            assertTrue(source().isPresent());
            assertEquals(2, inventory.getPlayers());
            assertEquals(3, inventory.getBans());
            assertEquals(1, inventory.getMutes());
            assertEquals(2, inventory.getKicks());
            assertEquals(0, inventory.getWithoutAccount());
        }
    }

    @Test
    void activeLapsedUnbannedAndExpiredBansAreTold() throws Exception {
        try (SourceReader reader = source().open()) {
            Map<String, SourcePlayer> players = SourceFixtures.players(reader);
            SourcePlayer alice = players.get(ALICE);
            SourcePlayer bob = players.get(BOB);

            SourcePunishment hacking = byReason(alice, "Hacking");
            assertTrue(hacking.isInForce());
            assertEquals("Console", hacking.getIssuerName());
            assertEquals(NOW - 3 * DAY, hacking.getIssuedAt());
            SourcePunishment spam = byReason(bob, "Spam");
            assertEquals(PunishmentKind.MUTE, spam.getKind());
            assertFalse(spam.isInForce());
            assertFalse(spam.isLifted());
            assertEquals("Mod", spam.getIssuerName());
            Removal removal = byReason(bob, "Mistake").getRemoval();
            assertNotNull(removal);
            assertEquals("Mod", removal.getBy());
            assertEquals("Appeal accepted", removal.getReason());
            assertNull(byReason(alice, "Short").getRemoval());
            assertEquals(PunishmentKind.KICK, byReason(alice, "AFK").getKind());
        }
    }

    @Test
    void addressesAndKnownNamesBecomeHistory() throws Exception {
        try (SourceReader reader = source().open()) {
            SourcePlayer alice = SourceFixtures.players(reader).get(ALICE);

            assertEquals("198.51.100.7", alice.getAddresses().get(0).getIp());
            assertEquals("Alice", alice.getUsernames().get(0).getName());
        }
    }

    @Test
    void kicksInTheSameSecondKeepTheirOwnIds() throws Exception {
        try (SourceReader reader = source().open()) {
            SourcePlayer alice = SourceFixtures.players(reader).get(ALICE);

            assertEquals(2, alice.getPunishments().stream().filter(kick -> kick.getReason().equals("AFK"))
                    .map(SourcePunishment::getId).distinct().count());
        }
    }

    @Test
    void aRunningBanSystemIsReadFromACopyOfItsDatabase() throws Exception {
        try (SourceReader reader = new BanSystemSource(SourceFixtures.context(root, "BanSystem"::equals)).open()) {
            assertEquals(2, reader.inventory().getPlayers());
        }
        try (Stream<Path> left = Files.list(root.resolve("plugins").resolve("modl").resolve("import").resolve("copies"))) {
            assertEquals(0, left.count());
        }
    }

    private static SourcePunishment byReason(SourcePlayer player, String reason) {
        return player.getPunishments().stream().filter(punishment -> punishment.getReason().equals(reason)).findFirst()
                .orElseThrow(() -> new AssertionError("No punishment " + reason + " in " + player.getPunishments()));
    }
}
