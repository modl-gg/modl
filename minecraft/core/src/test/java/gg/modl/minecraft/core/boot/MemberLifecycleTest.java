package gg.modl.minecraft.core.boot;

import com.google.gson.Gson;
import gg.modl.minecraft.api.http.request.StartupRequest;
import gg.modl.minecraft.api.http.response.StartupResponse;
import gg.modl.minecraft.core.connection.BackendClientIdentity;
import gg.modl.minecraft.core.connection.BackendConnections;
import gg.modl.minecraft.core.connection.BackendEndpointResolver;
import gg.modl.minecraft.core.connection.PlainHttpNotice;
import gg.modl.minecraft.core.connection.RemoteSettings;
import gg.modl.minecraft.core.member.MemberSession;
import gg.modl.minecraft.core.member.MemberSessionListener;
import gg.modl.minecraft.core.support.FakeModlHttpClient;
import gg.modl.minecraft.core.support.RecordingPluginLogger;
import gg.modl.update.VersionCompatibility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemberLifecycleTest {
    private static final String SERVER_URL = "http://203.0.113.7:8080";
    private static final String CONFIG_PATH = "plugins/modl/connection.yml";
    private static final String MEMBER_REMEDY = "Check remote.api-key in " + CONFIG_PATH
            + "; create a server key in Panel > Settings > General > Minecraft servers > Add server.";

    private final RecordingPluginLogger logger = new RecordingPluginLogger();
    private final Queue<Attempt> attempts = new ConcurrentLinkedQueue<>();
    private final RecordingListener listener = new RecordingListener();
    private final ManualTimer timer = new ManualTimer();
    private final AtomicInteger clientShutdowns = new AtomicInteger();
    private final AtomicInteger startupCalls = new AtomicInteger();
    private final List<BackendConnections> sessionConnections = new CopyOnWriteArrayList<>();
    private MemberLifecycle lifecycle;

    @AfterEach
    void stop() {
        if (lifecycle != null) lifecycle.shutdown();
    }

    @Test
    void theStartupRequestNamesTheMemberRoleWhileCoresKeepTheirs() {
        StartupRequest core = new StartupRequest("3.0.0", "SPIGOT", "1.21.11", 20);
        StartupRequest member = MemberLifecycle.memberRequest(core);

        assertEquals(StartupRequest.ServerRole.CORE, core.getServerRole());
        assertEquals(StartupRequest.ServerRole.MEMBER, member.getServerRole());
        assertTrue(new Gson().toJson(member).contains("\"serverRole\":\"MEMBER\""), new Gson().toJson(member));
        assertTrue(new Gson().toJson(core).contains("\"serverRole\":\"CORE\""), new Gson().toJson(core));
        assertEquals(core.getPluginVersion(), member.getPluginVersion());
        assertEquals(core.getMaxPlayers(), member.getMaxPlayers());
    }

    @Test
    void failuresNameTheServerKeyAndNeverFailClosed() {
        attempts.add(rejected());
        attempts.add(rejected());
        attempts.add(answering(startup(true, true)));
        lifecycle = lifecycle();

        lifecycle.start();
        await(() -> listener.connected.size() == 1);

        assertEquals("remote.url " + SERVER_URL + " sends the API key without encryption to a public address. "
                + "Use https:// (see https://github.com/modl-gg/modl/wiki/HTTPS-and-Reverse-Proxy).", logger.warnings().get(0));
        assertEquals("The modl server at " + SERVER_URL + " rejected the API key, retrying in 0s. " + MEMBER_REMEDY,
                logger.warnings().get(1));
        assertEquals(MemberLifecycle.PLAYERS_CAN_JOIN, logger.warnings().get(2));
        assertEquals(1, Collections.frequency(logger.warnings(), MemberLifecycle.PLAYERS_CAN_JOIN));
        assertEquals(4, logger.warnings().size(), logger.warnings().toString());
        assertTrue(logger.severes().isEmpty(), logger.severes().toString());
        assertTrue(logger.infos().contains("Connected to the modl server at " + SERVER_URL + "."));
        assertEquals(3, startupCalls.get());
    }

    @Test
    void aServerWithoutAHubIsReportedAtOnceAndEveryFiveMinutes() {
        attempts.add(answering(startup(true, null)));
        lifecycle = connected();

        String notice = MemberLifecycle.noHubNotice(SERVER_URL);
        assertEquals("[network] The modl server at " + SERVER_URL
                + " does not run a network hub; staff tools on this server stay off until it is updated.", notice);
        assertEquals(1, Collections.frequency(logger.warnings(), notice));
        assertEquals(TimeUnit.MINUTES.toMillis(5), timer.periodMillis);

        timer.fire();
        timer.fire();

        assertEquals(3, Collections.frequency(logger.warnings(), notice));
    }

    @Test
    void disabledRealtimeWinsOverAMissingHub() {
        attempts.add(answering(startup(false, null)));
        lifecycle = connected();

        String notice = MemberLifecycle.realtimeDisabledNotice(SERVER_URL);
        assertEquals("[network] Realtime is disabled on the modl server at " + SERVER_URL
                + " (modl.realtime.ws.enabled=false); staff tools on this server stay off.", notice);
        timer.fire();

        assertEquals(2, Collections.frequency(logger.warnings(), notice));
        assertFalse(logger.warnings().contains(MemberLifecycle.noHubNotice(SERVER_URL)));
    }

    @Test
    void aServerWithAHubAndRealtimeSchedulesNoNotice() {
        attempts.add(answering(startup(true, true)));
        lifecycle = connected();

        assertEquals(0, timer.scheduled.get());
        assertFalse(logger.warnings().stream().anyMatch(line -> line.startsWith("[network]")));
        assertSame(listener.connected.get(0).httpClient(), lifecycle.httpClient());
    }

    @Test
    void degradeRepeatsItsReasonInsteadOfTheHubNoticeAndKeepsPlayersAndHttp() {
        attempts.add(answering(startup(true, null)));
        lifecycle = connected();

        lifecycle.degrade("modl 3.0.0 here does not match modl 3.1.0 on the modl server.");
        timer.fire();

        String notice = MemberLifecycle.degradedNotice("modl 3.0.0 here does not match modl 3.1.0 on the modl server.");
        assertEquals("[network] Staff tools on this server stay off: modl 3.0.0 here does not match modl 3.1.0 on the "
                + "modl server.", notice);
        assertEquals(2, Collections.frequency(logger.warnings(), notice));
        assertEquals(1, Collections.frequency(logger.warnings(), MemberLifecycle.noHubNotice(SERVER_URL)));
        assertEquals(Collections.singletonList("modl 3.0.0 here does not match modl 3.1.0 on the modl server."),
                listener.degraded);
        assertTrue(listener.disconnected.isEmpty());
        assertTrue(logger.severes().isEmpty());
        lifecycle.httpClient();
    }

    @Test
    void restartEndsTheSessionAndConnectsAFreshOne() {
        attempts.add(answering(startup(true, null)));
        attempts.add(rejected());
        attempts.add(answering(startup(true, true)));
        lifecycle = connected();
        MemberSession first = listener.connected.get(0);

        lifecycle.restart("the modl server rejected this server's key");
        await(() -> listener.connected.size() == 2);

        assertEquals(Collections.singletonList(first), listener.disconnected);
        assertTrue(first.isClosed());
        assertEquals(1, clientShutdowns.get());
        assertThrows(IllegalStateException.class, first::httpClient);
        assertNotSame(first, listener.connected.get(1));
        assertSame(listener.connected.get(1).httpClient(), lifecycle.httpClient());
        assertEquals(1, timer.cancelled.get());
        assertEquals(1, Collections.frequency(logger.warnings(), MemberLifecycle.PLAYERS_CAN_JOIN));
        assertTrue(logger.warnings().contains("The modl server at " + SERVER_URL
                + " rejected the API key, retrying in 0s. " + MEMBER_REMEDY));
    }

    @Test
    void aVersionRefusalOnTheSessionsConnectionsReconnectsTheSession() {
        attempts.add(answering(startup(true, true)));
        attempts.add(answering(startup(true, true)));
        lifecycle = connected();
        MemberSession first = listener.connected.get(0);

        sessionConnections.get(0).report(new VersionMismatchFailure(VersionCompatibility.SERVER_OLDER, "3.0.0", "2.9.0",
                SERVER_URL));
        await(() -> listener.connected.size() == 2);

        assertEquals(Collections.singletonList(first), listener.disconnected);
        assertSame(sessionConnections.get(0), sessionConnections.get(1));
    }

    @Test
    void shutdownStopsTheNoticeAndClosesTheSession() {
        attempts.add(answering(startup(true, null)));
        lifecycle = connected();

        lifecycle.shutdown();
        timer.fire();

        assertEquals(1, timer.cancelled.get());
        assertEquals(1, Collections.frequency(logger.warnings(), MemberLifecycle.noHubNotice(SERVER_URL)));
        assertEquals(1, listener.disconnected.size());
        assertEquals(1, clientShutdowns.get());
        assertThrows(IllegalStateException.class, lifecycle::httpClient);
    }

    @Test
    void noClientIsAvailableBeforeTheFirstConnection() {
        lifecycle = lifecycle();

        assertThrows(IllegalStateException.class, lifecycle::httpClient);
    }

    private MemberLifecycle connected() {
        MemberLifecycle connecting = lifecycle();
        connecting.start();
        await(() -> listener.connected.size() == 1);
        return connecting;
    }

    private MemberLifecycle lifecycle() {
        RemoteBackendSource source = new RemoteBackendSource(
                RemoteSettings.builder().url(SERVER_URL).apiKey("server_key").build(),
                BackendEndpointResolver.fromEnvironment(), RemoteBackendSource.memberRemedy(CONFIG_PATH), logger,
                new PlainHttpNotice(InetAddress::getAllByName), Runnable::run);
        return new MemberLifecycle(source, BackendClientIdentity.newBoot(PlatformType.SPIGOT), this::startupCall,
                this::session, listener, logger, failures -> 0, timer);
    }

    private StartupCall startupCall(BackendConnections connections) {
        return new StartupCall() {
            @Override
            public String serverUrl() {
                return connections.baseUrl();
            }

            @Override
            public StartupResponse call() throws IOException {
                startupCalls.incrementAndGet();
                Attempt attempt = attempts.poll();
                if (attempt == null) throw new IOException("Connection refused");
                return attempt.run();
            }
        };
    }

    private MemberSession session(BackendConnections connections, StartupResponse startup) {
        sessionConnections.add(connections);
        return MemberSession.of(connections, startup, new FakeModlHttpClient() {
            @Override
            public void shutdown() {
                clientShutdowns.incrementAndGet();
            }
        });
    }

    private static StartupResponse startup(boolean realtimeEnabled, Boolean networkHub) {
        return new StartupResponse("https://panel.example.test", null, "srv-member", realtimeEnabled, 1,
                Collections.<String>emptyList(), networkHub);
    }

    private static Attempt answering(StartupResponse response) {
        return () -> response;
    }

    private static Attempt rejected() {
        return () -> {
            throw new CredentialsRejectedException(HttpStatusException.withBody(401, ""));
        };
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            try {
                TimeUnit.MILLISECONDS.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(condition.getAsBoolean(), "condition not met in time");
    }

    private interface Attempt {
        StartupResponse run() throws IOException;
    }

    private static final class RecordingListener implements MemberSessionListener {
        private final List<MemberSession> connected = new CopyOnWriteArrayList<>();
        private final List<MemberSession> disconnected = new CopyOnWriteArrayList<>();
        private final List<String> degraded = new CopyOnWriteArrayList<>();

        @Override
        public void onConnected(MemberSession session) {
            connected.add(session);
        }

        @Override
        public void onDisconnected(MemberSession session) {
            disconnected.add(session);
        }

        @Override
        public void onDegraded(String reason) {
            degraded.add(reason);
        }
    }

    private static final class ManualTimer implements MemberLifecycle.StatusTimer {
        private final List<Runnable> active = new ArrayList<>();
        private final AtomicInteger scheduled = new AtomicInteger();
        private final AtomicInteger cancelled = new AtomicInteger();
        private long periodMillis;

        @Override
        public synchronized Runnable every(long periodMillis, Runnable task) {
            this.periodMillis = periodMillis;
            scheduled.incrementAndGet();
            active.add(task);
            return () -> {
                synchronized (this) {
                    if (active.remove(task)) cancelled.incrementAndGet();
                }
            };
        }

        synchronized void fire() {
            for (Runnable task : new ArrayList<>(active)) task.run();
        }
    }
}
