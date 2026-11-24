package gg.modl.minecraft.core.impl.http;

import com.sun.net.httpserver.HttpServer;
import gg.modl.minecraft.api.http.PanelUnavailableException;
import gg.modl.minecraft.api.http.request.PlayerLoginRequest;
import gg.modl.minecraft.core.login.LoginBudget;
import gg.modl.minecraft.core.support.TestConnections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LoginDeadlineTest {
    private static final long WAIT_SECONDS = 10;
    private static final long PAST_THE_BUDGET = LoginBudget.DURATION.toNanos() + 1L;
    private static final long ONE_SECOND_LEFT = LoginBudget.DURATION.minusSeconds(1).toNanos();

    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicLong now = new AtomicLong();
    private HttpServer server;
    private ModlHttpClientV2Impl client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            now.set(ONE_SECOND_LEFT);
            exchange.getResponseHeaders().add("Retry-After", "2");
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (client != null) client.shutdown();
        server.stop(0);
    }

    @Test
    void aLoginWhoseDeadlinePassedWhileQueuedIsNeverSent() {
        AtomicBoolean deadlineSet = new AtomicBoolean();
        client = clientWithClock(() -> deadlineSet.getAndSet(true) ? PAST_THE_BUDGET : 0L);

        assertLoginUnavailable();
        assertEquals(0, requests.get());
    }

    @Test
    void aBusyLoginIsNotRetriedWhenTheWaitWouldOutlastTheDeadline() {
        client = clientWithClock(now::get);

        assertLoginUnavailable();
        assertEquals(1, requests.get());
    }

    @Test
    void timeoutsAreCappedByTheRemainingBudgetAndNeverBecomeInfinite() {
        AtomicLong clock = new AtomicLong();
        RequestDeadline deadline = RequestDeadline.after(Duration.ofSeconds(3), clock::get);

        assertEquals(3000, deadline.capMillis(Duration.ofSeconds(10)));
        clock.set(Duration.ofSeconds(2).toNanos());
        assertEquals(1000, deadline.capMillis(Duration.ofSeconds(10)));
        clock.set(Duration.ofSeconds(4).toNanos());
        assertEquals(1, deadline.capMillis(Duration.ofSeconds(10)));
        assertEquals(10_000, RequestDeadline.unbounded().capMillis(Duration.ofSeconds(10)));
    }

    private ModlHttpClientV2Impl clientWithClock(LongSupplier clock) {
        return new ModlHttpClientV2Impl(
                TestConnections.to("http://127.0.0.1:" + server.getAddress().getPort(), "api-key"), false, clock);
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
