package gg.modl.backend.public_api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.settings.data.DefaultPunishmentTypes;
import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.HttpIntegrationTest;
import gg.modl.backend.support.JsonHelper;
import gg.modl.backend.support.TestDataProvider;
import gg.modl.backend.support.TestDatabase;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@HttpIntegrationTest
class PublicAppealApiTest {

    static ApiClient api;

    private static String testUuid;

    @BeforeAll
    static void setUp() {
        api = new ApiClient();

        testUuid = TestDataProvider.getPlayers().get(0).uuid();
    }

    @Test
    void getAppeal() throws Exception {
        var response = api.publicGet("/v1/public/appeals/nonexistent-appeal-id");
        assertEquals(404, response.statusCode());
    }

    @Test
    void createAndGetAppeal() throws Exception {
        // Need an active punishment first
        var createPunishment = api.minecraftPost("/v1/minecraft/punishments/dynamic", Map.of(
            "targetUuid", testUuid,
            "issuerName", "TestBot",
            "console", true, "type_ordinal", DefaultPunishmentTypes.MANUAL_BAN_ORDINAL,
            "reason", "Public appeal test",
            "duration", 300,
            "severity", "LOW",
            "status", "ACTIVE"
        ));
        JsonHelper.assertStatus(createPunishment, 200);
        String punishmentId = JsonHelper.parseObject(createPunishment.body()).get("punishmentId").getAsString();
        JsonHelper.assertStatus(api.minecraftPost("/v1/minecraft/punishments/acknowledge", Map.of(
            "punishmentId", punishmentId,
            "playerUuid", testUuid,
            "executedAt", "2026-01-01T00:00:00Z",
            "success", true)), 200);

        // Create appeal
        var createAppeal = api.publicPost("/v1/public/appeals", Map.of(
            "punishmentId", punishmentId,
            "playerUuid", testUuid,
            "email", "test@example.com",
            "reason", "API test appeal - auto cleanup"
        ));
        int appealStatus = createAppeal.statusCode();
        assertTrue(appealStatus == 200 || appealStatus == 201,
            "Expected 200 or 201 but got " + appealStatus + ": " + createAppeal.body());

        if (appealStatus == 200 || appealStatus == 201) {
            var json = JsonHelper.parseObject(createAppeal.body());
            String appealId = json.has("appealId") ? json.get("appealId").getAsString() : null;
            assertNotNull(appealId, "The response must carry the created id");
            if (appealId != null) {
                // DB VERIFICATION: confirm appeal created as ticket with type=appeal
                if (TestDatabase.isAvailable()) {
                    var dbTicket = TestDatabase.getInstance().findTicketById(appealId);
                    assertNotNull(dbTicket, "Appeal should exist in DB (tickets table) after creation");
                    assertEquals("appeal", dbTicket.getType());
                }

                var bareIdGet = api.publicGet("/v1/public/appeals/" + appealId);
                assertEquals(403, bareIdGet.statusCode());
                assertTrue(JsonHelper.parseObject(bareIdGet.body()).get("requiresVerification").getAsBoolean());

                String publicUrl = json.get("publicUrl").getAsString();
                String accessKey = publicUrl.substring(publicUrl.indexOf("?key=") + "?key=".length());
                var keyedGet = api.publicGet("/v1/public/appeals/" + appealId + "?token=" + accessKey);
                JsonHelper.assertStatus(keyedGet, 200);
                assertEquals(appealId, JsonHelper.parseObject(keyedGet.body()).get("id").getAsString());

                var invalidCodeVerify = api.publicPost("/v1/public/appeals/" + appealId + "/verify", Map.of("code", "000000"));
                assertEquals(403, invalidCodeVerify.statusCode());

                // Cleanup: dismiss via panel
                api.panelPatch("/v1/panel/appeals/" + appealId + "/status", Map.of("status", "dismissed"));
            }
        }

        // Cleanup punishment
        api.minecraftPost("/v1/minecraft/punishments/" + punishmentId + "/pardon", Map.of(
            "console", true,
            "issuerName", "TestBot",
            "reason", "cleanup"
        ));
    }

    @Test
    void replyToAppeal() throws Exception {
        var response = api.publicPost("/v1/public/appeals/nonexistent-appeal-id/replies", Map.of(
            "name", "PublicUser",
            "content", "Test reply",
            "type", "player",
            "staff", false
        ));
        assertEquals(404, response.statusCode());
    }
}

