package gg.modl.backend.public_api;

import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.HttpIntegrationTest;
import gg.modl.backend.support.JsonHelper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@HttpIntegrationTest
class ValidationGuardsApiTest {
    private static final String NOTCH = "069a79f4-44e9-4726-a5be-fca90e38aaf5";

    private static ApiClient api;

    @BeforeAll
    static void setUp() {
        api = new ApiClient();
    }

    @Test
    void aNulInBoundTextIsABadRequest() throws Exception {
        JsonHelper.assertStatus(api.panelPost("/v1/panel/homepage-cards", Map.of("title", "before\u0000after")), 400);
    }

    @Test
    void aNulInALookupMatchesNothing() throws Exception {
        JsonHelper.assertStatus(api.publicGet("/v1/public/knowledgebase/articles/nonexistent-slug"), 404);
        JsonHelper.assertStatus(api.publicPost("/v1/public/staff/invitations/accept", Map.of("token", "bad\u0000token")), 400);
    }

    @Test
    void anOverflowingRoleOrderIsRefused() throws Exception {
        JsonHelper.assertStatus(api.panelPost("/v1/panel/roles/reorder",
            Map.of("roleOrder", List.of(Map.of("id", "helper", "order", Integer.MAX_VALUE)))), 400);
    }

    @Test
    void aNonFiniteNumberInFormDataIsRefused() throws Exception {
        JsonHelper.assertStatus(api.panelPost("/v1/panel/tickets",
            "{\"type\":\"bug_report\",\"subject\":\"Non-finite\",\"formData\":{\"size\":1e400}}"), 400);
    }

    @Test
    void anAddressThatIsNoIpLiteralIsRefused() throws Exception {
        JsonHelper.assertStatus(api.panelPost("/v1/panel/players/" + NOTCH + "/ips", Map.of("ipAddress", "hello")), 400);
    }

    @Test
    void aMissingSettingsBodyNeverClearsTheTicketForms() throws Exception {
        JsonHelper.assertStatus(api.panelPatch("/v1/panel/settings/ticket-forms", Map.of("expectedVersion", 0)), 400);
    }

    @Test
    void aMissingThresholdCategoryIsRefused() throws Exception {
        JsonHelper.assertStatus(api.panelPatch("/v1/panel/settings/status-thresholds", Map.of("expectedVersion", 0)), 400);
    }

    @Test
    void aBlankProfileUsernameIsRefused() throws Exception {
        JsonHelper.assertStatus(api.panelPatch("/v1/panel/auth/profile", Map.of("username", " ")), 400);
    }

    @Test
    void anAuditReasonOverTheLimitIsRefused() throws Exception {
        JsonHelper.assertStatus(api.panelPost("/v1/panel/audit/punishments/bulk-pardon",
            Map.of("typeOrdinals", List.of(6), "reason", "r".repeat(501))), 400);
    }

    @Test
    void anUpdatedCategoryNameCannotBeBlank() throws Exception {
        var created = api.panelPost("/v1/panel/knowledgebase/categories", Map.of("name", "Validation guard"));
        JsonHelper.assertSuccess(created);
        String id = JsonHelper.parseObject(created.body()).get("id").getAsString();

        JsonHelper.assertStatus(api.panelPut("/v1/panel/knowledgebase/categories/" + id, Map.of("name", " ")), 400);
        JsonHelper.assertStatus(api.panelDelete("/v1/panel/knowledgebase/categories/" + id), 200);
    }
}
