package gg.modl.backend.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.google.gson.JsonObject;
import gg.modl.backend.support.ApiClient;
import gg.modl.backend.support.HttpIntegrationTest;
import gg.modl.backend.support.InProcessServer;
import gg.modl.backend.support.JsonHelper;
import gg.modl.backend.support.TestServerCredentials;
import java.io.BufferedWriter;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@HttpIntegrationTest
class MinecraftMigrationApiTest {
    private static final String JOBS = "/v1/minecraft/migration/jobs";
    private static final Duration IMPORT_WAIT = Duration.ofSeconds(30);
    private static final Duration IMPORT_POLL = Duration.ofMillis(250);
    private static final long MEBIBYTE = 1024L * 1024;
    private static final long DEFAULT_LIMIT = 500 * MEBIBYTE;
    private static final int BEYOND_THE_MULTIPART_LIMIT_MB = 120;
    private static final String EXPORT = """
        {"players":[{"minecraftUuid":"7125ba8b-1c86-4508-b92b-b5c042ccfe2b","usernames":[{"username":"Imported"}]}]}""";

    static ApiClient api;

    @TempDir
    Path directory;

    private String jobId;

    @BeforeAll
    static void setUp() {
        api = new ApiClient();
    }

    @BeforeEach
    void startImport() throws Exception {
        assumeFalse(TestServerCredentials.usesExternalServer(), "An active import needs the in-process server");
        InProcessServer.get().forgetImportJobs();
        var response = api.minecraftPost(JOBS, startRequest());
        JsonHelper.assertStatus(response, 201);
        JsonObject body = JsonHelper.parseObject(response.body());
        assertEquals(DEFAULT_LIMIT, body.get("maxUploadBytes").getAsLong());
        JsonObject job = body.getAsJsonObject("job");
        assertEquals("building_json", job.get("status").getAsString());
        assertEquals("API test", job.get("startedFrom").getAsString());
        assertEquals("SCOPED_PUNISHMENT_CHOICE_SKIP", job.get("scopedPunishments").getAsString());
        assertEquals("IMPORT_END_REASON_UNSPECIFIED", job.get("endReason").getAsString());
        jobId = job.get("id").getAsString();
    }

    @Test
    void anExportIsAcceptedOnceAndImported() throws Exception {
        assertCode(api.minecraftPutFile(filePath(), BodyPublishers.ofString(EXPORT)).body(), "IMPORT_FILE_NOT_ACCEPTED");
        finishExport();

        JsonHelper.assertStatus(api.minecraftPutFile(filePath(), BodyPublishers.ofString(EXPORT)), 202);
        JsonHelper.assertStatus(api.minecraftPutFile(filePath(), BodyPublishers.ofString(EXPORT)), 409);

        awaitImportStatus("completed");
        JsonObject latest = JsonHelper.parseObject(api.minecraftGet(JOBS + "/latest").body());
        assertEquals(jobId, latest.getAsJsonObject("job").get("id").getAsString());
        assertEquals(1, latest.getAsJsonObject("job").get("recordsProcessed").getAsInt());
        assertEquals(true, latest.get("setupReady").getAsBoolean());
        assertEquals(365, latest.get("ipHistoryDays").getAsInt());
    }

    @Test
    void aFileAboveTheMultipartLimitIsAccepted() throws Exception {
        finishExport();
        Path export = directory.resolve("export.json");
        String padding = "x".repeat((int) MEBIBYTE / 2);
        try (BufferedWriter writer = Files.newBufferedWriter(export)) {
            writer.write("{\"padding\":[");
            for (int chunk = 0; chunk < BEYOND_THE_MULTIPART_LIMIT_MB * 2; chunk++) {
                writer.write((chunk == 0 ? "\"" : ",\"") + padding + "\"");
            }
            writer.write("]," + EXPORT.substring(1));
        }

        JsonHelper.assertStatus(api.minecraftPutFile(filePath(), BodyPublishers.ofFile(export)), 202);

        awaitImportStatus("completed");
    }

    @Test
    void aFileDeclaredAboveTheLimitIsRefusedUnread() throws Exception {
        finishExport();

        assertEquals(413, api.minecraftDeclareFile(filePath(), DEFAULT_LIMIT + MEBIBYTE));
        assertEquals("IMPORT_END_REASON_FILE_TOO_LARGE", currentJob().get("endReason").getAsString());
    }

    @Test
    void aSecondStartIsRefusedWhileOneRuns() throws Exception {
        var response = api.minecraftPost(JOBS, startRequest());

        JsonHelper.assertStatus(response, 409);
        assertCode(response.body(), "IMPORT_ACTIVE");
    }

    @Test
    void aCancelEndsTheJobAndAnotherMayStartAtOnce() throws Exception {
        var ended = api.minecraftPost(JOBS + "/" + jobId + "/end", Map.of("reason", "IMPORT_END_REASON_CANCELLED"));

        JsonHelper.assertStatus(ended, 200);
        assertEquals("IMPORT_END_REASON_CANCELLED",
            JsonHelper.parseObject(ended.body()).getAsJsonObject("job").get("endReason").getAsString());
        JsonHelper.assertStatus(api.minecraftPost(JOBS, startRequest()), 201);
    }

    @Test
    void aStartWithoutBothChoicesAndAReasonOfTheBackendAreInvalid() throws Exception {
        JsonHelper.assertStatus(api.minecraftPost(JOBS, Map.of("type", "litebans", "serverName", "API test")), 400);
        JsonHelper.assertStatus(api.minecraftPost(JOBS + "/" + jobId + "/end",
            Map.of("reason", "IMPORT_END_REASON_IMPORT_FAILED")), 400);
    }

    @Test
    void anUnknownJobIsNotFound() throws Exception {
        JsonHelper.assertStatus(api.minecraftGet(JOBS + "/missing"), 404);
        JsonHelper.assertStatus(api.minecraftPutFile(JOBS + "/missing/file", BodyPublishers.ofString(EXPORT)), 404);
    }

    @Test
    void theRoutesNeedTheApiKey() throws Exception {
        JsonHelper.assertStatus(api.publicGet(JOBS + "/latest"), 401);
    }

    private static Map<String, String> startRequest() {
        return Map.of(
            "type", "litebans",
            "serverName", "API test",
            "scopedPunishments", "SCOPED_PUNISHMENT_CHOICE_SKIP",
            "ipHistory", "IMPORTED_IP_HISTORY_CHOICE_STANDARD");
    }

    private void finishExport() throws Exception {
        var response = api.minecraftPost(JOBS + "/" + jobId + "/progress",
            Map.of("phase", "EXPORT_PHASE_UPLOADING", "recordsProcessed", 1, "totalRecords", 1));
        JsonHelper.assertStatus(response, 200);
        assertEquals("uploading_json",
            JsonHelper.parseObject(response.body()).getAsJsonObject("job").get("status").getAsString());
    }

    private String filePath() {
        return JOBS + "/" + jobId + "/file";
    }

    private static void assertCode(String body, String code) {
        assertEquals(code, JsonHelper.parseObject(body).get("code").getAsString());
    }

    private JsonObject currentJob() throws Exception {
        return JsonHelper.parseObject(api.minecraftGet(JOBS + "/" + jobId).body()).getAsJsonObject("job");
    }

    private void awaitImportStatus(String expected) throws Exception {
        long deadline = System.nanoTime() + IMPORT_WAIT.toNanos();
        String status = "";
        while (System.nanoTime() < deadline) {
            status = currentJob().get("status").getAsString();
            if (expected.equals(status)) {
                return;
            }
            Thread.sleep(IMPORT_POLL);
        }
        assertEquals(expected, status);
    }
}
