package gg.modl.minecraft.core.imports.vanilla;

import gg.modl.minecraft.core.imports.source.PunishmentKind;
import gg.modl.minecraft.core.imports.source.PunishmentScope;
import gg.modl.minecraft.core.imports.source.SourceInventory;
import gg.modl.minecraft.core.imports.source.SourcePlayer;
import gg.modl.minecraft.core.imports.source.SourcePunishment;
import gg.modl.minecraft.core.imports.source.SourceReader;
import gg.modl.minecraft.core.imports.sql.SourceFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static gg.modl.minecraft.core.imports.sql.SourceFixtures.ALICE;
import static gg.modl.minecraft.core.imports.sql.SourceFixtures.BOB;
import static gg.modl.minecraft.core.imports.sql.SourceFixtures.DAY;
import static gg.modl.minecraft.core.imports.sql.SourceFixtures.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VanillaSourceTest {
    private static final String CAROL = "55555555-5555-4555-8555-555555555555";

    @TempDir
    Path root;

    @BeforeEach
    void writeFiles() throws Exception {
        SourceFixtures.write(root.resolve("banned-players.json"), "[\n"
                + "  {\"uuid\": \"" + ALICE + "\", \"name\": \"Alice\", \"created\": \"2026-05-29 00:00:00 +0000\","
                + " \"source\": \"Server\", \"expires\": \"forever\", \"reason\": \"Banned by an operator.\"},\n"
                + "  {\"uuid\": \"" + BOB + "\", \"name\": \"Bob\", \"created\": \"2026-05-20 00:00:00 +0200\","
                + " \"source\": \"§cMod\", \"expires\": \"2026-05-21 00:00:00 +0200\", \"reason\": \"Spam\"},\n"
                + "  {\"name\": \"Nobody\", \"created\": \"2026-05-20 00:00:00 +0000\", \"source\": \"(Unknown)\", \"expires\": \"forever\"}\n"
                + "]\n");
        SourceFixtures.write(root.resolve("banned-ips.json"), """
                [
                  {"ip": "198.51.100.7", "created": "2026-05-30 00:00:00 +0000", "source": "Rcon", "expires": "forever", "reason": "Alts"},
                  {"ip": "203.0.113.9", "created": "2026-05-30 00:00:00 +0000", "source": "Server", "expires": "forever"}
                ]
                """);
        Path users = root.resolve("plugins").resolve("Essentials").resolve("userdata");
        SourceFixtures.write(users.resolve(ALICE + ".yml"), "last-account-name: Alice\nip-address: 198.51.100.7\n"
                + "timestamps:\n  login: " + (NOW - DAY) + "\n  logout: " + (NOW - DAY) + "\n");
        SourceFixtures.write(users.resolve(CAROL + ".yml"), "lastAccountName: Carol\nipAddress: 198.51.100.8\nmuted: true\n"
                + "muteReason: Caps\ntimestamps:\n  login: " + (NOW - 400 * DAY) + "\n  mute: " + (NOW + DAY) + "\n");
        SourceFixtures.write(users.resolve(BOB + ".yml"), "muted: true\nmute-reason: Old\ntimestamps:\n  mute: " + (NOW - DAY) + "\n");
    }

    private VanillaSource source() {
        return new VanillaSource(SourceFixtures.context(root));
    }

    @Test
    void banListsAndEssentialsMutesAreCounted() throws Exception {
        try (SourceReader reader = source().open()) {
            SourceInventory inventory = reader.inventory();

            assertTrue(source().isPresent());
            assertEquals(5, inventory.getBans());
            assertEquals(1, inventory.getMutes());
            assertEquals(2, inventory.getIpWide());
            assertEquals(2, inventory.getWithoutAccount());
            assertEquals(3, inventory.getPlayers());
            assertEquals(1, reader.historyEntriesBefore(NOW - 365 * DAY));
        }
    }

    @Test
    void entriesKeepTheirDatesSourcesAndExpiry() throws Exception {
        try (SourceReader reader = source().open()) {
            Map<String, SourcePlayer> players = SourceFixtures.players(reader);

            SourcePunishment alice = byReason(players.get(ALICE), "Banned by an operator.");
            assertTrue(alice.isInForce());
            assertEquals("Console", alice.getIssuerName());
            assertEquals(0, alice.getDuration());
            SourcePunishment bob = byReason(players.get(BOB), "Spam");
            assertFalse(bob.isInForce());
            assertEquals("Mod", bob.getIssuerName());
            assertEquals(DAY, bob.getDuration());
            SourcePunishment alts = byReason(players.get(ALICE), "Alts");
            assertEquals(PunishmentScope.IP_WIDE, alts.getScope());
            assertTrue(alts.isAttributedByAddress());
            assertFalse(alts.blocksAltsWhenWidened());
        }
    }

    @Test
    void anEssentialsMuteRunsUntilItsTimeoutAndUserDataBecomesHistory() throws Exception {
        try (SourceReader reader = source().open()) {
            Map<String, SourcePlayer> players = SourceFixtures.players(reader);
            SourcePlayer carol = players.get(CAROL);

            SourcePunishment mute = carol.getPunishments().get(0);
            assertEquals(PunishmentKind.MUTE, mute.getKind());
            assertEquals("Caps", mute.getReason());
            assertNull(mute.getIssuerName());
            assertEquals(DAY, mute.getDuration());
            assertEquals("Carol", carol.getUsernames().get(0).getName());
            assertEquals("198.51.100.8", carol.getAddresses().get(0).getIp());
            assertTrue(players.get(BOB).getPunishments().stream().noneMatch(punishment -> punishment.getReason().equals("Old")));
        }
    }

    private static SourcePunishment byReason(SourcePlayer player, String reason) {
        return player.getPunishments().stream().filter(punishment -> punishment.getReason().equals(reason)).findFirst()
                .orElseThrow(() -> new AssertionError("No punishment " + reason + " in " + player.getPunishments()));
    }
}
