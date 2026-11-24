package gg.modl.backend.instance.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.infrastructure.web.HttpOrigin;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PublicUrlProbeTest {
    private static final String PROBE_PREFIX = "/v1/public/setup/probe/";

    private final PublicUrlProbe probe = new PublicUrlProbe(new IdGenerator(), Clock.systemUTC());
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void anOriginThatAnswersItsOwnProbeIsReachable() throws IOException {
        int port = serve(nonce -> probe.recognizes(nonce) ? 204 : 404);

        PublicUrlCheck check = probe.check("HTTP://127.0.0.1:" + port + "/");

        assertThat(check.verdict()).isEqualTo(PublicUrlVerdict.REACHABLE);
        assertThat(check.normalizedUrl()).isEqualTo("http://127.0.0.1:" + port);
    }

    @Test
    void anotherServiceAtTheOriginIsReportedAsSuch() throws IOException {
        int port = serve(nonce -> 200);

        assertThat(probe.check("http://127.0.0.1:" + port).verdict()).isEqualTo(PublicUrlVerdict.OTHER_SERVICE);
    }

    @Test
    void redirectsAreNeverFollowed() throws IOException {
        int port = serve(nonce -> 302);

        assertThat(probe.check("http://127.0.0.1:" + port).verdict()).isEqualTo(PublicUrlVerdict.OTHER_SERVICE);
    }

    @Test
    void aClosedPortIsUnreachable() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }

        assertThat(probe.check("http://127.0.0.1:" + port).verdict()).isEqualTo(PublicUrlVerdict.UNREACHABLE);
    }

    @Test
    void anythingButAnHttpOriginIsInvalidAndNeverContacted() {
        assertThat(probe.check("ftp://panel.example.com").verdict()).isEqualTo(PublicUrlVerdict.INVALID);
        assertThat(probe.check("https://panel.example.com/admin").verdict()).isEqualTo(PublicUrlVerdict.INVALID);
        assertThat(probe.check("https://user:pass@panel.example.com").verdict()).isEqualTo(PublicUrlVerdict.INVALID);
    }

    @Test
    void aNonceIsOnlyRecognisedWhileItsProbeIsRunning() throws IOException {
        AtomicReference<String> seen = new AtomicReference<>();
        int port = serve(nonce -> {
            seen.set(nonce);
            return 204;
        });

        probe.check("http://127.0.0.1:" + port);

        assertThat(seen.get()).isNotBlank();
        assertThat(probe.recognizes(seen.get())).isFalse();
    }

    @Test
    void aLocalhostPanelThatListensOnlyOnTheIpv6LoopbackIsReachable() throws IOException {
        byte[] ipv6LoopbackBytes = new byte[16];
        ipv6LoopbackBytes[15] = 1;
        InetAddress ipv6Loopback = InetAddress.getByAddress(ipv6LoopbackBytes);
        assumeTrue(canBind(ipv6Loopback));
        int port = serve(ipv6Loopback, nonce -> probe.recognizes(nonce) ? 204 : 404);

        assertThat(probe.check("http://localhost:" + port).verdict()).isEqualTo(PublicUrlVerdict.REACHABLE);
    }

    @Test
    void everyLoopbackAddressIsTriedOnlyForALoopbackName() {
        HttpOrigin localhost = HttpOrigin.requireExact("http://localhost:3000");
        HttpOrigin publicPanel = HttpOrigin.requireExact("https://panel.example.com");

        assertThat(PublicUrlProbe.addressesOf(localhost)).extracting(HttpOrigin::value)
            .containsExactly("http://localhost:3000", "http://127.0.0.1:3000", "http://[::1]:3000");
        assertThat(PublicUrlProbe.addressesOf(publicPanel)).containsExactly(publicPanel);
    }

    private static boolean canBind(InetAddress address) {
        try (ServerSocket socket = new ServerSocket(0, 1, address)) {
            return socket.isBound();
        } catch (IOException e) {
            return false;
        }
    }

    private int serve(StatusForNonce status) throws IOException {
        return serve(InetAddress.getLoopbackAddress(), status);
    }

    private int serve(InetAddress address, StatusForNonce status) throws IOException {
        server = HttpServer.create(new InetSocketAddress(address, 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String nonce = path.startsWith(PROBE_PREFIX) ? path.substring(PROBE_PREFIX.length()) : "";
            int code = status.forNonce(nonce);
            if (code == 302) {
                exchange.getResponseHeaders().add("Location", "http://127.0.0.1:1/elsewhere");
            }
            exchange.sendResponseHeaders(code, -1);
            exchange.close();
        });
        server.start();
        return server.getAddress().getPort();
    }

    private interface StatusForNonce {
        int forNonce(String nonce);
    }
}
