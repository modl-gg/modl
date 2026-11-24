package gg.modl.minecraft.core.impl.http;

import com.sun.net.httpserver.HttpServer;
import gg.modl.minecraft.api.http.PanelUnavailableException;
import gg.modl.minecraft.api.http.request.PlayerLoginRequest;
import gg.modl.minecraft.core.support.TestConnections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LoginBusyRetryTest {
    private static final long WAIT_SECONDS = 10;
    private static final Duration MAX_WAIT = Duration.ofSeconds(2);

    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;
    private ModlHttpClientV2Impl client;
    private int busyResponses;
    private String retryAfter = "0";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            boolean busy = requests.incrementAndGet() <= busyResponses;
            if (busy && retryAfter != null) exchange.getResponseHeaders().add("Retry-After", retryAfter);
            byte[] body = (busy ? "{}" : "{\"status\":200}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(busy ? 503 : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        client = new ModlHttpClientV2Impl(
                TestConnections.to("http://127.0.0.1:" + server.getAddress().getPort(), "api-key"), false);
    }

    @AfterEach
    void stopServer() {
        client.shutdown();
        server.stop(0);
    }

    @Test
    void aBusyLoginIsRetriedOnce() throws Exception {
        busyResponses = 1;

        assertNotNull(client.playerLogin(login()).get(WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(2, requests.get());
    }

    @Test
    void aSecondBusyAnswerFailsTheLogin() {
        busyResponses = 2;

        assertLoginUnavailable();
        assertEquals(2, requests.get());
    }

    @Test
    void anUnavailableAnswerWithoutRetryAfterIsNotRetried() {
        busyResponses = 1;
        retryAfter = null;

        assertLoginUnavailable();
        assertEquals(1, requests.get());
    }

    @Test
    void theWaitIsCappedAndOnlyAppliesToBusyAnswers() {
        BusyRetry retry = BusyRetry.onceWaitingAtMost(MAX_WAIT);

        assertEquals(Optional.of(Duration.ofSeconds(1)), retry.delayBeforeRetry(503, "1"));
        assertEquals(Optional.of(MAX_WAIT), retry.delayBeforeRetry(503, "120"));
        assertEquals(Optional.empty(), retry.delayBeforeRetry(429, "1"));
        assertEquals(Optional.empty(), retry.delayBeforeRetry(503, "soon"));
        assertEquals(Optional.empty(), BusyRetry.NEVER.delayBeforeRetry(503, "1"));
    }

    private void assertLoginUnavailable() {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> client.playerLogin(login()).get(WAIT_SECONDS, TimeUnit.SECONDS));
        assertInstanceOf(PanelUnavailableException.class, failure.getCause());
    }

    private static PlayerLoginRequest login() {
        return new PlayerLoginRequest("123e4567-e89b-12d3-a456-426614174000", "modltester", "127.0.0.1", null, "hub",
                "hub-1");
    }
}
