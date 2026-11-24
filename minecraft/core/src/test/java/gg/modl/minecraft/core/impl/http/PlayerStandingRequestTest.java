package gg.modl.minecraft.core.impl.http;

import com.sun.net.httpserver.HttpServer;
import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.api.http.response.PlayerStandingResponse;
import gg.modl.minecraft.api.http.response.PlayerStandingResponse.State;
import gg.modl.minecraft.core.plugin.PluginInfo;
import gg.modl.minecraft.core.support.TestConnections;
import gg.modl.update.CompatibilityHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerStandingRequestTest {
    private static final UUID PLAYER = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final String BODY = "{\"socialStatus\":\"Low\",\"gameplayStatus\":\"Medium\",\"socialPoints\":1,"
            + "\"gameplayPoints\":6,\"punishments\":[{\"id\":\"ABCD1234\",\"type\":\"Cheating\",\"category\":\"BAN\","
            + "\"issuedAt\":1767225600000,\"durationMs\":null,\"state\":\"ACTIVE\",\"endedAt\":null,\"expiresAt\":null,"
            + "\"reason\":\"Follow the rules\"}]}";

    private final AtomicReference<String> requested = new AtomicReference<>();
    private final AtomicReference<String> apiKey = new AtomicReference<>();
    private final AtomicReference<String> pluginVersion = new AtomicReference<>();
    private HttpServer server;
    private String origin;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requested.set(exchange.getRequestURI().toString());
            apiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            pluginVersion.set(exchange.getRequestHeaders().getFirst(CompatibilityHeaders.PLUGIN_VERSION));
            byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void theJsonClientReadsThePlayerSafeRoute() throws Exception {
        assertReadsStanding(new ModlHttpClientV2Impl(TestConnections.to(origin, "api-key"), false));
    }

    @Test
    void theProtobufClientUsesTheSameJsonRoute() throws Exception {
        assertReadsStanding(new ModlHttpClientV3Impl(TestConnections.to(origin, "api-key"), false));
    }

    private void assertReadsStanding(ModlHttpClient client) throws Exception {
        try {
            PlayerStandingResponse standing = client.getPlayerStanding(PLAYER).get(5, TimeUnit.SECONDS);

            assertEquals("/v1/minecraft/players/" + PLAYER + "/standing", requested.get());
            assertEquals("api-key", apiKey.get());
            assertEquals(PluginInfo.VERSION, pluginVersion.get());
            assertEquals("Medium", standing.getGameplayStatus());
            assertEquals(State.ACTIVE, standing.getPunishments().get(0).getState());
            assertEquals("Follow the rules", standing.getPunishments().get(0).getReason());
        } finally {
            client.shutdown();
        }
    }
}
