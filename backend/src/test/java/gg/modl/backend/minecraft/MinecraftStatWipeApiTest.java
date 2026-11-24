package gg.modl.backend.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.settings.data.DefaultPunishmentTypes;
import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.HttpIntegrationTest;
import gg.modl.backend.support.JsonHelper;
import gg.modl.backend.support.TestDataProvider;
import gg.modl.backend.support.TestDataProvider.PlayerInfo;
import gg.modl.backend.support.TestDatabase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@HttpIntegrationTest
class MinecraftStatWipeApiTest {

    static ApiClient api;
    private static String testUuid;
    private static int testTypeOrdinal;

    @BeforeAll
    static void setUp() {
        api = new ApiClient();

        PlayerInfo player = TestDataProvider.getPlayerWithPunishments();
        testUuid = player.uuid();
        testTypeOrdinal = TestDataProvider.getPunishmentTypes().get(0).ordinal();
    }

    @Test
    void statWipeAcknowledgeOfAPunishmentStillInForceIsIgnored() throws Exception {
        // Create a punishment with wipeAfterExpiry enabled
        var createResponse = api.minecraftPost("/v1/minecraft/punishments/dynamic", Map.of(
            "targetUuid", testUuid,
            "issuerName", "TestBot",
            "console", true, "type_ordinal", testTypeOrdinal,
            "reason", "API Test - stat wipe acknowledge",
            "duration", 60,
            "severity", "LOW",
            "status", "ACTIVE",
            "data", Map.of("wipeAfterExpiry", true)
        ));
        JsonHelper.assertStatus(createResponse, 200);
        String punishmentId = JsonHelper.parseObject(createResponse.body()).get("punishmentId").getAsString();

        // Acknowledge the stat wipe
        var ackResponse = api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/stat-wipe-acknowledge", Map.of(
            "punishmentId", punishmentId,
            "serverName", "TestServer",
            "success", true
        ));
        JsonHelper.assertStatus(ackResponse, 200);
        var ackJson = JsonHelper.parseObject(ackResponse.body());
        assertEquals("No stat wipe is due for this punishment", ackJson.get("message").getAsString());

        if (TestDatabase.isAvailable()) {
            var dbPunishment = TestDatabase.getInstance().findPunishmentInPlayer(testUuid, punishmentId);
            assertNotNull(dbPunishment, "Punishment should exist in DB");
            assertNull(dbPunishment.getStatWipeCompletedAt(), "a stat wipe of a punishment still in force is not done");
        }

        // Cleanup
        api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/pardon", Map.of(
            "console", true,
            "issuerName", "TestBot",
            "reason", "API test cleanup"
        ));
    }

    @Test
    void statWipeAcknowledgeReturnsNotFoundForInvalidId() throws Exception {
        var ackResponse = api.minecraftPost("/v1/minecraft/punishments/nonexistent-id/stat-wipe-acknowledge", Map.of(
            "punishmentId", "nonexistent-id",
            "serverName", "TestServer",
            "success", true
        ));
        JsonHelper.assertStatus(ackResponse, 404);
    }

    @Test
    void statWipeAcknowledgeIgnoresWhenWipeDisabled() throws Exception {
        // Create punishment WITHOUT wipeAfterExpiry
        var createResponse = api.minecraftPost("/v1/minecraft/punishments/dynamic", Map.of(
            "targetUuid", testUuid,
            "issuerName", "TestBot",
            "console", true, "type_ordinal", testTypeOrdinal,
            "reason", "API Test - stat wipe disabled",
            "duration", 60,
            "severity", "LOW",
            "status", "ACTIVE"
        ));
        JsonHelper.assertStatus(createResponse, 200);
        String punishmentId = JsonHelper.parseObject(createResponse.body()).get("punishmentId").getAsString();

        // Acknowledge should succeed but not set the flag since wipeAfterExpiry is not enabled
        var ackResponse = api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/stat-wipe-acknowledge", Map.of(
            "punishmentId", punishmentId,
            "serverName", "TestServer",
            "success", true
        ));
        JsonHelper.assertStatus(ackResponse, 200);
        var ackJson = JsonHelper.parseObject(ackResponse.body());
        assertEquals("Stat wipe no longer enabled for this punishment", ackJson.get("message").getAsString());

        // Cleanup
        api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/pardon", Map.of(
            "console", true,
            "issuerName", "TestBot",
            "reason", "API test cleanup"
        ));
    }

    @Test
    void syncResponseIncludesPendingStatWipesField() throws Exception {
        // Verify the sync response now includes the pendingStatWipes field
        var response = api.minecraftPost("/v1/minecraft/players/sync", Map.of(
            "lastSyncTimestamp", "2025-01-01T00:00:00Z",
            "onlinePlayers", List.of()
        ));
        JsonHelper.assertStatus(response, 200);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("data"));
        var data = json.getAsJsonObject("data");
        assertTrue(data.has("pendingStatWipes"), "Sync response data should include pendingStatWipes field");
    }

    @Test
    void toggleStatWipeThenAcknowledge() throws Exception {
        // Create a punishment without wipeAfterExpiry
        var createResponse = api.minecraftPost("/v1/minecraft/punishments/dynamic", Map.of(
            "targetUuid", testUuid,
            "issuerName", "TestBot",
            "console", true, "type_ordinal", DefaultPunishmentTypes.MANUAL_BAN_ORDINAL,
            "reason", "API Test - toggle then ack",
            "duration", 60,
            "severity", "LOW",
            "status", "ACTIVE"
        ));
        JsonHelper.assertStatus(createResponse, 200);
        String punishmentId = JsonHelper.parseObject(createResponse.body()).get("punishmentId").getAsString();

        // Enable stat wipe via toggle
        var toggleResponse = api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/toggle", Map.of(
            "console", true,
            "issuerName", "TestBot",
            "option", "STAT_WIPE",
            "enabled", true
        ));
        JsonHelper.assertStatus(toggleResponse, 200);

        var ackResponse = api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/stat-wipe-acknowledge", Map.of(
            "punishmentId", punishmentId,
            "serverName", "TestServer",
            "success", true
        ));
        JsonHelper.assertStatus(ackResponse, 200);

        if (TestDatabase.isAvailable()) {
            var dbPunishment = TestDatabase.getInstance().findPunishmentInPlayer(testUuid, punishmentId);
            assertNotNull(dbPunishment, "Punishment should exist");
            assertTrue(dbPunishment.getWipeAfterExpiry(), "wipeAfterExpiry should be true");
            assertNull(dbPunishment.getStatWipeCompletedAt(), "the wipe is not due while the ban is in force");
        }

        // Cleanup
        api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/pardon", Map.of(
            "console", true,
            "issuerName", "TestBot",
            "reason", "API test cleanup"
        ));
    }
}
