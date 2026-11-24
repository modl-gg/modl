package gg.modl.backend.ai.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.ai.model.AiDeadline;
import gg.modl.backend.ai.model.AiFailureKind;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class AiHttpTransportTest {
    private static final Duration GENEROUS = Duration.ofSeconds(20);

    @Test
    void redirectsAreReportedAndNeverFollowed() throws Exception {
        try (LocalAiServer server = LocalAiServer.start()) {
            server.on("/redirect", exchange -> {
                exchange.getResponseHeaders().set("Location", "/target");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            }).json("/target", 200, "{}");

            AiHttpResponse response = transport().exchange(get(server, "/redirect", 1024), AiDeadline.after(GENEROUS));

            assertThat(response.status()).isEqualTo(302);
            assertThat(server.requests()).extracting(LocalAiServer.Recorded::uri).extracting(URI::getPath).containsExactly("/redirect");
        }
    }

    @Test
    void failuresAreNotRetried() throws Exception {
        try (LocalAiServer server = LocalAiServer.start()) {
            server.json("/fail", 503, "{}");

            assertThat(transport().exchange(get(server, "/fail", 1024), AiDeadline.after(GENEROUS)).status()).isEqualTo(503);
            assertThat(server.requests()).hasSize(1);
        }
    }

    @Test
    void theLimitAppliesAfterDecompression() throws Exception {
        byte[] compressed = gzip("{\"padding\":\"" + "a".repeat(64 * 1024) + "\"}");
        try (LocalAiServer server = LocalAiServer.start()) {
            server.on("/big", exchange -> {
                exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                exchange.sendResponseHeaders(200, compressed.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(compressed);
                }
            });

            assertThat(compressed.length).isLessThan(4096);
            assertThatThrownBy(() -> transport().exchange(get(server, "/big", 4096), AiDeadline.after(GENEROUS)))
                .isInstanceOfSatisfying(AiTransportException.class,
                    failure -> assertThat(failure.kind()).isEqualTo(AiFailureKind.RESPONSE_TOO_LARGE));
        }
    }

    @Test
    void errorBodiesAreCappedSeparately() throws Exception {
        try (LocalAiServer server = LocalAiServer.start()) {
            server.json("/error", 400, "{\"error\":\"" + "e".repeat(AiHttpTransport.ERROR_BODY_LIMIT) + "\"}");

            assertThatThrownBy(() -> transport().exchange(get(server, "/error", 1024 * 1024), AiDeadline.after(GENEROUS)))
                .isInstanceOfSatisfying(AiTransportException.class,
                    failure -> assertThat(failure.kind()).isEqualTo(AiFailureKind.RESPONSE_TOO_LARGE));
        }
    }

    @Test
    void theDeadlineCutsATrickledBody() throws Exception {
        try (LocalAiServer server = LocalAiServer.start()) {
            server.on("/slow", exchange -> {
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream output = exchange.getResponseBody()) {
                    for (int beat = 0; beat < 200; beat++) {
                        output.write(' ');
                        output.flush();
                        Thread.sleep(100);
                    }
                } catch (InterruptedException | IOException stopped) {
                    Thread.currentThread().interrupt();
                }
            });
            long started = System.nanoTime();

            assertThatThrownBy(() -> transport().exchange(get(server, "/slow", 1024 * 1024), AiDeadline.after(Duration.ofSeconds(1))))
                .isInstanceOfSatisfying(AiTransportException.class, failure -> assertThat(failure.kind()).isEqualTo(AiFailureKind.TIMEOUT));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        }
    }

    @Test
    void theResolverIsConsultedAndItsAnswerIsUsed() throws Exception {
        List<String> lookups = new CopyOnWriteArrayList<>();
        try (LocalAiServer server = LocalAiServer.start()) {
            server.json("/ok", 200, "{\"ok\":true}");
            AiHttpTransport transport = new AiHttpTransport(new AiAddressPolicy(), host -> {
                lookups.add(host);
                return new InetAddress[] {InetAddress.getLoopbackAddress()};
            });
            URI named = URI.create("http://ai.test:" + server.baseUrl().getPort() + "/ok");

            AiHttpResponse response = transport.exchange(AiHttpRequest.get(named, Map.of(), 1024), AiDeadline.after(GENEROUS));

            assertThat(response.status()).isEqualTo(200);
            assertThat(lookups).contains("ai.test");
        }
    }

    @Test
    void aHostThatResolvesToABlockedAddressIsRefused() {
        AiHttpTransport transport = new AiHttpTransport(new AiAddressPolicy(),
            host -> new InetAddress[] {InetAddress.getByName("93.184.215.14"), InetAddress.getByName("169.254.169.254")});

        assertThatThrownBy(() -> transport.exchange(AiHttpRequest.get(URI.create("https://rebind.test/v1/models"), Map.of(), 1024),
            AiDeadline.after(GENEROUS)))
            .isInstanceOfSatisfying(AiTransportException.class,
                failure -> assertThat(failure.kind()).isEqualTo(AiFailureKind.BLOCKED_ADDRESS));
    }

    @Test
    void plainHttpToAPublicAnswerIsRefused() {
        AiHttpTransport transport = new AiHttpTransport(new AiAddressPolicy(),
            host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")});

        assertThatThrownBy(() -> transport.exchange(AiHttpRequest.get(URI.create("http://public.test/v1"), Map.of(), 1024),
            AiDeadline.after(GENEROUS)))
            .isInstanceOfSatisfying(AiTransportException.class,
                failure -> assertThat(failure.kind()).isEqualTo(AiFailureKind.BLOCKED_ADDRESS));
    }

    private static AiHttpTransport transport() {
        return new AiHttpTransport(new AiAddressPolicy());
    }

    private static AiHttpRequest get(LocalAiServer server, String path, int limit) {
        return AiHttpRequest.get(URI.create(server.baseUrl() + path), Map.of(), limit);
    }

    private static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }
}
