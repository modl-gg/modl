package gg.modl.minecraft.core.boot;

import com.sun.net.httpserver.HttpServer;
import gg.modl.minecraft.api.http.request.StartupRequest;
import gg.modl.minecraft.core.connection.BackendConnections;
import gg.modl.minecraft.core.connection.BackendEndpoint;
import gg.modl.minecraft.core.connection.ConnectionConfig;
import gg.modl.minecraft.core.connection.ConnectionConfigLoader;
import gg.modl.minecraft.core.connection.PanelUrl;
import gg.modl.minecraft.core.connection.ProxyForwarding;
import gg.modl.minecraft.core.embedded.EmbeddedServerException;
import gg.modl.minecraft.core.packet.PacketEventsRequirement;
import gg.modl.minecraft.core.plugin.PluginInfo;
import gg.modl.minecraft.core.support.RecordingPluginLogger;
import gg.modl.minecraft.core.support.TempConfigs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginLifecycleTest {
    private static final long WAIT_SECONDS = 45;
    private static final StartupRequest REQUEST = new StartupRequest(PluginInfo.VERSION, "SPIGOT", "1.21.11", 20);
    private static final String STARTUP_JSON = "{\"panelUrl\":\"https://panel.example.test\",\"serverInstanceId\":\"srv-1\","
            + "\"modlVersion\":\"" + PluginInfo.VERSION + "\"}";

    @TempDir
    Path dataFolder;

    private final RecordingPluginLogger logger = new RecordingPluginLogger();
    private final FakeHost host = new FakeHost();
    private final AtomicInteger failuresBeforeSuccess = new AtomicInteger();
    private final AtomicInteger startupRequests = new AtomicInteger();
    private HttpServer backend;
    private PluginLifecycle lifecycle;

    @BeforeEach
    void startBackend() throws IOException {
        backend = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        backend.createContext("/v2/minecraft/startup", exchange -> {
            exchange.getRequestBody().readAllBytes();
            startupRequests.incrementAndGet();
            boolean fail = failuresBeforeSuccess.getAndDecrement() > 0;
            byte[] body = (fail ? "{\"error\":\"unavailable\"}" : STARTUP_JSON).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(fail ? 503 : 200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        backend.start();
    }

    @AfterEach
    void stopEverything() {
        if (lifecycle != null) lifecycle.shutdown();
        backend.stop(0);
    }

    @Test
    void preparesTheCoreDuringEnableThenConnectsItOnTheMainThreadOnceTheServerAnswers() throws Exception {
        RecordingBootstrap bootstrap = new RecordingBootstrap();
        lifecycle = remoteLifecycle(this::reachableSource);

        lifecycle.enable(REQUEST, bootstrap);

        assertEquals(singletonList(CoreState.STARTING), bootstrap.statesWhenPrepared);
        assertTrue(bootstrap.started.await(WAIT_SECONDS, TimeUnit.SECONDS));
        PanelUrl panelUrl = PanelUrl.unresolved();
        bootstrap.sessions.get(0).resolvePanelUrl(panelUrl);
        assertEquals("https://panel.example.test", panelUrl.get());
        assertEquals("srv-1", bootstrap.sessions.get(0).serverInstanceId());
        assertEquals(CoreState.RUNNING, lifecycle.availability().state());
        assertTrue(host.gate.isOpen());
        assertEquals(1, host.unregistrations.get());
        assertEquals(1, host.mainThreadTasks.get());
        assertEquals(1, bootstrap.preparations.get());
        assertTrue(logger.warnings().isEmpty(), logger.warnings().toString());
    }

    @Test
    void aRefusalOnTheRunningSessionsConnectionsMakesTheCoreIncompatible() throws Exception {
        RecordingBootstrap bootstrap = new RecordingBootstrap();
        lifecycle = remoteLifecycle(this::reachableSource);
        lifecycle.enable(REQUEST, bootstrap);
        assertTrue(bootstrap.started.await(WAIT_SECONDS, TimeUnit.SECONDS));

        BackendConnections connections = bootstrap.sessions.get(0).getConnections();
        connections.report(connections.mismatchWith("9.0.0"));

        assertEquals(CoreState.INCOMPATIBLE, lifecycle.availability().state());
        assertFalse(host.gate.isOpen());
    }

    @Test
    void theCoreIsPreparedOnlyOnceEvenWhenEnableRunsAgain() throws Exception {
        RecordingBootstrap bootstrap = new RecordingBootstrap();
        lifecycle = remoteLifecycle(this::reachableSource);

        lifecycle.enable(REQUEST, bootstrap);
        assertTrue(bootstrap.started.await(WAIT_SECONDS, TimeUnit.SECONDS));
        lifecycle.enable(REQUEST, bootstrap);

        assertEquals(1, bootstrap.preparations.get());
        assertEquals(1, bootstrap.sessions.size());
    }

    @Test
    void deniesLoginsWhileRetryingThenStartsTheCoreLate() throws Exception {
        failuresBeforeSuccess.set(2);
        RecordingBootstrap bootstrap = new RecordingBootstrap();
        lifecycle = remoteLifecycle(this::reachableSource);

        lifecycle.enable(REQUEST, bootstrap);

        assertNotNull(host.gate);
        assertFalse(host.gate.isOpen());
        assertTrue(host.gate.deniedMessage().contains("Connection to panel failed"));

        assertEquals(1, bootstrap.preparations.get());
        assertTrue(bootstrap.started.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertTrue(lifecycle.availability().state().isRunning());
        assertEquals(1, host.unregistrations.get());
        assertTrue(host.mainThreadTasks.get() >= 1);
        assertEquals(3, startupRequests.get());
    }

    @Test
    void aShutdownWhileWaitingNeverStartsTheCore() throws Exception {
        failuresBeforeSuccess.set(Integer.MAX_VALUE);
        RecordingBootstrap bootstrap = new RecordingBootstrap();
        lifecycle = remoteLifecycle(this::reachableSource);

        lifecycle.enable(REQUEST, bootstrap);
        lifecycle.shutdown();
        failuresBeforeSuccess.set(0);
        TimeUnit.MILLISECONDS.sleep(300);

        assertTrue(bootstrap.sessions.isEmpty());
        assertEquals(CoreState.UNAVAILABLE, lifecycle.availability().state());
        assertEquals(1, bootstrap.stops.get());
    }

    @Test
    void enableReturnsWhileTheEmbeddedServerStartsAndTheCoreStartsLateOnceItIsReady() throws Exception {
        CompletableFuture<BackendEndpoint> starting = new CompletableFuture<>();
        RecordingBootstrap bootstrap = new RecordingBootstrap();
        lifecycle = remoteLifecycle(() -> new FakeSource(starting));

        lifecycle.enable(REQUEST, bootstrap);

        assertFalse(host.gate.isOpen());
        assertTrue(host.gate.deniedMessage().contains("Connection to panel failed"));
        assertTrue(bootstrap.sessions.isEmpty());
        assertEquals(1, bootstrap.preparations.get());
        assertEquals(CoreState.STARTING, lifecycle.availability().state());
        assertEquals(0, startupRequests.get());

        starting.complete(endpoint());

        assertTrue(bootstrap.started.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertTrue(lifecycle.availability().state().isRunning());
        assertTrue(host.gate.isOpen());
        assertTrue(logger.warnings().isEmpty(), logger.warnings().toString());
        assertFalse(logger.infos().stream().anyMatch(line -> line.startsWith("Connected to the modl server")), logger.infos().toString());
    }

    @Test
    void anEmbeddedServerThatFailsWhileStartingFailsClosedWithItsReason() throws Exception {
        CompletableFuture<BackendEndpoint> starting = new CompletableFuture<>();
        RecordingBootstrap bootstrap = new RecordingBootstrap();
        lifecycle = remoteLifecycle(() -> new FakeSource(starting));
        lifecycle.enable(REQUEST, bootstrap);

        starting.completeExceptionally(new EmbeddedServerException("The built-in panel made no startup progress for 180 seconds."));

        awaitSevere("modl is not running: The built-in panel made no startup progress for 180 seconds.");
        assertTrue(logger.severes().stream().anyMatch(line -> line.startsWith("Fix the problem and restart the server")));
        assertTrue(host.gate.deniedMessage().contains("not running"));
        assertTrue(bootstrap.sessions.isEmpty());
        assertEquals(CoreState.UNAVAILABLE, lifecycle.availability().state());
        assertEquals(1, bootstrap.preparations.get());
        assertEquals(0, bootstrap.stops.get());
    }

    @Test
    void shuttingDownWhileTheEmbeddedServerStartsReturnsAtOnceAndNeverStartsTheCore() throws Exception {
        CompletableFuture<BackendEndpoint> starting = new CompletableFuture<>();
        AtomicInteger backendShutdowns = new AtomicInteger();
        RecordingBootstrap bootstrap = new RecordingBootstrap();
        lifecycle = remoteLifecycle(() -> new FakeSource(starting, backendShutdowns::incrementAndGet));
        lifecycle.enable(REQUEST, bootstrap);

        long shutdownStarted = System.nanoTime();
        lifecycle.shutdown();

        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - shutdownStarted) < 1000);
        assertEquals(1, backendShutdowns.get());
        assertTrue(starting.isCancelled());
        TimeUnit.MILLISECONDS.sleep(300);
        assertTrue(bootstrap.sessions.isEmpty());
        assertFalse(lifecycle.availability().state().isRunning());
        assertTrue(logger.severes().isEmpty(), logger.severes().toString());
    }

    @Test
    void aReloadDuringAnUnfinishedStartLetsTheNextLifecycleStartNormally() throws Exception {
        CompletableFuture<BackendEndpoint> abandoned = new CompletableFuture<>();
        RecordingBootstrap first = new RecordingBootstrap();
        lifecycle = remoteLifecycle(() -> new FakeSource(abandoned));
        lifecycle.enable(REQUEST, first);
        lifecycle.shutdown();

        CompletableFuture<BackendEndpoint> restarted = new CompletableFuture<>();
        RecordingBootstrap second = new RecordingBootstrap();
        lifecycle = remoteLifecycle(() -> new FakeSource(restarted));
        lifecycle.enable(REQUEST, second);
        restarted.complete(endpoint());

        assertTrue(second.started.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertTrue(first.sessions.isEmpty());
        assertTrue(lifecycle.availability().state().isRunning());
    }

    @Test
    void anInvalidConnectionConfigFailsClosed() throws IOException {
        TempConfigs.write(dataFolder.resolve(ConnectionConfig.FILE_NAME), "mode: remote", "remote:", "  api-key: \"\"");
        RecordingBootstrap bootstrap = new RecordingBootstrap();
        lifecycle = new PluginLifecycle(host, loader(), config -> reachableSource(), failures -> 0);

        lifecycle.enable(REQUEST, bootstrap);

        assertTrue(bootstrap.sessions.isEmpty());
        assertEquals(0, bootstrap.preparations.get());
        assertFalse(host.gate.isOpen());
        assertTrue(host.gate.deniedMessage().contains("not running"));
        assertTrue(logger.severes().stream().anyMatch(line -> line.contains("modl is not running: connection.yml: remote.api-key")));
        assertTrue(logger.severes().stream().anyMatch(line -> line.contains("Players cannot join")));
    }

    @Test
    void anUnmetHostRequirementFailsClosedBeforeTheBackendStarts() throws IOException {
        writeRemoteConfig();
        host.unmetRequirement = PacketEventsRequirement.unmetBy(null);
        AtomicInteger sourcesCreated = new AtomicInteger();
        RecordingBootstrap bootstrap = new RecordingBootstrap();
        lifecycle = new PluginLifecycle(host, loader(), config -> {
            sourcesCreated.incrementAndGet();
            return reachableSource();
        }, failures -> 0);

        assertNull(lifecycle.load(() -> { }));
        lifecycle.enable(REQUEST, bootstrap);

        assertEquals(0, sourcesCreated.get());
        assertEquals(0, startupRequests.get());
        assertEquals(0, bootstrap.preparations.get());
        assertTrue(bootstrap.sessions.isEmpty());
        assertFalse(host.gate.isOpen());
        assertTrue(host.gate.deniedMessage().contains("PacketEvents 2.14.0 or newer"));
        assertTrue(logger.severes().stream().anyMatch(line ->
                line.equals("modl is not running: PacketEvents is not installed, but modl needs PacketEvents 2.14.0 or newer.")));
        assertTrue(logger.severes().stream().anyMatch(line ->
                line.equals("Install PacketEvents 2.14.0 or newer and restart the server.")));
        assertFalse(lifecycle.availability().state().isRunning());
    }

    @Test
    void anEmbeddedStartFailureFailsClosedLikeRemoteMode() throws IOException {
        writeRemoteConfig();
        RecordingBootstrap bootstrap = new RecordingBootstrap();
        lifecycle = new PluginLifecycle(host, loader(), config -> FakeSource.failed(
                "Port 8080 is already in use, so the built-in panel cannot start."), failures -> 0);

        lifecycle.enable(REQUEST, bootstrap);

        assertTrue(bootstrap.sessions.isEmpty());
        assertFalse(host.gate.isOpen());
        assertTrue(logger.severes().stream().anyMatch(line -> line.contains("Port 8080 is already in use")));
    }

    @Test
    void aCoreThatFailsToConnectStaysPreparedAsUnavailableUntilShutdown() throws Exception {
        lifecycle = remoteLifecycle(this::reachableSource);
        RecordingBootstrap bootstrap = RecordingBootstrap.failingToConnect(new IllegalStateException("broken listener"));

        lifecycle.enable(REQUEST, bootstrap);

        awaitSevere("modl is not running: modl failed to start (broken listener).");
        assertFalse(host.gate.isOpen());
        assertEquals(CoreState.UNAVAILABLE, lifecycle.availability().state());
        assertEquals(0, bootstrap.stops.get());

        lifecycle.shutdown();

        assertEquals(1, bootstrap.stops.get());
    }

    @Test
    void aCoreThatFailsToPrepareIsStoppedAndFailsClosedDuringEnable() throws IOException {
        lifecycle = remoteLifecycle(this::reachableSource);
        RecordingBootstrap bootstrap = RecordingBootstrap.failingToPrepare(new IllegalStateException("broken command"));

        lifecycle.enable(REQUEST, bootstrap);

        assertFalse(host.gate.isOpen());
        assertEquals(CoreState.UNAVAILABLE, lifecycle.availability().state());
        assertEquals(1, bootstrap.stops.get());
        assertEquals(0, startupRequests.get());
        assertTrue(logger.severes().contains("modl is not running: modl failed to start (broken command)."),
                logger.severes().toString());

        lifecycle.shutdown();

        assertEquals(1, bootstrap.stops.get());
    }

    @Test
    void aMissingRuntimeClassFailsClosedInsteadOfEscaping() throws Exception {
        lifecycle = remoteLifecycle(this::reachableSource);

        lifecycle.enable(REQUEST,
                RecordingBootstrap.failingToConnect(new NoClassDefFoundError("net/kyori/adventure/text/Component")));

        awaitSevere("modl is not running: modl failed to start (net/kyori/adventure/text/Component).");
        assertFalse(host.gate.isOpen());
    }

    @Test
    void aFailingPrerequisiteFailsClosed() throws IOException {
        writeRemoteConfig();
        lifecycle = new PluginLifecycle(host, loader(), config -> reachableSource(), failures -> 0);

        assertNull(lifecycle.load(() -> {
            throw new IllegalStateException("libraries could not be downloaded");
        }));
        lifecycle.enable(REQUEST, new RecordingBootstrap());

        assertFalse(host.gate.isOpen());
        assertTrue(logger.severes().stream().anyMatch(line -> line.contains("libraries could not be downloaded")));
    }

    @Test
    void aMalformedLocaleOverrideStillDeniesLogins() throws IOException {
        Files.createDirectories(dataFolder.resolve("locale"));
        TempConfigs.write(dataFolder.resolve("locale").resolve("en_US.yml"), "api_errors: [unclosed", "  : : :");
        failuresBeforeSuccess.set(Integer.MAX_VALUE);
        lifecycle = remoteLifecycle(this::reachableSource);

        lifecycle.enable(REQUEST, new RecordingBootstrap());

        assertNotNull(host.gate);
        assertFalse(host.gate.isOpen());
        assertTrue(host.gate.deniedMessage().contains("Connection to panel failed"));
    }

    @Test
    void shutdownStopsTheCoreBeforeTheBackend() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        FakeSource source = new FakeSource(CompletableFuture.completedFuture(endpoint()), () -> order.add("backend"));
        lifecycle = remoteLifecycle(() -> source);
        RecordingBootstrap bootstrap = RecordingBootstrap.stoppingWith(() -> order.add("core"));

        lifecycle.enable(REQUEST, bootstrap);
        assertTrue(bootstrap.started.await(WAIT_SECONDS, TimeUnit.SECONDS));
        lifecycle.shutdown();

        assertEquals(2, order.size());
        assertEquals("core", order.get(0));
        assertEquals("backend", order.get(1));
    }

    private void awaitSevere(String expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!logger.severes().contains(expected) && System.nanoTime() < deadline) TimeUnit.MILLISECONDS.sleep(20);
        assertTrue(logger.severes().contains(expected), logger.severes().toString());
    }

    private PluginLifecycle remoteLifecycle(Supplier<BackendSource> source) throws IOException {
        writeRemoteConfig();
        PluginLifecycle remote = new PluginLifecycle(host, loader(), config -> source.get(), failures -> 0);
        remote.load(() -> { });
        return remote;
    }

    @Test
    void anEmbeddedPanelOnTheGamePortIsRefusedBeforeItCanTakeThePort() throws IOException {
        TempConfigs.write(dataFolder.resolve(ConnectionConfig.FILE_NAME), "mode: embedded", "embedded:", "  port: 25565");
        AtomicInteger sourcesCreated = new AtomicInteger();
        lifecycle = new PluginLifecycle(host, loader(), config -> {
            sourcesCreated.incrementAndGet();
            return reachableSource();
        }, failures -> 0);

        assertNull(lifecycle.load(() -> { }));

        assertEquals(0, sourcesCreated.get());
        assertTrue(logger.severes().get(0).contains("embedded.port is 25565, the port players join on"),
                logger.severes().get(0));
    }

    @Test
    void anEmbeddedPanelOnTheRconPortIsRefusedBeforeItCanTakeThePort() throws IOException {
        TempConfigs.write(dataFolder.resolve(ConnectionConfig.FILE_NAME), "mode: embedded", "embedded:", "  port: 25575");
        host.rconPorts = Collections.singleton(25575);
        AtomicInteger sourcesCreated = new AtomicInteger();
        lifecycle = new PluginLifecycle(host, loader(), config -> {
            sourcesCreated.incrementAndGet();
            return reachableSource();
        }, failures -> 0);

        assertNull(lifecycle.load(() -> { }));

        assertEquals(0, sourcesCreated.get());
        assertTrue(logger.severes().get(0).contains("embedded.port is 25575, the RCON port of this server"),
                logger.severes().get(0));
    }

    private void writeRemoteConfig() throws IOException {
        TempConfigs.write(dataFolder.resolve(ConnectionConfig.FILE_NAME), "mode: remote", "remote:",
                "  url: \"http://127.0.0.1:1\"", "  api-key: \"modl_key\"");
    }

    private ConnectionConfigLoader loader() {
        return new ConnectionConfigLoader(dataFolder, PlatformType.SPIGOT, ProxyForwarding.NONE, logger);
    }

    private BackendSource reachableSource() {
        return new FakeSource(CompletableFuture.completedFuture(endpoint()));
    }

    private BackendEndpoint endpoint() {
        return BackendEndpoint.of("http://127.0.0.1:" + backend.getAddress().getPort(), "modl_key");
    }

    private final class FakeHost implements PluginHost {
        private volatile LoginGate gate;
        private final AtomicInteger unregistrations = new AtomicInteger();
        private volatile Optional<StartupFailure> unmetRequirement = Optional.empty();
        private volatile Set<Integer> gamePorts = Collections.singleton(25565);
        private volatile Set<Integer> rconPorts = Collections.emptySet();
        private final AtomicInteger mainThreadTasks = new AtomicInteger();

        @Override public PlatformType platformType() { return PlatformType.SPIGOT; }
        @Override public Path dataFolder() { return dataFolder; }
        @Override public RecordingPluginLogger logger() { return logger; }
        @Override public void registerLoginGate(LoginGate gate) { this.gate = gate; }
        @Override public void unregisterLoginGate() { unregistrations.incrementAndGet(); }
        @Override public void runOnMainThread(Runnable task) {
            mainThreadTasks.incrementAndGet();
            task.run();
        }
        @Override public Optional<StartupFailure> unmetRequirement() { return unmetRequirement; }
        @Override public Set<Integer> gamePorts() { return gamePorts; }
        @Override public Set<Integer> rconPorts() { return rconPorts; }
    }

    private static final class FakeSource implements BackendSource {
        private final CompletableFuture<BackendEndpoint> endpoint;
        private final Runnable onShutdown;

        private FakeSource(CompletableFuture<BackendEndpoint> endpoint) {
            this(endpoint, () -> {});
        }

        private FakeSource(CompletableFuture<BackendEndpoint> endpoint, Runnable onShutdown) {
            this.endpoint = endpoint;
            this.onShutdown = onShutdown;
        }

        private static FakeSource failed(String message) {
            CompletableFuture<BackendEndpoint> failed = new CompletableFuture<>();
            failed.completeExceptionally(new EmbeddedServerException(message));
            return new FakeSource(failed);
        }

        @Override public void start() {}
        @Override public CompletableFuture<BackendEndpoint> endpoint() { return endpoint; }
        @Override public String currentPanelUrl() { return null; }
        @Override public String rejectedCredentialsRemedy() { return "Check remote.api-key."; }
        @Override public void shutdown() {
            endpoint.cancel(false);
            onShutdown.run();
        }
    }

    private static final class RecordingBootstrap implements CoreBootstrap {
        private final List<CoreState> statesWhenPrepared = new CopyOnWriteArrayList<>();
        private final List<BackendSession> sessions = new CopyOnWriteArrayList<>();
        private final CountDownLatch started = new CountDownLatch(1);
        private final AtomicInteger preparations = new AtomicInteger();
        private final AtomicInteger stops = new AtomicInteger();
        private final Throwable prepareFailure;
        private final Throwable connectFailure;
        private final Runnable onStop;

        private RecordingBootstrap() {
            this(null, null, () -> { });
        }

        private RecordingBootstrap(Throwable prepareFailure, Throwable connectFailure, Runnable onStop) {
            this.prepareFailure = prepareFailure;
            this.connectFailure = connectFailure;
            this.onStop = onStop;
        }

        private static RecordingBootstrap failingToPrepare(Throwable failure) {
            return new RecordingBootstrap(failure, null, () -> { });
        }

        private static RecordingBootstrap failingToConnect(Throwable failure) {
            return new RecordingBootstrap(null, failure, () -> { });
        }

        private static RecordingBootstrap stoppingWith(Runnable onStop) {
            return new RecordingBootstrap(null, null, onStop);
        }

        @Override
        public void prepare(CoreAvailability availability) {
            preparations.incrementAndGet();
            statesWhenPrepared.add(availability.state());
            rethrow(prepareFailure);
        }

        @Override
        public synchronized void connect(BackendSession session) {
            rethrow(connectFailure);
            sessions.add(session);
            started.countDown();
        }

        @Override
        public void stop() {
            stops.incrementAndGet();
            onStop.run();
        }

        private static void rethrow(Throwable failure) {
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
        }
    }
}
