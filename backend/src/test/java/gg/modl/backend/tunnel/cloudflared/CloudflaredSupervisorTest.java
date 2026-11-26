package gg.modl.backend.tunnel.cloudflared;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import gg.modl.backend.tunnel.TunnelEvents;
import gg.modl.backend.tunnel.TunnelPhase;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(OS.WINDOWS)
class CloudflaredSupervisorTest {
    private static final String TOKEN = "eyJhIjoiYWNjb3VudCIsInQiOiJ0dW5uZWwiLCJzIjoic2VjcmV0In0=";
    private static final CloudflaredSupervisor.Timing FAST = new CloudflaredSupervisor.Timing(
        Duration.ofMillis(600), Duration.ofMillis(50), Duration.ofMillis(200), Duration.ofMillis(20), Duration.ofMillis(100));

    @TempDir
    Path data;

    private final List<String> events = new CopyOnWriteArrayList<>();
    private final List<String> output = new CopyOnWriteArrayList<>();
    private CloudflaredSupervisor supervisor;

    @AfterEach
    void tearDown() {
        if (supervisor != null) {
            supervisor.stop();
        }
    }

    @Test
    void theTokenTravelsOnlyInTheEnvironmentAndNeverReachesTheLog() throws Exception {
        Path stub = stub("""
            echo "args: $*"
            echo "token: $TUNNEL_TOKEN"
            echo "variables: $(env | grep -v '^PWD=' | grep -v '^SHLVL=' | grep -v '^_=' | cut -d= -f1 | sort | tr '\\n' ' ')"
            echo "INF Registered tunnel connection connIndex=0 connection=abc event=0 ip=198.41.192.7 location=fra01 protocol=quic"
            exec sleep 30
            """);
        start(stub);

        eventually(Duration.ofSeconds(10), () -> events.contains("connected"));
        assertThat(output).anySatisfy(line -> assertThat(line).startsWith("args: tunnel --no-autoupdate --config ")
            .contains("--metrics 127.0.0.1:0", "--protocol auto").endsWith(" run").doesNotContain(TOKEN));
        assertThat(output).contains("token: [redacted]", "variables: HOME TUNNEL_TOKEN ");
        assertThat(output).noneMatch(line -> line.contains(TOKEN));
        assertThat(supervisor.state().phase()).isEqualTo(TunnelPhase.CONNECTED);
        assertThat(events).containsExactly("connected");
    }

    @Test
    void stoppingEndsTheProcessAndRemovesItsPidFile() throws Exception {
        Path stub = stub("""
            echo "INF Registered tunnel connection connIndex=0"
            exec sleep 30
            """);
        start(stub);
        TunnelDirectory directory = TunnelDirectory.in(data);
        eventually(Duration.ofSeconds(10), () -> Files.exists(directory.pidFile()));
        long pid = Long.parseLong(Files.readString(directory.pidFile()).trim());

        supervisor.stop();

        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        assertThat(directory.pidFile()).doesNotExist();
    }

    @Test
    void aProcessThatIgnoresTheStopSignalIsKilled() throws Exception {
        Path stub = stub("""
            trap '' TERM
            echo "INF Registered tunnel connection connIndex=0"
            while true; do sleep 1; done
            """);
        start(stub);
        TunnelDirectory directory = TunnelDirectory.in(data);
        eventually(Duration.ofSeconds(10), () -> events.contains("connected"));
        long pid = Long.parseLong(Files.readString(directory.pidFile()).trim());

        supervisor.stop();

        eventually(Duration.ofSeconds(5), () -> ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true));
    }

    @Test
    void anExitingProcessIsRestartedWithBackoffAndTheFailureIsAnnouncedOnce() throws Exception {
        Path launches = data.resolve("launches.txt");
        Path stub = stub("echo launched >> '" + launches + "'\necho \"ERR Register tunnel error from server side\"\nexit 1\n");
        start(stub);

        eventually(Duration.ofSeconds(10), () -> Files.exists(launches) && Files.readAllLines(launches).size() >= 4
            && events.contains("not connected"));
        assertThat(events).containsExactly("not connected");
        assertThat(supervisor.state().phase()).isEqualTo(TunnelPhase.NOT_CONNECTED);
    }

    @Test
    void aReconnectAfterTheFailureNoticeIsAnnouncedAgain() throws Exception {
        Path marker = data.resolve("second-run");
        Path stub = stub("if [ -f '" + marker + "' ]; then echo \"INF Registered tunnel connection connIndex=1\"; exec sleep 30;"
            + " fi\nexit 1\n");
        start(stub);
        eventually(Duration.ofSeconds(10), () -> events.contains("not connected"));

        Files.writeString(marker, "");

        eventually(Duration.ofSeconds(10), () -> events.contains("connected"));
        assertThat(events).containsExactly("not connected", "connected");
    }

    @Test
    void aBinaryThatCannotBeInstalledIsAnnouncedOnceAndRetried() throws Exception {
        List<Integer> attempts = new CopyOnWriteArrayList<>();
        supervisor = new CloudflaredSupervisor(cancelled -> {
            attempts.add(attempts.size());
            throw new TunnelUnavailableException("cloudflared could not be downloaded");
        }, launcher(), recorder(), FAST,
            Clock.systemUTC(), TOKEN, output::add);
        supervisor.start();

        eventually(Duration.ofSeconds(10), () -> attempts.size() >= 3);
        assertThat(events).containsExactly("unavailable: cloudflared could not be downloaded");
        assertThat(supervisor.state().phase()).isEqualTo(TunnelPhase.UNAVAILABLE);
        assertThat(supervisor.state().problem()).isEqualTo("cloudflared could not be downloaded");
    }

    @Test
    void aSlowDownloadDoesNotCountAgainstTheMinuteToConnect() throws Exception {
        Path stub = stub("exec sleep 30\n");
        long launchedAt = System.nanoTime() + Duration.ofMillis(900).toNanos();
        supervisor = new CloudflaredSupervisor(cancelled -> {
            pauseQuietly(Duration.ofMillis(900));
            return stub;
        }, launcher(), recorder(), FAST, Clock.systemUTC(), TOKEN, output::add);
        supervisor.start();

        eventually(Duration.ofSeconds(10), () -> System.nanoTime() > launchedAt + Duration.ofMillis(300).toNanos());
        assertThat(events).isEmpty();
        eventually(Duration.ofSeconds(10), () -> events.contains("not connected"));
    }

    @Test
    void stoppingDuringTheDownloadPrintsNoNotice() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        supervisor = new CloudflaredSupervisor(cancelled -> {
            calls.add("download");
            while (!cancelled.getAsBoolean()) {
                Thread.onSpinWait();
            }
            throw new TunnelUnavailableException("cloudflared could not be downloaded (null)");
        }, launcher(), recorder(), FAST, Clock.systemUTC(), TOKEN, output::add);
        supervisor.start();
        eventually(Duration.ofSeconds(10), () -> !calls.isEmpty());

        supervisor.stop();

        assertThat(events).isEmpty();
    }

    @Test
    void aConnectionLostWithoutAnExitIsSeenThroughTheReadinessEndpoint() throws Exception {
        AtomicInteger readyStatus = new AtomicInteger(200);
        HttpServer metrics = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        metrics.createContext("/ready", exchange -> {
            exchange.sendResponseHeaders(readyStatus.get(), -1);
            exchange.close();
        });
        metrics.start();
        try {
            Path stub = stub("echo \"INF Starting metrics server on 127.0.0.1:" + metrics.getAddress().getPort() + "/metrics\"\n"
                + "echo \"INF Registered tunnel connection connIndex=0\"\nexec sleep 30\n");
            start(stub);
            eventually(Duration.ofSeconds(10), () -> events.contains("connected"));

            readyStatus.set(503);

            eventually(Duration.ofSeconds(10), () -> events.contains("not connected"));
            readyStatus.set(200);
            eventually(Duration.ofSeconds(10), () -> events.size() == 3);
            assertThat(events).containsExactly("connected", "not connected", "connected");
        } finally {
            metrics.stop(0);
        }
    }

    @Test
    void aStartedProcessThatCannotBeRecordedIsStoppedAgain() throws Exception {
        Path stub = stub("exec sleep 30\n");
        Files.createDirectories(TunnelDirectory.in(data).pidFile());
        Files.writeString(TunnelDirectory.in(data).pidFile().resolve("keep"), "");
        start(stub);

        eventually(Duration.ofSeconds(10), () -> events.stream().anyMatch(event -> event.startsWith("unavailable: ")));
        supervisor.stop();

        eventually(Duration.ofSeconds(10), () -> ProcessHandle.current().children().noneMatch(ProcessHandle::isAlive));
        assertThat(events).hasSize(1).allMatch(event -> event.contains("could not record its process"));
    }

    private CloudflaredLauncher launcher() {
        return new CloudflaredLauncher(TunnelDirectory.in(data), TOKEN, HostPlatform.current());
    }

    private void start(Path stub) {
        supervisor = new CloudflaredSupervisor(cancelled -> stub, launcher(), recorder(), FAST,
            Clock.systemUTC(), TOKEN, output::add);
        supervisor.start();
    }

    private TunnelEvents recorder() {
        return new TunnelEvents() {
            @Override
            public void connected() {
                events.add("connected");
            }

            @Override
            public void notConnected() {
                events.add("not connected");
            }

            @Override
            public void unavailable(String reason) {
                events.add("unavailable: " + reason);
            }
        };
    }

    private static void pauseQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
        }
    }

    private static void eventually(Duration timeout, Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.call()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("The condition did not hold within " + timeout);
            }
            Thread.sleep(20);
        }
    }

    private Path stub(String body) throws IOException {
        Path directory = Files.createDirectories(data.resolve("tunnel"));
        Path stub = directory.resolve("cloudflared-stub");
        Files.writeString(stub, "#!/bin/sh\n" + body, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(stub, PosixFilePermissions.fromString("rwx------"));
        return stub;
    }
}
