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
class MinecraftReportApiTest {

    static ApiClient api;

    private static final String TEST_UUID = "069a79f4-44e9-4726-a5be-fca90e38aaf5";

    @BeforeAll
    static void setUp() {
        api = new ApiClient();
    }

    @Test
    void listOpenReports() throws Exception {
        var response = api.minecraftGet("/v1/minecraft/reports?status=open&limit=10");
        JsonHelper.assertStatus(response, 200);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("reports"));
    }

    @Test
    void listPlayerReports() throws Exception {
        var response = api.minecraftGet("/v1/minecraft/reports/player/" + TEST_UUID + "?status=all&limit=5");
        JsonHelper.assertStatus(response, 200);
        var json = JsonHelper.parseObject(response.body());
        assertTrue(json.has("reports"));
    }

    @Test
    void dismissReport() throws Exception {
        var listResponse = api.minecraftGet("/v1/minecraft/reports?status=open&limit=1");
        var json = JsonHelper.parseObject(listResponse.body());
        var reports = json.getAsJsonArray("reports");
        assertFalse(reports.isEmpty(), "The fixture must provide reports");

        String reportId = reports.get(0).getAsJsonObject().get("id").getAsString();

        var response = api.minecraftPost("/v1/minecraft/reports/" + reportId + "/dismiss", Map.of(
            "console", true,
            "dismissedBy", "TestBot",
            "reason", "API test - dismissed for testing"
        ));
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void assignReport() throws Exception {
        var listResponse = api.minecraftGet("/v1/minecraft/reports?status=open&limit=1");
        var json = JsonHelper.parseObject(listResponse.body());
        var reports = json.getAsJsonArray("reports");
        assertFalse(reports.isEmpty(), "The fixture must provide reports");

        String reportId = reports.get(0).getAsJsonObject().get("id").getAsString();

        var response = api.minecraftPost("/v1/minecraft/reports/" + reportId + "/assign", Map.of(
            "assignee", "TestBot"
        ));
        JsonHelper.assertStatus(response, 200);
    }

    @Test
    void resolveReport() throws Exception {
        var listResponse = api.minecraftGet("/v1/minecraft/reports?status=open&limit=1");
        var json = JsonHelper.parseObject(listResponse.body());
        var reports = json.getAsJsonArray("reports");
        assertFalse(reports.isEmpty(), "The fixture must provide reports");

        String reportId = reports.get(0).getAsJsonObject().get("id").getAsString();

        var response = api.minecraftPost("/v1/minecraft/reports/" + reportId + "/resolve", Map.of(
            "resolvedBy", "TestBot",
            "resolution", "API test - resolved for testing",
            "punishmentId", "test-punishment-id",
            "console", true
        ));
        JsonHelper.assertStatus(response, 200);
    }
}
