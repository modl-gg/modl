package gg.modl.minecraft.core.imports.advancedban;

import gg.modl.minecraft.core.imports.source.PlayerRecords;
import gg.modl.minecraft.core.imports.source.PunishmentKind;
import gg.modl.minecraft.core.imports.source.PunishmentScope;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static gg.modl.minecraft.core.imports.sql.SourceFixtures.ALICE;
import static gg.modl.minecraft.core.imports.sql.SourceFixtures.BOB;
import static gg.modl.minecraft.core.imports.sql.SourceFixtures.DAY;
import static gg.modl.minecraft.core.imports.sql.SourceFixtures.NOW;
import static gg.modl.minecraft.core.imports.sql.SourceFixtures.punishment;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvancedBanSourceTest {
    private static final String ACTIVE = "INSERT INTO Punishments (name, uuid, reason, operator, punishmentType, start, end, calculation) VALUES (?, ?, ?, ?, ?, ?, ?, '')";
    private static final String HISTORY = "INSERT INTO PunishmentHistory (name, uuid, reason, operator, punishmentType, start, end, calculation) VALUES (?, ?, ?, ?, ?, ?, ?, '')";

    @TempDir
    Path root;

    @BeforeEach
    void createDatabase() throws Exception {
        Path folder = root.resolve("plugins").resolve("AdvancedBan");
        Files.createDirectories(folder.resolve("data"));
        SourceFixtures.write(folder.resolve("config.yml"), "UseMySQL: false\n");
        String url = "jdbc:hsqldb:file:" + folder.resolve("data").resolve("storage") + ";hsqldb.lock_file=false";
        try (Connection connection = SourceFixtures.connect(JdbcDriver.HSQLDB, url, "SA")) {
            SourceFixtures.runScript(connection, "/gg/modl/minecraft/core/imports/advancedban/schema.sql");
            String alice = ALICE.replace("-", "");
            String bob = BOB.replace("-", "");
            punish(connection, true, "Alice", alice, "Cheating", "CONSOLE", "BAN", NOW - 3 * DAY, -1);
            punish(connection, true, "Bob", bob, "Spam", "Mod", "TEMP_MUTE", NOW - 2 * DAY, NOW + DAY);
            punish(connection, false, "Bob", bob, "Old", "Mod", "TEMP_BAN", NOW - 10 * DAY, NOW - 9 * DAY);
            punish(connection, false, "Bob", bob, "Mistake", "Mod", "BAN", NOW - 5 * DAY, -1);
            punish(connection, true, "Alice", alice, "Language", "Mod", "WARNING", NOW - DAY, -1);
            punish(connection, false, "Alice", alice, "AFK", "CONSOLE", "KICK", NOW - DAY, -1);
            punish(connection, true, "Alice", "198.51.100.7", "Alts", "CONSOLE", "IP_BAN", NOW - DAY, -1);
            punish(connection, true, "203.0.113.9", "203.0.113.9", "Proxy", "CONSOLE", "IP_BAN", NOW - DAY, -1);
            punish(connection, true, "Carol", "carol", "Griefing", "Mod", "BAN", NOW - DAY, -1);
            punish(connection, true, "Bob", bob, "Watch him", "Mod", "NOTE", NOW - DAY, -1);
            punish(connection, false, "Bob", bob, "Revoked", "Mod", "WARNING", NOW - DAY, -1);
            punish(connection, false, "Bob", bob, "Unnoted", "Mod", "NOTE", NOW - DAY + 1, -1);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SHUTDOWN");
            }
        }
    }

    private static void punish(Connection connection, boolean active, String name, String uuid, String reason,
                               String operator, String type, long start, long end) throws Exception {
        for (String sql : active ? new String[]{HISTORY, ACTIVE} : new String[]{HISTORY}) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, name);
                statement.setString(2, uuid);
                statement.setString(3, reason);
                statement.setString(4, operator);
                statement.setString(5, type);
                statement.setLong(6, start);
                statement.setLong(7, end);
                statement.executeUpdate();
            }
        }
    }

    private AdvancedBanSource source(boolean running) {
        return new AdvancedBanSource(SourceFixtures.context(root, name -> running && "AdvancedBan".equals(name)));
    }

    @Test
    void theDataFolderIsDetected() {
        assertTrue(source(false).isPresent());
    }

    @Test
    void theInventoryCountsEveryKindAndRowsWithoutAnAccount() throws Exception {
        try (SourceReader reader = source(false).open()) {
            SourceInventory inventory = reader.inventory();

            assertEquals(3, inventory.getPlayers());
            assertEquals(6, inventory.getBans());
            assertEquals(1, inventory.getMutes());
            assertEquals(2, inventory.getWarnings());
            assertEquals(1, inventory.getKicks());
            assertEquals(1, inventory.getNotes());
            assertEquals(1, inventory.getIpWide());
            assertEquals(1, inventory.getWithoutAccount());
        }
    }

    @Test
    void activeExpiredAndRevokedPunishmentsAreTold() throws Exception {
        try (SourceReader reader = source(false).open()) {
            Map<String, SourcePlayer> players = SourceFixtures.players(reader);
            SourcePlayer alice = players.get(ALICE);
            SourcePlayer bob = players.get(BOB);

            SourcePunishment ban = byReason(alice, "Cheating");
            assertTrue(ban.isInForce());
            assertEquals(0, ban.getDuration());
            assertEquals("Console", ban.getIssuerName());
            SourcePunishment mute = byReason(bob, "Spam");
            assertTrue(mute.isInForce());
            SourceFixtures.assertMinutes(3 * DAY, mute.getDuration());
            SourcePunishment expired = byReason(bob, "Old");
            assertFalse(expired.isInForce());
            assertFalse(expired.isLifted());
            SourcePunishment revoked = byReason(bob, "Mistake");
            assertTrue(revoked.isEndedEarly());
            assertNull(revoked.getRemoval());
            assertEquals("Mod", revoked.getIssuerName());
        }
    }

    @Test
    void anIpBanTypedWithANameBelongsToThatAccountAndOfflineNamesGetOfflineUuids() throws Exception {
        try (SourceReader reader = source(false).open()) {
            Map<String, SourcePlayer> players = SourceFixtures.players(reader);

            SourcePunishment ipBan = byReason(players.get(ALICE), "Alts");
            assertEquals(PunishmentScope.IP_WIDE, ipBan.getScope());
            assertEquals(PunishmentKind.BAN, ipBan.getKind());
            SourcePlayer carol = players.get(PlayerKeys.offlineUuid("Carol"));
            assertEquals("Griefing", carol.getPunishments().get(0).getReason());
            assertEquals("Carol", carol.getUsernames().get(0).getName());
            assertEquals(3, players.size());
        }
    }

    @Test
    void anOnlineModeServerInventsNoOfflineUuidForANameOnlyRow() throws Exception {
        try (SourceReader reader = new AdvancedBanSource(SourceFixtures.context(root, name -> false, false)).open()) {
            Map<String, SourcePlayer> players = SourceFixtures.players(reader);

            assertFalse(players.containsKey(PlayerKeys.offlineUuid("Carol")));
            assertEquals(2, reader.inventory().getWithoutAccount());
        }
    }

    @Test
    void warningsNotesAndKicksKeepTheirKinds() throws Exception {
        try (SourceReader reader = source(false).open()) {
            Map<String, SourcePlayer> players = SourceFixtures.players(reader);

            assertEquals(PunishmentKind.WARNING, byReason(players.get(ALICE), "Language").getKind());
            assertEquals(PunishmentKind.KICK, byReason(players.get(ALICE), "AFK").getKind());
            assertEquals(PunishmentKind.NOTE, byReason(players.get(BOB), "Watch him").getKind());
            assertTrue(byReason(players.get(BOB), "Revoked").isLifted());
            assertTrue(players.get(BOB).getPunishments().stream().noneMatch(note -> note.getReason().equals("Unnoted")));
            assertTrue(punishment(players.get(ALICE), "advancedban-0").isInForce());
        }
    }

    @Test
    void anAccountStoredWithSeveralUuidSpellingsIsExportedOnce() throws Exception {
        String url = "jdbc:hsqldb:file:" + root.resolve("plugins").resolve("AdvancedBan").resolve("data").resolve("storage")
                + ";hsqldb.lock_file=false";
        try (Connection connection = SourceFixtures.connect(JdbcDriver.HSQLDB, url, "SA")) {
            punish(connection, false, "Alice", ALICE.replace("-", "").toUpperCase(Locale.ROOT), "Shouting", "Mod", "KICK",
                    NOW - DAY, -1);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SHUTDOWN");
            }
        }
        try (SourceReader reader = source(false).open()) {
            List<SourcePlayer> alices = new ArrayList<>();
            PlayerRecords records = reader.players();
            for (SourcePlayer player = records.next(); player != null; player = records.next()) {
                if (player.getUuid().equals(ALICE)) alices.add(player);
            }

            assertEquals(1, alices.size());
            byReason(alices.get(0), "Shouting");
            byReason(alices.get(0), "Cheating");
            byReason(alices.get(0), "Alts");
        }
    }

    @Test
    void aRunningAdvancedBanKeepsItsUnlockedFileClosedToUs() {
        SourceException refused = assertThrows(SourceException.class, () -> source(true).open());

        assertEquals("import.source_locked", refused.getMessageKey());
    }

    @Test
    void theOriginalDatabaseIsNeverTouchedAndTheCopyIsRemoved() throws Exception {
        Path data = root.resolve("plugins").resolve("AdvancedBan").resolve("data");
        long before = Files.getLastModifiedTime(data.resolve("storage.script")).toMillis();
        try (SourceReader reader = source(false).open()) {
            SourceFixtures.players(reader);
        }

        assertEquals(before, Files.getLastModifiedTime(data.resolve("storage.script")).toMillis());
        try (Stream<Path> left = Files.list(root.resolve("plugins").resolve("modl").resolve("import").resolve("copies"))) {
            assertEquals(0, left.count());
        }
    }

    private static SourcePunishment byReason(SourcePlayer player, String reason) {
        return player.getPunishments().stream().filter(punishment -> punishment.getReason().equals(reason)).findFirst()
                .orElseThrow(() -> new AssertionError("No punishment " + reason + " in " + player.getPunishments()));
    }
}
