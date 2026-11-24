package gg.modl.backend.panel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import gg.modl.backend.ai.http.LocalAiServer;
import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.HttpIntegrationTest;
import gg.modl.backend.support.JsonHelper;
import gg.modl.backend.support.TestServerCredentials;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@HttpIntegrationTest
class PanelAiSettingsApiTest {
    private static final String BASE = "/v1/panel/settings/ai";
    private static final String SECRET = "sk-integration-secret-123";

    static ApiClient api;

    @BeforeAll
    static void setUp() {
        api = new ApiClient();
    }

    @AfterEach
    void removeConnections() throws Exception {
        api.panelPut(BASE, Map.of("activeProvider", ""));
        api.panelDelete(BASE + "/connections/custom");
        api.panelDelete(BASE + "/connections/openai");
    }

    @Test
    void theSettingsListTheCatalogAndNoKey() throws Exception {
        api.panelPut(BASE + "/connections/openai", Map.of("apiKey", SECRET));

        HttpResponse<String> response = api.panelGet(BASE);

        JsonHelper.assertStatus(response, 200);
        assertThat(response.body()).contains("\"anthropic\"", "\"hasApiKey\":true").doesNotContain(SECRET);
        assertThat(response.body()).doesNotContain("apiKey\":\"");
    }

    @Test
    void theStatusIsReadableBySettingsViewers() throws Exception {
        HttpResponse<String> response = api.panelGet(BASE + "/status");

        JsonHelper.assertStatus(response, 200);
        assertThat(response.body()).contains("readiness");
    }

    @Test
    void invalidInputsCarryMachineCodes() throws Exception {
        assertCode(api.panelPut(BASE + "/connections/custom", Map.of("baseUrl", "http://169.254.169.254/v1")), 400, "AI_ADDRESS_BLOCKED");
        assertCode(api.panelPut(BASE + "/connections/custom", Map.of("baseUrl", "http://8.8.8.8/v1")), 400, "AI_BASE_URL_HTTP_PUBLIC");
        assertCode(api.panelPut(BASE + "/connections/openai", Map.of("apiKey", SECRET, "activate", true)), 400, "AI_NOT_READY");
        assertCode(api.panelPut(BASE + "/connections/acme", Map.of()), 400, "AI_PROVIDER_UNKNOWN");
        assertCode(api.panelPut(BASE + "/connections/openai", Map.of("apiKey", "has space")), 400, "AI_KEY_INVALID");
    }

    @Test
    void anUpstreamRejectionIsReportedInTheBodyAndKeepsTheSession() throws Exception {
        assumeFalse(TestServerCredentials.usesExternalServer(), "The stub provider runs beside the in-process server");
        try (LocalAiServer upstream = LocalAiServer.start()) {
            upstream.json("/v1/models", 401, "{\"error\":{\"message\":\"bad key\"}}");
            Map<String, Object> draft = Map.of("provider", "custom", "baseUrl", upstream.baseUrl() + "/v1", "apiKey", SECRET);

            HttpResponse<String> models = api.panelPost(BASE + "/models", Map.of("connection", draft));

            JsonHelper.assertStatus(models, 200);
            assertThat(models.body()).contains("AI_FAILURE_AUTH").doesNotContain("bad key").doesNotContain(SECRET);
            JsonHelper.assertStatus(api.panelGet(BASE), 200);
            assertThat(upstream.requests().getFirst().header("Authorization")).isEqualTo("Bearer " + SECRET);
        }
    }

    @Test
    void aWorkingConnectionCanBeTestedSavedAndActivated() throws Exception {
        assumeFalse(TestServerCredentials.usesExternalServer(), "The stub provider runs beside the in-process server");
        try (LocalAiServer upstream = LocalAiServer.start()) {
            upstream.json("/v1/chat/completions", 200, "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":"
                + "\"{\\\"analysis\\\":\\\"Fine.\\\",\\\"confidence\\\":0.9,\\\"punishmentTypeId\\\":\\\"none\\\",\\\"severity\\\":\\\"none\\\"}\"}}]}");
            String baseUrl = upstream.baseUrl() + "/v1";

            HttpResponse<String> test = api.panelPost(BASE + "/test",
                Map.of("connection", Map.of("provider", "custom", "baseUrl", baseUrl, "model", "stub")));
            HttpResponse<String> saved = api.panelPut(BASE + "/connections/custom", Map.of("baseUrl", baseUrl, "model", "stub", "activate", true));

            JsonHelper.assertStatus(test, 200);
            assertThat(test.body()).contains("\"success\":true", "AI_OUTPUT_MODE_SCHEMA");
            JsonHelper.assertStatus(saved, 200);
            assertThat(saved.body()).contains("AI_READINESS_READY", "\"activeProvider\":\"custom\"");
        }
    }

    private static void assertCode(HttpResponse<String> response, int status, String code) {
        JsonHelper.assertStatus(response, status);
        assertThat(response.body()).contains("\"code\":\"" + code + "\"");
    }
}
