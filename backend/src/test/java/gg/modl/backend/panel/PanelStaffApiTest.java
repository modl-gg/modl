package gg.modl.backend.panel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.HttpIntegrationTest;
import gg.modl.backend.support.JsonHelper;
import gg.modl.backend.support.TestDatabase;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@HttpIntegrationTest
class PanelStaffApiTest {

    private static final String FIXTURE_STAFF = "helper";

    static ApiClient api;

    @BeforeAll
    static void setUp() {
        api = new ApiClient();
    }

    @Test
    void listStaff() throws Exception {
        var response = api.panelGet("/v1/panel/staff");
        JsonHelper.assertStatus(response, 200);
        var arr = JsonHelper.parseArrayField(response.body(), "staff");
        assertNotNull(arr);
    }

    @Test
    void getStaffByUsername() throws Exception {
        String username = persistedStaffUsername();

        var response = api.panelGet("/v1/panel/staff/" + username);
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void checkUsername() throws Exception {
        var response = api.panelGet("/v1/panel/staff/check-username/nonexistent-test-user-12345");
        JsonHelper.assertStatus(response, 200);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("exists"));
    }

    @Test
    void createAndDeleteStaff() throws Exception {
        String testUsername = "apitest" + System.currentTimeMillis();
        String testEmail = "api-test-" + System.currentTimeMillis() + "@example.com";
        var createResponse = api.panelPost("/v1/panel/staff", Map.of(
            "email", testEmail,
            "username", testUsername,
            "role", "Moderator"
        ));
        int status = createResponse.statusCode();
        assertTrue(status == 200 || status == 201, "Expected 200 or 201 but got " + status);

        // DB VERIFICATION: confirm staff created
        if (TestDatabase.isAvailable()) {
            var dbStaff = TestDatabase.getInstance().findStaffByUsername(testUsername);
            assertNotNull(dbStaff, "Staff should exist in DB after creation");
        }

        var json = JsonHelper.parseObject(createResponse.body());
        String staffId = json.has("id") ? json.get("id").getAsString() : null;
        assertNotNull(staffId, "The response must carry the created id");
        if (staffId != null) {
            // Cleanup: delete
            api.panelDelete("/v1/panel/staff/" + staffId);

            // DB VERIFICATION: confirm staff deleted
            if (TestDatabase.isAvailable()) {
                var dbStaff = TestDatabase.getInstance().findStaffByUsername(testUsername);
                assertNull(dbStaff, "Staff should not exist in DB after deletion");
            }
        }
    }

    @Test
    void updateStaff() throws Exception {
        String username = persistedStaffUsername();

        // Idempotent update
        var response = api.panelPatch("/v1/panel/staff/" + username, Map.of(
            "username", username
        ));
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void updateStaffRole() throws Exception {
        var listResponse = api.panelGet("/v1/panel/staff");
        var arr = JsonHelper.parseArrayField(listResponse.body(), "staff");
        assertTrue(arr.size() >= 2, "The fixture must provide at least 2 entries");

        var staff = StreamSupport.stream(arr.spliterator(), false)
            .map(JsonElement::getAsJsonObject)
            .filter(member -> FIXTURE_STAFF.equals(stringField(member, "username")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("The fixture must provide " + FIXTURE_STAFF));
        String staffId = staff.get("id").getAsString();
        String currentRole = staff.has("role") ? staff.get("role").getAsString() : "Moderator";

        // Set same role (idempotent)
        var response = api.panelPatch("/v1/panel/staff/" + staffId + "/role", Map.of(
            "role", currentRole
        ));
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void inviteStaff() throws Exception {
        var response = api.panelPost("/v1/panel/staff/invite", Map.of(
            "email", "invite-test-" + System.currentTimeMillis() + "@example.com",
            "role", "Moderator"
        ));
        int inviteStatus = response.statusCode();
        assertTrue(inviteStatus == 200 || inviteStatus == 201, "Expected 200 or 201 but got " + inviteStatus);
    }

    @Test
    void assignMinecraftPlayer() throws Exception {
        var listResponse = api.panelGet("/v1/panel/staff");
        var arr = JsonHelper.parseArrayField(listResponse.body(), "staff");
        assertFalse(arr.isEmpty(), "The fixture must provide arr");
        String email = arr.get(0).getAsJsonObject().get("email").getAsString();

        var response = api.panelPatch("/v1/panel/staff/" + email + "/minecraft-player", Map.of());
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void getAvailablePlayers() throws Exception {
        var response = api.panelGet("/v1/panel/staff/available-players");
        JsonHelper.assertStatus(response, 200);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("players"));
    }

    private static String persistedStaffUsername() throws Exception {
        var staff = JsonHelper.parseArrayField(api.panelGet("/v1/panel/staff").body(), "staff");
        Optional<String> username = StreamSupport.stream(staff.spliterator(), false)
            .map(JsonElement::getAsJsonObject)
            .filter(member -> !stringField(member, "id").isEmpty())
            .map(member -> stringField(member, "username"))
            .filter(name -> !name.isEmpty())
            .findFirst();
        assertTrue(username.isPresent(), "The fixture must provide a persisted staff member with a username");
        return username.get();
    }

    private static String stringField(JsonObject object, String field) {
        return object.has(field) ? object.get(field).getAsString() : "";
    }
}
