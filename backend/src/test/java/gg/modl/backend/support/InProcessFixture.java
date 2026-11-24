package gg.modl.backend.support;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

final class InProcessFixture {
    private static final int MANUAL_MUTE = 1;
    private static final int OPEN_REPORTS = 4;
    private static final Player NOTCH = new Player("069a79f4-44e9-4726-a5be-fca90e38aaf5", "Notch", "198.51.100.10",
        "notch-skin");
    private static final Player JEB = new Player("853c80ef-3c37-49fd-aa49-938b674adae6", "jeb_", "198.51.100.11",
        "jeb-skin");
    private static final Player DINNERBONE = new Player("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6", "Dinnerbone", "198.51.100.12",
        "dinnerbone-skin");

    private final ApiClient api;

    InProcessFixture(ApiClient api) {
        this.api = api;
    }

    void seed() throws IOException, InterruptedException {
        requireSuccess(api.panelPut("/v1/panel/setup/steps/account", Map.of("status", "SETUP_STEP_STATUS_DONE")));
        for (Player player : List.of(NOTCH, JEB, DINNERBONE)) {
            requireSuccess(api.minecraftPost("/v1/minecraft/players/login", Map.of(
                "minecraftUUID", player.uuid(), "username", player.name(), "ip", player.ip(), "serverName", "lobby",
                "skinHash", player.skinHash())));
        }
        requireSuccess(api.minecraftPost("/v1/minecraft/punishments/dynamic", Map.of(
            "targetUuid", DINNERBONE.uuid(), "issuerName", "Console", "console", true, "type_ordinal", MANUAL_MUTE, "reason", "Fixture mute")));
        requireSuccess(api.panelPost("/v1/panel/staff", Map.of(
            "email", "helper@example.com", "username", "helper", "role", "Moderator")));
        for (int report = 1; report <= OPEN_REPORTS; report++) {
            requireSuccess(api.minecraftPost("/v1/minecraft/tickets", Map.of(
                "type", "player",
                "subject", "Fixture report " + report,
                "creatorUuid", JEB.uuid(),
                "creatorName", JEB.name(),
                "reportedPlayerUuid", DINNERBONE.uuid(),
                "reportedPlayerName", DINNERBONE.name())));
        }
    }

    private static void requireSuccess(HttpResponse<String> response) {
        assertTrue(response.statusCode() == 200 || response.statusCode() == 201,
            () -> response.uri() + " answered " + response.statusCode() + ": " + response.body());
    }

    private record Player(String uuid, String name, String ip, String skinHash) {
    }
}
