package gg.modl.backend.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.HttpIntegrationTest;
import gg.modl.backend.support.JsonHelper;
import gg.modl.backend.support.TestDataProvider;
import gg.modl.backend.support.TestDataProvider.PlayerInfo;
import gg.modl.backend.support.TestDatabase;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@HttpIntegrationTest
class MinecraftPlayerApiTest {

    static ApiClient api;

    private static String testUuid;
    private static String testUsername;

    @BeforeAll
    static void setUp() throws Exception {
        api = new ApiClient();

        PlayerInfo player = TestDataProvider.getPlayers().get(0);
        testUuid = player.uuid();
        testUsername = player.username();
        logInTestPlayer();
    }

    private static HttpResponse<String> logInTestPlayer() throws Exception {
        return api.minecraftPost("/v1/minecraft/players/login", Map.of(
            "minecraftUUID", testUuid,
            "username", testUsername,
            "ip", "127.0.0.1",
            "serverName", "lobby"
        ));
    }

    @Test
    void login() throws Exception {
        var response = logInTestPlayer();
        // 201 for new player, 200 for returning player
        int status = Integer.parseInt(JsonHelper.parseObject(response.body()).get("status").getAsString());
        assertTrue(status == 200 || status == 201, "Expected 200 or 201 but got " + status);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("activePunishments"));

        // DB VERIFICATION: confirm player document updated
        if (TestDatabase.isAvailable()) {
            var dbPlayer = TestDatabase.getInstance().findPlayerByUuid(testUuid);
            assertNotNull(dbPlayer, "Player should exist in DB after login");
            assertTrue(TestDatabase.getInstance().usernamesOf(dbPlayer).contains(testUsername),
                "Usernames should contain " + testUsername);
        }
    }

    @Test
    void disconnect() throws Exception {
        var response = api.minecraftPost("/v1/minecraft/players/disconnect", Map.of(
            "minecraftUuid", testUuid,
            "sessionDurationMs", 5000
        ));
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void updateServer() throws Exception {
        var response = api.minecraftPost("/v1/minecraft/players/update-server", Map.of(
            "minecraftUuid", testUuid,
            "serverName", "lobby"
        ));
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void getOnlinePlayers() throws Exception {
        var response = api.minecraftGet("/v1/minecraft/players/online");
        JsonHelper.assertStatus(response, 200);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("players"));
    }

    @Test
    void getPlayerByUuid() throws Exception {
        var response = api.minecraftGet("/v1/minecraft/players/" + testUuid);
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void lookupPlayerByName() throws Exception {
        var response = api.minecraftGet("/v1/minecraft/players/by-name?username=" + testUsername + "&queryMojang=false");
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void lookupPost() throws Exception {
        var response = api.minecraftPost("/v1/minecraft/players/lookup", Map.of(
            "query", testUsername,
            "shouldQueryMojang", false
        ));
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void addNote() throws Exception {
        var response = api.minecraftPost("/v1/minecraft/players/" + testUuid + "/notes", Map.of(
            "text", "API test note - safe to ignore",
            "issuerName", "TestBot",
            "kind", "NOTE",
            "console", true
        ));
        JsonHelper.assertStatus(response, 200);

        // DB VERIFICATION: confirm note exists in player document
        if (TestDatabase.isAvailable()) {
            var dbPlayer = TestDatabase.getInstance().findPlayerByUuid(testUuid);
            assertNotNull(dbPlayer, "Player should exist in DB");
            assertTrue(TestDatabase.getInstance().notesOf(dbPlayer).contains("API test note - safe to ignore"),
                "Should contain the test note");
        }
    }

    @Test
    void getLinkedAccounts() throws Exception {
        var response = api.minecraftGet("/v1/minecraft/players/" + testUuid + "/linked-accounts");
        JsonHelper.assertStatus(response, 200);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("linkedAccounts"));
    }

    @Test
    void getPlayerReports() throws Exception {
        var response = api.minecraftGet("/v1/minecraft/players/" + testUuid + "/reports");
        JsonHelper.assertStatus(response, 200);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("reports"));
    }

    @Test
    void pardonByName() throws Exception {
        // This may or may not find active punishments, both 200 outcomes are fine
        var response = api.minecraftPost("/v1/minecraft/players/pardon", Map.of(
            "console", true,
            "playerName", testUsername,
            "issuerName", "TestBot"
        ));
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void rejectsWithoutApiKey() throws Exception {
        var response = api.rawGet("/v1/minecraft/players/online");
        assertEquals(401, response.statusCode());
    }

    @Test
    void lookupDifferentPlayers() throws Exception {
        var players = TestDataProvider.getPlayers();
        for (var player : players) {
            var response = api.minecraftGet("/v1/minecraft/players/" + player.uuid());
            JsonHelper.assertStatus(response, 200);
        }
    }
}
