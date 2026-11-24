package gg.modl.minecraft.core.embedded;

import com.alessiodp.libby.download.ArtifactDownloader;
import com.alessiodp.libby.download.RetryPolicy;
import com.alessiodp.libby.logging.Logger;
import gg.modl.backend.embedded.EmbeddedServer;
import gg.modl.minecraft.core.connection.EmbeddedSettings;
import gg.modl.minecraft.core.support.RecordingPluginLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddedServerHostTest {
    private static final long WAIT_MILLIS = 10_000;
    private static final byte[] SPRING_JAR = "spring-classes".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path pluginFolder;

    @TempDir
    Path payloadFolder;

    private final RecordingPluginLogger logger = new RecordingPluginLogger();

    @AfterEach
    void clearFakeServerState() {
        System.clearProperty(EmbeddedServer.CLOSED_PROPERTY);
        System.clearProperty(EmbeddedServer.INTERNAL_KEY_PROPERTY);
        System.clearProperty(EmbeddedServer.STARTING_PROPERTY);
        System.clearProperty(EmbeddedServer.HOLD_PROPERTY);
        System.clearProperty(EmbeddedServer.FAIL_PROPERTY);
    }

    @Test
    void startsThePayloadInAnIsolatedLoaderAndClosesIt() throws Exception {
        int port = freePort();
        EmbeddedServerHandle handle = host(loopback(port), fakeServer()).start().get(30, TimeUnit.SECONDS);

        assertEquals("http://127.0.0.1:" + port, handle.endpoint().baseUrl());
        assertEquals(System.getProperty(EmbeddedServer.INTERNAL_KEY_PROPERTY), handle.endpoint().getApiKey());
        assertEquals(64, handle.endpoint().getApiKey().length());
        assertEquals("http://localhost:" + port, handle.publicUrl());
        assertTrue(logger.infos().stream().anyMatch(line -> line.contains("The built-in panel is listening on port " + port)));

        handle.close();

        assertEquals("true", System.getProperty(EmbeddedServer.CLOSED_PROPERTY));
        assertEquals("", handle.publicUrl());
    }

    @Test
    void failsWithAClearMessageWhenThePortIsTaken() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0)) {
            ExecutionException failure = assertThrows(ExecutionException.class, () -> host(EmbeddedSettings.builder()
                    .port(occupied.getLocalPort()).build(), fakeServer()).start().get(30, TimeUnit.SECONDS));

            assertInstanceOf(EmbeddedServerException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("already in use"));
            assertFalse(System.getProperties().containsKey(EmbeddedServer.INTERNAL_KEY_PROPERTY));
        }
    }

    @Test
    void aStartCancelledWhileDownloadingNeverStartsTheServerAndFreesTheDirectory() throws Exception {
        MavenArtifact springCore = MavenArtifact.parse("org.springframework:spring-core:7.1.0");
        try (MavenRepositoryFixture repository = MavenRepositoryFixture.start()) {
            repository.publish(springCore, SPRING_JAR);
            CountDownLatch releaseDownload = repository.holdHalfway(springCore);
            BasePayload payload = downloadingPayload(repository, springCore);
            int port = freePort();

            CompletableFuture<EmbeddedServerHandle> cancelled = host(loopback(port), payload).start();
            assertTrue(repository.arrivalOf(springCore).await(WAIT_MILLIS, TimeUnit.MILLISECONDS));
            cancelled.cancel(false);
            releaseDownload.countDown();

            EmbeddedServerHandle restarted = host(loopback(port), payload).start().get(30, TimeUnit.SECONDS);

            assertTrue(cancelled.isCancelled());
            assertEquals(1, panelStartedLines());
            restarted.close();
        }
    }

    @Test
    void aStartCancelledWhileTheServerStartsClosesTheServerOnceItIsUp() throws Exception {
        System.setProperty(EmbeddedServer.HOLD_PROPERTY, "true");
        CompletableFuture<EmbeddedServerHandle> cancelled = host(loopback(freePort()), fakeServer()).start();
        awaitProperty(EmbeddedServer.STARTING_PROPERTY);

        cancelled.cancel(false);
        System.clearProperty(EmbeddedServer.HOLD_PROPERTY);

        awaitProperty(EmbeddedServer.CLOSED_PROPERTY);
        assertTrue(cancelled.isCancelled());
        assertEquals(0, panelStartedLines());
    }

    @Test
    void aSecondStartInTheSameJvmWaitsUntilTheCancelledFirstStartHasStopped() throws Exception {
        int port = freePort();
        System.setProperty(EmbeddedServer.HOLD_PROPERTY, "true");
        CompletableFuture<EmbeddedServerHandle> first = host(loopback(port), fakeServer()).start();
        awaitProperty(EmbeddedServer.STARTING_PROPERTY);
        first.cancel(false);
        System.clearProperty(EmbeddedServer.STARTING_PROPERTY);

        CompletableFuture<EmbeddedServerHandle> second = host(loopback(port), fakeServer()).start();
        TimeUnit.MILLISECONDS.sleep(EmbeddedServerLock.RETRY_MILLIS * 3);

        assertFalse(second.isDone());
        assertFalse(System.getProperties().containsKey(EmbeddedServer.STARTING_PROPERTY));

        System.clearProperty(EmbeddedServer.HOLD_PROPERTY);
        EmbeddedServerHandle handle = second.get(30, TimeUnit.SECONDS);

        assertEquals("true", System.getProperty(EmbeddedServer.CLOSED_PROPERTY));
        assertEquals(1, panelStartedLines());
        handle.close();
    }

    @Test
    void aServerThatFailsToStartFreesTheDirectoryForTheNextStart() throws Exception {
        int port = freePort();
        System.setProperty(EmbeddedServer.FAIL_PROPERTY, "true");

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> host(loopback(port), fakeServer()).start().get(30, TimeUnit.SECONDS));
        System.clearProperty(EmbeddedServer.FAIL_PROPERTY);

        assertTrue(failure.getCause().getMessage().contains("fake server start failure"), failure.getCause().getMessage());
        host(loopback(port), fakeServer()).start().get(30, TimeUnit.SECONDS).close();
        assertEquals(1, panelStartedLines());
    }

    private EmbeddedServerHost host(EmbeddedSettings settings, BasePayload payload) {
        return EmbeddedServerHost.builder()
                .settings(settings)
                .paths(paths())
                .payloads(ManagedPayloads.unmanaged(payload))
                .progress(new StartupProgress())
                .configPath("plugins/modl/connection.yml")
                .logger(logger)
                .build();
    }

    private BasePayload fakeServer() throws IOException {
        FakeServerPayload.write(payloadFolder);
        return PayloadZips.base(payloadFolder, pluginFolder.resolve("archive"),
                PayloadZips.archives(paths(), MavenCentralDownloader.create(logger), logger));
    }

    private BasePayload downloadingPayload(MavenRepositoryFixture repository, MavenArtifact library) throws IOException {
        FakeServerPayload.builder(payloadFolder).central(library, SPRING_JAR).write();
        MavenCentralDownloader downloader = new MavenCentralDownloader(
                new ArtifactDownloader(new Logger(new DebugLogAdapter(logger)), RetryPolicy.of(1, 0)),
                repository.repositoryUrl(), 1);
        return PayloadZips.base(payloadFolder, pluginFolder.resolve("archive"), PayloadZips.archives(paths(), downloader, logger));
    }

    private EmbeddedServerPaths paths() {
        return EmbeddedServerPaths.forPluginDataFolder(pluginFolder);
    }

    private long panelStartedLines() {
        return logger.infos().stream().filter(line -> line.startsWith("The built-in panel is listening on port")).count();
    }

    private static EmbeddedSettings loopback(int port) {
        return EmbeddedSettings.builder().port(port).bindAddress("127.0.0.1").build();
    }

    private static void awaitProperty(String name) throws InterruptedException {
        await(() -> System.getProperties().containsKey(name));
        assertTrue(System.getProperties().containsKey(name), name);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) TimeUnit.MILLISECONDS.sleep(10);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
