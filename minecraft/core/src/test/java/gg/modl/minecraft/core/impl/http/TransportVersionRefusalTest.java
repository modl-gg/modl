package gg.modl.minecraft.core.impl.http;

import com.sun.net.httpserver.HttpServer;
import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.core.boot.VersionMismatchFailure;
import gg.modl.minecraft.core.boot.VersionMismatchReports;
import gg.modl.minecraft.core.connection.BackendConnections;
import gg.modl.minecraft.core.connection.BackendEndpoint;
import gg.modl.minecraft.core.support.TestConnections;
import gg.modl.update.CompatibilityHeaders;
import gg.modl.update.VersionCompatibility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransportVersionRefusalTest {
    private static final UUID PLAYER = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final long NO_BODY = -1;

    private final VersionMismatchReports mismatches = new VersionMismatchReports();
    private final List<VersionMismatchFailure> reported = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private BackendConnections connections;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add(CompatibilityHeaders.COMPATIBILITY, VersionCompatibility.SERVER_OLDER.name());
            exchange.getResponseHeaders().add(CompatibilityHeaders.SERVER_VERSION, "1.0.0");
            exchange.sendResponseHeaders(CompatibilityHeaders.REFUSAL_STATUS, NO_BODY);
            exchange.close();
        });
        server.start();
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        connections = TestConnections.reportingTo(BackendEndpoint.of(origin, "api-key"), mismatches);
        mismatches.listen(reported::add);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void theJsonClientReportsARefusalToItsLifecycle() {
        assertRefusalReported(new ModlHttpClientV2Impl(connections, false));
    }

    @Test
    void theProtobufClientReportsARefusalToItsLifecycle() {
        assertRefusalReported(new ModlHttpClientV3Impl(connections, false));
    }

    private void assertRefusalReported(ModlHttpClient client) {
        try {
            assertThrows(ExecutionException.class, () -> client.getPlayerStanding(PLAYER).get(5, TimeUnit.SECONDS));

            assertEquals(1, reported.size());
            assertEquals(VersionCompatibility.SERVER_OLDER, reported.get(0).compatibility());
            assertEquals("1.0.0", reported.get(0).serverVersion());
        } finally {
            client.shutdown();
        }
    }
}
