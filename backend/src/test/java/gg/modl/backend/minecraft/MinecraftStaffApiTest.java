package gg.modl.backend.minecraft;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.HttpIntegrationTest;
import gg.modl.backend.support.JsonHelper;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@HttpIntegrationTest
class MinecraftStaffApiTest {

    static ApiClient api;

    @BeforeAll
    static void setUp() {
        api = new ApiClient();
    }

    @Test
    void listStaff() throws Exception {
        var response = api.minecraftGet("/v1/minecraft/staff");
        JsonHelper.assertStatus(response, 200);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("staff"));
    }

    @Test
    void getPermissions() throws Exception {
        var response = api.minecraftGet("/v1/minecraft/staff/permissions");
        JsonHelper.assertStatus(response, 200);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("data"));
    }

    @Test
    void updateStaffRole() throws Exception {
        // Get a staff member to update
        var listResponse = api.minecraftGet("/v1/minecraft/staff");
        var json = JsonHelper.parseObject(listResponse.body());
        var staff = json.getAsJsonArray("staff");
        assertFalse(staff.isEmpty(), "The fixture must provide staff");

        String staffId = staff.get(0).getAsJsonObject().get("id").getAsString();
        String currentRole = staff.get(0).getAsJsonObject().get("role").getAsString();
        String path = "/v1/minecraft/staff/" + staffId + "/role";
        Map<String, String> body = Map.of("role", currentRole);

        JsonHelper.assertStatus(api.minecraftPatch(path, body), 403);
        JsonHelper.assertStatus(api.minecraftPatchAs(createActingAdmin(), path, body), 200);
    }

    private static String createActingAdmin() throws Exception {
        var created = api.panelPost("/v1/panel/staff", Map.of(
            "email", "acting-admin@example.com", "username", "actingadmin", "role", "Admin"));
        JsonHelper.assertSuccess(created);
        return JsonHelper.parseObject(created.body()).get("id").getAsString();
    }
}
