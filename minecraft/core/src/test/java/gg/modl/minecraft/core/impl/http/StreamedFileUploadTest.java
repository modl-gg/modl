package gg.modl.minecraft.core.impl.http;

import com.sun.net.httpserver.HttpServer;
import gg.modl.minecraft.core.boot.VersionMismatchFailure;
import gg.modl.minecraft.core.boot.VersionMismatchReports;
import gg.modl.minecraft.core.connection.BackendEndpoint;
import gg.modl.minecraft.core.plugin.PluginInfo;
import gg.modl.minecraft.core.support.TestConnections;
import gg.modl.update.CompatibilityHeaders;
import gg.modl.update.VersionCompatibility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StreamedFileUploadTest {
    private static final String JOB = "0123456789abcdef";
    private static final int FILE_BYTES = 3 * 1024 * 1024;
    private static final int ACCEPTED = 202, FOUND = 302;
    private static final long NO_BODY = -1;

    @TempDir
    Path directory;

    private final AtomicLong receivedBytes = new AtomicLong();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> requestLine = new AtomicReference<>();
    private final AtomicReference<String> declaredLength = new AtomicReference<>();
    private final AtomicReference<String> apiKey = new AtomicReference<>();
    private final AtomicReference<String> pluginVersion = new AtomicReference<>();
    private HttpServer server;
    private Path file;
    private final VersionMismatchReports mismatches = new VersionMismatchReports();
    private int answer = ACCEPTED;
    private String compatibilityAnswer;

    @BeforeEach
    void startServer() throws IOException {
        byte[] content = new byte[FILE_BYTES];
        Arrays.fill(content, (byte) ' ');
        file = Files.write(directory.resolve("export.json"), content);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            requestLine.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            declaredLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
            apiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            pluginVersion.set(exchange.getRequestHeaders().getFirst(CompatibilityHeaders.PLUGIN_VERSION));
            InputStream body = exchange.getRequestBody();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = body.read(buffer)) != -1) receivedBytes.addAndGet(read);
            if (answer == FOUND) exchange.getResponseHeaders().add("Location", "/elsewhere");
            if (compatibilityAnswer != null) {
                exchange.getResponseHeaders().add(CompatibilityHeaders.COMPATIBILITY, compatibilityAnswer);
            }
            exchange.sendResponseHeaders(answer, NO_BODY);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void theFileIsStreamedWithItsLengthAndTheKey() throws IOException {
        StreamedFileUpload upload = upload();

        assertEquals(ACCEPTED, upload.send(JOB, file));

        assertEquals("PUT /v1/minecraft/migration/jobs/" + JOB + "/file", requestLine.get());
        assertEquals(String.valueOf(FILE_BYTES), declaredLength.get());
        assertEquals("api-key", apiKey.get());
        assertEquals(PluginInfo.VERSION, pluginVersion.get());
        assertEquals(FILE_BYTES, receivedBytes.get());
        assertFalse(upload.isStalled());
    }

    @Test
    void aRedirectNeverReceivesTheKeyOrTheFile() throws IOException {
        answer = FOUND;

        assertEquals(FOUND, upload().send(JOB, file));
        assertEquals(1, requests.get());
    }

    @Test
    void anAbortedUploadSendsNothing() {
        StreamedFileUpload upload = upload();
        upload.abort();

        assertThrows(IOException.class, () -> upload.send(JOB, file));
        assertEquals(0, requests.get());
    }

    @Test
    void aVersionRefusalReachesTheLifecycleThatOwnsTheConnection() throws IOException {
        answer = CompatibilityHeaders.REFUSAL_STATUS;
        compatibilityAnswer = VersionCompatibility.PLUGIN_OLDER.name();
        List<VersionMismatchFailure> reported = new ArrayList<>();
        mismatches.listen(reported::add);

        assertEquals(CompatibilityHeaders.REFUSAL_STATUS, upload().send(JOB, file));

        assertEquals(1, reported.size());
        assertEquals(VersionCompatibility.PLUGIN_OLDER, reported.get(0).compatibility());
    }

    private StreamedFileUpload upload() {
        return new StreamedFileUpload(TestConnections.reportingTo(
                BackendEndpoint.of("http://127.0.0.1:" + server.getAddress().getPort(), "api-key"), mismatches));
    }
}
