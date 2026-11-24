package gg.modl.minecraft.core.boot;

import com.sun.net.httpserver.HttpServer;
import gg.modl.minecraft.api.http.request.StartupRequest;
import gg.modl.minecraft.core.plugin.PluginInfo;
import gg.modl.minecraft.core.support.TestConnections;
import gg.modl.update.CompatibilityHeaders;
import gg.modl.update.ReleaseVersion;
import gg.modl.update.VersionCompatibility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupClientTest {
    private static final ReleaseVersion PLUGIN = ReleaseVersion.parse(PluginInfo.VERSION);
    private final AtomicInteger status = new AtomicInteger();
    private final AtomicReference<String> sentPluginVersion = new AtomicReference<>();
    private final AtomicReference<String> sentBootId = new AtomicReference<>();
    private String refusal;
    private String serverVersion;
    private String body;
    private HttpServer backend;

    @BeforeEach
    void startBackend() throws IOException {
        backend = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        backend.createContext("/v2/minecraft/startup", exchange -> {
            sentPluginVersion.set(exchange.getRequestHeaders().getFirst(CompatibilityHeaders.PLUGIN_VERSION));
            sentBootId.set(exchange.getRequestHeaders().getFirst(CompatibilityHeaders.BOOT_ID));
            exchange.getResponseHeaders().add("Location", "https://modl.example.com/v2/minecraft/startup");
            if (refusal != null) exchange.getResponseHeaders().add(CompatibilityHeaders.COMPATIBILITY, refusal);
            if (serverVersion != null) exchange.getResponseHeaders().add(CompatibilityHeaders.SERVER_VERSION, serverVersion);
            byte[] sent = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), sent.length == 0 ? -1 : sent.length);
            if (sent.length > 0) exchange.getResponseBody().write(sent);
            exchange.close();
        });
        backend.start();
    }

    @AfterEach
    void stopBackend() {
        backend.stop(0);
    }

    @Test
    void aRedirectNamesTheAddressToUseInstead() {
        status.set(301);

        IOException failure = assertThrows(IOException.class, () -> client().call());

        assertTrue(failure.getMessage().contains("https://modl.example.com"));
    }

    @Test
    void aRejectedKeyIsReportedAsRejectedCredentials() {
        status.set(401);

        assertInstanceOf(CredentialsRejectedException.class,
                assertThrows(IOException.class, () -> client().call()));
    }

    @Test
    void aRefusalNamesBothVersionsAndTheRemedy() {
        status.set(409);
        refusal = "PLUGIN_OLDER";
        serverVersion = PLUGIN.major() + "." + (PLUGIN.minor() + 1) + ".0";

        VersionMismatchFailure mismatch = assertThrows(VersionMismatchFailure.class, () -> client().call());

        assertEquals(VersionCompatibility.PLUGIN_OLDER, mismatch.compatibility());
        assertTrue(mismatch.reason().contains(serverVersion));
        assertEquals("Update the modl plugin to " + PLUGIN.major() + "." + (PLUGIN.minor() + 1)
                + ".x on this server and restart it.", mismatch.remedy());
    }

    @Test
    void aConflictWithoutTheHeaderIsNotAVersionMismatch() {
        status.set(409);

        assertInstanceOf(HttpStatusException.class, assertThrows(IOException.class, () -> client().call()));
    }

    @Test
    void anOlderServerThatAnswersIsStillAMismatch() {
        status.set(200);
        body = "{\"panelUrl\":\"https://panel.example.test\",\"modlVersion\":\"" + (PLUGIN.major() - 1) + ".9.0\"}";

        assertEquals(VersionCompatibility.SERVER_OLDER,
                assertThrows(VersionMismatchFailure.class, () -> client().call()).compatibility());
    }

    @Test
    void aServerWithoutAVersionIsAMismatch() {
        status.set(200);
        body = "{\"panelUrl\":\"https://panel.example.test\"}";

        assertEquals(VersionCompatibility.UNKNOWN,
                assertThrows(VersionMismatchFailure.class, () -> client().call()).compatibility());
    }

    @Test
    void aDifferentPatchConnectsAndEveryCallNamesThePlugin() throws IOException {
        status.set(200);
        serverVersion = PLUGIN.line() + ".7";
        body = "{\"panelUrl\":\"https://panel.example.test\"}";

        client().call();

        assertNotNull(sentPluginVersion.get());
        assertNotNull(sentBootId.get());
    }

    private StartupClient client() {
        return new StartupClient(TestConnections.to("http://127.0.0.1:" + backend.getAddress().getPort(), "key"),
                new StartupRequest(PluginInfo.VERSION, "SPIGOT", "1.21.11", 20));
    }
}
