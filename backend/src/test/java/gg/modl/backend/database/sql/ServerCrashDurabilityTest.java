package gg.modl.backend.database.sql;

import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENTS;
import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.database.jooq.tables.records.PunishmentsRecord;
import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.backend.support.sql.TestDialects;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class ServerCrashDurabilityTest {
    private static final String API_KEY = "crash-durability-test-api-key-0123456789abcdef";
    private static final String PLAYER_UUID = "11111111-2222-3333-4444-555555555555";
    private static final String REASON = "acknowledged right before the kill";
    private static final int MANUAL_BAN = 2;

    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();

    @TempDir
    Path directory;

    @Test
    void aPunishmentIsThereAfterTheServerIsKilledRightAfterItsResponse() throws Exception {
        String punishmentId;
        try (ChildJvm server = startServer()) {
            String address = addressOf(server);
            HttpResponse<String> login = post(address + "/v1/minecraft/players/login",
                Map.of("minecraftUUID", PLAYER_UUID, "username", "Victim", "ip", "127.0.0.1"));
            assertThat(login.statusCode()).as(login.body()).isEqualTo(201);
            HttpResponse<String> created = post(address + "/v1/minecraft/punishments/dynamic",
                Map.of("targetUuid", PLAYER_UUID, "issuerName", "Console", "console", true, "type_ordinal", MANUAL_BAN, "reason", REASON));
            server.kill();
            assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
            punishmentId = json.readTree(created.body()).get("punishmentId").asString();
        }

        try (ChildJvm restarted = startServer()) {
            HttpResponse<String> read = http.send(request(addressOf(restarted) + "/v1/minecraft/punishments/" + punishmentId)
                .GET()
                .build(), BodyHandlers.ofString());
            assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
            assertThat(read.body()).contains(punishmentId, REASON);
        }

        try (SqlTestDatabase database = SqlTestDatabase.start(TestDialects.SQLITE, directory)) {
            PunishmentsRecord stored = database.dsl().selectFrom(PUNISHMENTS).where(PUNISHMENTS.ID.eq(punishmentId)).fetchSingle();
            assertThat(stored.getReason()).isEqualTo(REASON);
            assertThat(stored.getTypeOrdinal()).isEqualTo(MANUAL_BAN);
            assertThat(database.dsl().select(PLAYERS.MINECRAFT_UUID).from(PLAYERS).where(PLAYERS.ID.eq(stored.getPlayerId()))
                .fetchSingle().value1()).isEqualTo(PLAYER_UUID);
        }
        CrashedDatabase.assertSound(directory);
    }

    private ChildJvm startServer() {
        return ChildJvm.start(CrashingServerProcess.class, directory.toString(), API_KEY);
    }

    private static String addressOf(ChildJvm server) throws InterruptedException {
        String line = server.awaitLineStartingWith(CrashingServerProcess.LISTENING);
        return "http://127.0.0.1:" + line.substring(CrashingServerProcess.LISTENING.length()).trim();
    }

    private HttpResponse<String> post(String url, Map<String, Object> body) throws IOException, InterruptedException {
        return http.send(request(url).POST(BodyPublishers.ofString(json.writeValueAsString(body))).build(), BodyHandlers.ofString());
    }

    private static HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).header("X-API-Key", API_KEY).header("Content-Type", "application/json")
            .header("Accept", "application/json");
    }
}
