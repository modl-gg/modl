package gg.modl.backend.email;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.email.StartTlsProbe.Support;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class StartTlsProbeTest {
    private static final Duration STEP = Duration.ofMillis(500);

    private final StartTlsProbe probe = new StartTlsProbe(STEP);
    private final List<String> received = new CopyOnWriteArrayList<>();
    private static final String CRLF = "\r\n";

    private ServerSocket server;
    private CompletableFuture<Void> session = CompletableFuture.completedFuture(null);

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void aServerListingStartTlsAmongItsExtensionsOffersIt() throws Exception {
        serve(conversation("220 mail.example ESMTP ready",
            "250-mail.example greets you", "250-SIZE 10240000", "250-starttls", "250 8BITMIME"));

        assertThat(probe.probe(host(), port())).isEqualTo(Support.OFFERED);

        session.get(5, TimeUnit.SECONDS);
        assertThat(received).hasSize(2);
        assertThat(received.getFirst()).matches("EHLO \\[127\\.0\\.0\\.1]");
        assertThat(received.getLast()).isEqualTo("QUIT");
    }

    @Test
    void aServerWithoutTheExtensionDoesNotOfferIt() throws Exception {
        serve(conversation("220-mail.example ESMTP" + CRLF + "220 second greeting line",
            "250-mail.example", "250-STARTTLSX", "250 AUTH PLAIN LOGIN"));

        assertThat(probe.probe(host(), port())).isEqualTo(Support.NOT_OFFERED);

        session.get(5, TimeUnit.SECONDS);
        assertThat(received).as("no credentials are sent on the probe").noneMatch(line -> line.startsWith("AUTH"));
    }

    @Test
    void aSingleLineEhloReplyListsNoExtensions() throws Exception {
        serve(conversation("220 mail.example", "250 STARTTLS"));

        assertThat(probe.probe(host(), port())).as("the first line names the server, not an extension").isEqualTo(Support.NOT_OFFERED);
    }

    @Test
    void aServerThatRefusesTheSessionIsUnknownAndHearsNoEhlo() throws Exception {
        serve(client -> {
            writeLines(client, "554 No service");
            client.close();
        });

        assertThat(probe.probe(host(), port())).isEqualTo(Support.UNKNOWN);

        session.get(5, TimeUnit.SECONDS);
        assertThat(received).isEmpty();
    }

    @Test
    void aRefusedEhloIsUnknown() throws Exception {
        serve(conversation("220 mail.example", "502 Command not implemented"));

        assertThat(probe.probe(host(), port())).isEqualTo(Support.UNKNOWN);
    }

    @Test
    void aMalformedReplyIsUnknown() throws Exception {
        serve(conversation("220 mail.example", "250-mail.example", "251 mismatched code"));

        assertThat(probe.probe(host(), port())).isEqualTo(Support.UNKNOWN);
    }

    @Test
    void anOverlongReplyLineIsUnknown() throws Exception {
        serve(conversation("220 " + "x".repeat(StartTlsProbe.MAX_LINE_BYTES)));

        assertThat(probe.probe(host(), port())).isEqualTo(Support.UNKNOWN);
    }

    @Test
    void aSilentServerIsUnknownWithinTheStepTimeout() throws Exception {
        serve(client -> sleep(STEP.multipliedBy(3)));

        long started = System.nanoTime();
        assertThat(probe.probe(host(), port())).isEqualTo(Support.UNKNOWN);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(STEP.multipliedBy(4));
    }

    @Test
    void aServerThatKeepsTheReplyGoingIsCutOffAtTheStepDeadline() throws Exception {
        serve(client -> {
            OutputStream out = client.getOutputStream();
            for (int line = 0; line < 50; line++) {
                out.write(("220-still greeting" + CRLF).getBytes(StandardCharsets.US_ASCII));
                out.flush();
                sleep(STEP.dividedBy(5));
            }
        });

        long started = System.nanoTime();
        assertThat(probe.probe(host(), port())).isEqualTo(Support.UNKNOWN);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(STEP.multipliedBy(4));
    }

    @Test
    void aPortWithoutAServerIsUnknown() throws IOException {
        int closedPort;
        try (ServerSocket unused = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = unused.getLocalPort();
        }

        assertThat(probe.probe(InetAddress.getLoopbackAddress().getHostAddress(), closedPort)).isEqualTo(Support.UNKNOWN);
    }

    private Session conversation(String greeting, String... ehloReply) {
        return client -> {
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
            writeLines(client, greeting);
            String ehlo = in.readLine();
            if (ehlo == null) {
                return;
            }
            received.add(ehlo);
            writeLines(client, ehloReply);
            String quit = in.readLine();
            if (quit != null) {
                received.add(quit);
                writeLines(client, "221 bye");
            }
        };
    }

    private void serve(Session script) throws IOException {
        server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        session = CompletableFuture.runAsync(() -> {
            try (Socket client = server.accept()) {
                script.run(client);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private String host() {
        return server.getInetAddress().getHostAddress();
    }

    private int port() {
        return server.getLocalPort();
    }

    private static void writeLines(Socket client, String... lines) throws IOException {
        OutputStream out = client.getOutputStream();
        for (String line : lines) {
            out.write((line + CRLF).getBytes(StandardCharsets.US_ASCII));
        }
        out.flush();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface Session {
        void run(Socket client) throws IOException;
    }
}
