package gg.modl.backend.panel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.HttpIntegrationTest;
import gg.modl.backend.support.JsonHelper;
import gg.modl.backend.support.TestDataProvider;
import gg.modl.backend.support.TestDataProvider.PlayerInfo;
import gg.modl.backend.support.TestDatabase;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@HttpIntegrationTest
class PanelPlayerApiTest {

    static ApiClient api;

    private static String testUuid;
    private static String testUsername;
    private static int testTypeOrdinal;

    @BeforeAll
    static void setUp() {
        api = new ApiClient();

        PlayerInfo player = TestDataProvider.getPlayers().get(0);
        testUuid = player.uuid();
        testUsername = player.username();
        testTypeOrdinal = TestDataProvider.getPunishmentTypes().get(0).ordinal();
    }

    @Test
    void searchPlayers() throws Exception {
        var response = api.panelGet("/v1/panel/players?search=" + testUsername);
        JsonHelper.assertStatus(response, 200);
        var arr = JsonHelper.parseArrayField(response.body(), "items");
        assertNotNull(arr);
    }

    @Test
    void getPlayerByUuid() throws Exception {
        var response = api.panelGet("/v1/panel/players/" + testUuid);
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void createPlayer() throws Exception {
        String uniqueUuid = UUID.randomUUID().toString();
        var response = api.panelPost("/v1/panel/players", Map.of(
            "minecraftUuid", uniqueUuid,
            "username", "ApiTestPlayer"
        ));
        int status = response.statusCode();
        assertTrue(status == 200 || status == 201, "Expected 200 or 201 but got " + status);

        // DB VERIFICATION: confirm player created
        if (TestDatabase.isAvailable()) {
            var dbPlayer = TestDatabase.getInstance().findPlayerByUuid(uniqueUuid);
            assertNotNull(dbPlayer, "Player should exist in DB after creation");
        }
    }

    @Test
    void addUsername() throws Exception {
        // Use a throwaway player to avoid corrupting the main test player
        String throwawayUuid = UUID.randomUUID().toString();
        api.panelPost("/v1/panel/players", Map.of(
            "minecraftUuid", throwawayUuid,
            "username", "ThrowAway"
        ));

        String alias = "TAlias" + (System.currentTimeMillis() % 1000000);
        var response = api.panelPost("/v1/panel/players/" + throwawayUuid + "/usernames", Map.of(
            "username", alias
        ));
        JsonHelper.assertStatus(response, 200);

        if (TestDatabase.isAvailable()) {
            var database = TestDatabase.getInstance();
            assertTrue(database.usernamesOf(database.findPlayerByUuid(throwawayUuid)).contains(alias), "Should contain the added username");
        }
    }

    @Test
    void addNote() throws Exception {
        var response = api.panelPost("/v1/panel/players/" + testUuid + "/notes", Map.of(
            "text", "Panel API test note",
            "issuerName", "TestBot"
        ));
        JsonHelper.assertStatus(response, 200);

        // DB VERIFICATION: confirm note exists
        if (TestDatabase.isAvailable()) {
            var dbPlayer = TestDatabase.getInstance().findPlayerByUuid(testUuid);
            assertNotNull(dbPlayer, "Player should exist in DB");
            assertTrue(TestDatabase.getInstance().notesOf(dbPlayer).contains("Panel API test note"),
                "Should contain the panel test note");
        }
    }

    @Test
    void addIp() throws Exception {
        var response = api.panelPost("/v1/panel/players/" + testUuid + "/ips", Map.of(
            "ipAddress", "192.168.1.1"
        ));
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void getActivePunishments() throws Exception {
        var response = api.panelGet("/v1/panel/players/" + testUuid + "/punishments/active");
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void searchPunishments() throws Exception {
        var response = api.panelGet("/v1/panel/players/punishments/search?q=test&activeOnly=false");
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void getLinkedAccounts() throws Exception {
        var response = api.panelGet("/v1/panel/players/" + testUuid + "/linked");
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void findLinked() throws Exception {
        var response = api.panelPost("/v1/panel/players/" + testUuid + "/find-linked", Map.of());
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void createPunishmentFromPanel() throws Exception {
        var response = api.panelPost("/v1/panel/players/" + testUuid + "/punishments", Map.of(
            "typeOrdinal", testTypeOrdinal,
            "reason", "Panel API test - auto cleanup",
            "duration", 60,
            "severity", "LOW",
            "issuerName", "TestBot"
        ));
        JsonHelper.assertStatus(response, 200);

        // Cleanup via minecraft pardon
        api.minecraftPost("/v1/minecraft/players/pardon", Map.of(
            "console", true,
            "playerName", testUsername,
            "issuerName", "TestBot",
            "reason", "Panel API test cleanup"
        ));
    }

    @Test
    void addPunishmentNote() throws Exception {
        // Create a punishment first
        var createResponse = api.minecraftPost("/v1/minecraft/punishments/dynamic", Map.of(
            "targetUuid", testUuid,
            "issuerName", "TestBot",
            "console", true, "type_ordinal", testTypeOrdinal,
            "reason", "Panel API test - punishment note",
            "duration", 60,
            "severity", "LOW",
            "status", "ACTIVE"
        ));
        JsonHelper.assertStatus(createResponse, 200);
        String punishmentId = JsonHelper.parseObject(createResponse.body()).get("punishmentId").getAsString();

        var response = api.panelPost("/v1/panel/players/" + testUuid + "/punishments/" + punishmentId + "/notes", Map.of(
            "text", "Panel test note",
            "issuerName", "TestBot"
        ));
        JsonHelper.assertStatus(response, 200);

        // DB VERIFICATION: confirm note on punishment
        if (TestDatabase.isAvailable()) {
            var dbPunishment = TestDatabase.getInstance().findPunishmentInPlayer(testUuid, punishmentId);
            assertNotNull(dbPunishment, "Punishment should exist in DB");
            assertTrue(TestDatabase.getInstance().notesOf(dbPunishment).contains("Panel test note"),
                "Should contain the panel test note");
        }

        // Cleanup
        api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/pardon", Map.of(
            "console", true,
            "issuerName", "TestBot",
            "reason", "cleanup"
        ));
    }

    @Test
    void getPunishmentById() throws Exception {
        // Create a punishment first
        var createResponse = api.minecraftPost("/v1/minecraft/punishments/dynamic", Map.of(
            "targetUuid", testUuid,
            "issuerName", "TestBot",
            "console", true, "type_ordinal", testTypeOrdinal,
            "reason", "Panel API test - get by id",
            "duration", 60,
            "severity", "LOW",
            "status", "ACTIVE"
        ));
        JsonHelper.assertStatus(createResponse, 200);
        String punishmentId = JsonHelper.parseObject(createResponse.body()).get("punishmentId").getAsString();

        var response = api.panelGet("/v1/panel/players/punishments/" + punishmentId);
        JsonHelper.assertStatus(response, 200);

        // Cleanup
        api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/pardon", Map.of(
            "console", true,
            "issuerName", "TestBot",
            "reason", "cleanup"
        ));
    }

    @Test
    void addEvidence() throws Exception {
        var createResponse = api.minecraftPost("/v1/minecraft/punishments/dynamic", Map.of(
            "targetUuid", testUuid,
            "issuerName", "TestBot",
            "console", true, "type_ordinal", testTypeOrdinal,
            "reason", "Panel API test - evidence",
            "duration", 60,
            "severity", "LOW",
            "status", "ACTIVE"
        ));
        JsonHelper.assertStatus(createResponse, 200);
        String punishmentId = JsonHelper.parseObject(createResponse.body()).get("punishmentId").getAsString();

        var response = api.panelPost("/v1/panel/players/" + testUuid + "/punishments/" + punishmentId + "/evidence", Map.of(
            "url", "https://example.com/evidence.png",
            "type", "LINK",
            "issuerName", "TestBot"
        ));
        JsonHelper.assertStatus(response, 200);

        // DB VERIFICATION: confirm evidence added
        if (TestDatabase.isAvailable()) {
            var dbPunishment = TestDatabase.getInstance().findPunishmentInPlayer(testUuid, punishmentId);
            assertNotNull(dbPunishment, "Punishment should exist in DB");
            var evidence = TestDatabase.getInstance().evidenceUrlsOf(dbPunishment);
            assertFalse(evidence.isEmpty(), "Evidence list should not be empty");
        }

        // Cleanup
        api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/pardon", Map.of(
            "console", true,
            "issuerName", "TestBot",
            "reason", "cleanup"
        ));
    }

    @Test
    void getLinkedBans() throws Exception {
        var createResponse = api.minecraftPost("/v1/minecraft/punishments/dynamic", Map.of(
            "targetUuid", testUuid,
            "issuerName", "TestBot",
            "console", true, "type_ordinal", testTypeOrdinal,
            "reason", "Panel API test - linked bans",
            "duration", 60,
            "severity", "LOW",
            "status", "ACTIVE"
        ));
        JsonHelper.assertStatus(createResponse, 200);
        String punishmentId = JsonHelper.parseObject(createResponse.body()).get("punishmentId").getAsString();

        var response = api.panelGet("/v1/panel/players/punishments/" + punishmentId + "/linked-bans");
        JsonHelper.assertStatus(response, 200);

        // Cleanup
        api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/pardon", Map.of(
            "console", true,
            "issuerName", "TestBot",
            "reason", "cleanup"
        ));
    }
}

