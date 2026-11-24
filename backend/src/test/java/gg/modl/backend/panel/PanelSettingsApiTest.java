package gg.modl.backend.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.google.gson.JsonPrimitive;
import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.HttpIntegrationTest;
import gg.modl.backend.support.JsonHelper;
import gg.modl.backend.support.TestServerCredentials;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@HttpIntegrationTest
class PanelSettingsApiTest {
    private static final String SETTINGS_VIEW_PERMISSION = "admin.settings.view";
    private static final String SUPER_ADMIN_ROLE = "Super Admin";

    static ApiClient api;

    @BeforeAll
    static void setUp() {
        api = new ApiClient();
    }

    // -- Punishment Types --

    @Test
    void getPunishmentTypes() throws Exception {
        var response = api.panelGet("/v1/panel/settings/punishment-types");
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void getPunishmentTypeByOrdinal() throws Exception {
        var response = api.panelGet("/v1/panel/settings/punishment-types/0");
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void resetPunishmentTypes() throws Exception {
        assumeFalse(TestServerCredentials.usesExternalServer(), "Resetting the types needs the in-process server");
        var response = api.panelPost("/v1/panel/settings/punishment-types/reset", Map.of());

        JsonHelper.assertStatus(response, 200);
        JsonHelper.assertStatus(api.panelGet("/v1/panel/settings/punishment-types/0"), 200);
    }

    // -- General Settings --

    @Test
    void getGeneralSettings() throws Exception {
        var response = api.panelGet("/v1/panel/settings/general");
        assertEquals(expectedStatusRequiring(SETTINGS_VIEW_PERMISSION), response.statusCode());
    }

    // -- Status Thresholds --

    @Test
    void getStatusThresholds() throws Exception {
        var response = api.panelGet("/v1/panel/settings/status-thresholds");
        JsonHelper.assertStatus(response, 200);
    }

    // -- API Keys --

    @Test
    void checkApiKeyExists() throws Exception {
        var response = api.panelGet("/v1/panel/settings/api-keys/panel/exists");
        assertEquals(expectedStatusForSuperAdminOnly(), response.statusCode());
    }

    @Test
    void revealApiKey() throws Exception {
        var response = api.panelGet("/v1/panel/settings/api-keys/panel/reveal");
        assertEquals(expectedStatusForSuperAdminOnly(), response.statusCode());
    }

    @Test
    void generateApiKey() throws Exception {
        assumeFalse(TestServerCredentials.usesExternalServer(), "Rotating the key needs the in-process server");
        var response = api.panelPost("/v1/panel/settings/api-keys/panel/generate", Map.of());

        JsonHelper.assertStatus(response, 409);
        assertEquals("CONFLICT", JsonHelper.parseObject(response.body()).get("code").getAsString());
    }

    // -- AI Moderation --

    @Test
    void getAiModerationSettings() throws Exception {
        var response = api.panelGet("/v1/panel/settings/ai-moderation");
        JsonHelper.assertStatus(response, 200);
    }

    // -- Webhooks --

    @Test
    void getWebhookSettings() throws Exception {
        var response = api.panelGet("/v1/panel/settings/webhooks");
        JsonHelper.assertStatus(response, 200);
    }

    // -- Ticket Forms --

    @Test
    void getTicketForms() throws Exception {
        var response = api.panelGet("/v1/panel/settings/ticket-forms");
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void getTicketFormByType() throws Exception {
        var response = api.panelGet("/v1/panel/settings/ticket-forms/bug_report");
        // Ticket form type may not exist on staging
        assertEquals(404, response.statusCode());
    }

    @Test
    void getAiStatus() throws Exception {
        var response = api.panelGet("/v1/panel/settings/ai/status");
        JsonHelper.assertStatus(response, 200);
    }

    // -- Quick Responses --

    @Test
    void getQuickResponses() throws Exception {
        var response = api.panelGet("/v1/panel/settings/quick-responses");
        JsonHelper.assertStatus(response, 200);
    }

    private static int expectedStatusRequiring(String permission) throws Exception {
        var permissions = JsonHelper.parseArrayField(api.panelGet("/v1/panel/auth/permissions").body(), "permissions");
        return permissions.contains(new JsonPrimitive(permission)) ? 200 : 403;
    }

    private static int expectedStatusForSuperAdminOnly() throws Exception {
        var profile = JsonHelper.parseObject(api.panelGet("/v1/panel/auth/me").body());
        return SUPER_ADMIN_ROLE.equals(profile.get("role").getAsString()) ? 200 : 403;
    }
}
