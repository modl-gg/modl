package gg.modl.backend.tunnel.cloudflared;

import gg.modl.backend.tunnel.TunnelEvents;
import gg.modl.backend.tunnel.TunnelState;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

@Slf4j
public class CloudflaredSupervisor {
    static final Duration STOP_JOIN = CloudflaredLauncher.STOP_GRACE.multipliedBy(2).plusSeconds(2);
    static final Duration READER_JOIN = Duration.ofSeconds(2);

    private final BinarySource binaries;
    private final CloudflaredLauncher launcher;
    private final TunnelEvents events;
    private final Timing timing;
    private final Clock clock;
    private final String token;
    private final Consumer<String> output;
    private final ConnectionWatch watch;
    private volatile boolean running;
    private @Nullable Thread thread;
    private @Nullable Process process;
    private volatile @Nullable URI readiness;

    public CloudflaredSupervisor(BinarySource binaries, CloudflaredLauncher launcher, TunnelEvents events, Timing timing,
                                 Clock clock, String token, Consumer<String> output) {
        this.binaries = binaries;
        this.launcher = launcher;
        this.events = events;
        this.timing = timing;
        this.clock = clock;
        this.token = token;
        this.output = output;
        this.watch = new ConnectionWatch(timing.notConnectedAfter());
    }

    public synchronized void start() {
        if (thread != null) {
            return;
        }
        running = true;
        thread = Thread.ofPlatform().daemon().name("modl-tunnel").start(this::supervise);
    }

    public void stop() {
        Thread supervising;
        Process current;
        synchronized (this) {
            running = false;
            supervising = thread;
            current = process;
        }
        if (supervising == null) {
            return;
        }
        supervising.interrupt();
        if (current != null) {
            CloudflaredLauncher.stop(current.toHandle());
        }
        join(supervising, STOP_JOIN);
    }

    public TunnelState state() {
        return watch.state();
    }

    private void supervise() {
        Duration retry = timing.firstRetry();
        while (running) {
            boolean connected = runOnce();
            if (!running) {
                return;
            }
            Duration delay = connected ? timing.firstRetry() : retry;
            retry = connected ? timing.firstRetry() : doubled(retry);
            log.info("Starting cloudflared again in {} seconds", delay.toSeconds());
            pause(delay);
        }
    }

    private boolean runOnce() {
        Process started;
        try {
            launcher.stopLeftover();
            Path binary = binaries.verifiedBinary(() -> !running);
            synchronized (this) {
                if (!running) {
                    return false;
                }
                readiness = null;
                started = launcher.start(binary);
                process = started;
            }
        } catch (TunnelUnavailableException e) {
            if (running) {
                log.debug("Cloudflare Tunnel cannot start", e);
                announce(watch.unavailable(e.reason()), e.reason());
            }
            return false;
        } catch (CancellationException stopping) {
            return false;
        }
        watch.launched(clock.instant());
        Thread reader = Thread.ofPlatform().daemon().name("modl-tunnel-output").start(() -> read(started));
        try {
            awaitExit(started);
        } finally {
            CloudflaredLauncher.stop(started.toHandle());
            synchronized (this) {
                process = null;
            }
            launcher.stopped();
            join(reader, READER_JOIN);
        }
        log.info("cloudflared exited with code {}", started.isAlive() ? "unknown" : String.valueOf(started.exitValue()));
        return watch.exited(clock.instant());
    }

    private void awaitExit(Process started) {
        Instant nextReadinessCheck = clock.instant().plus(timing.readinessPoll());
        try {
            while (running && !started.waitFor(timing.poll().toMillis(), TimeUnit.MILLISECONDS)) {
                if (!clock.instant().isBefore(nextReadinessCheck)) {
                    checkReadiness();
                    nextReadinessCheck = clock.instant().plus(timing.readinessPoll());
                }
                announce(watch.check(clock.instant()), null);
            }
        } catch (InterruptedException stopping) {
            log.debug("The tunnel is stopping");
        }
    }

    private void checkReadiness() {
        URI endpoint = readiness;
        if (endpoint == null) {
            return;
        }
        if (ReadinessProbe.ready(endpoint)) {
            announce(watch.connected(), null);
        } else {
            watch.disconnected(clock.instant());
        }
    }

    private void read(Process started) {
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = lines.readLine()) != null) {
                handle(line);
            }
        } catch (IOException closed) {
            log.debug("The cloudflared output ended", closed);
        }
    }

    private void handle(String line) {
        try {
            output.accept(CloudflaredOutput.forLog(line, token));
            CloudflaredOutput.readinessEndpoint(line).ifPresent(endpoint -> readiness = endpoint);
            if (CloudflaredOutput.registersConnection(line)) {
                announce(watch.connected(), null);
            }
        } catch (RuntimeException e) {
            log.warn("A cloudflared output line could not be handled", e);
        }
    }

    private void announce(ConnectionWatch.Announcement announcement, @Nullable String reason) {
        try {
            switch (announcement) {
                case CONNECTED -> events.connected();
                case NOT_CONNECTED -> events.notConnected();
                case UNAVAILABLE -> events.unavailable(reason == null ? "unknown" : reason);
                case NONE -> {
                }
            }
        } catch (RuntimeException e) {
            log.warn("A Cloudflare Tunnel notice could not be published", e);
        }
    }

    private void pause(Duration delay) {
        Instant until = clock.instant().plus(delay);
        try {
            while (running && clock.instant().isBefore(until)) {
                long remaining = Duration.between(clock.instant(), until).toMillis();
                Thread.sleep(Math.max(1, Math.min(remaining, timing.poll().toMillis())));
                announce(watch.check(clock.instant()), null);
            }
        } catch (InterruptedException stopping) {
            log.debug("The tunnel is stopping");
        }
    }

    private Duration doubled(Duration retry) {
        Duration next = retry.multipliedBy(2);
        return next.compareTo(timing.maxRetry()) > 0 ? timing.maxRetry() : next;
    }

    private static void join(Thread thread, Duration timeout) {
        try {
            thread.join(timeout);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    public interface BinarySource {
        Path verifiedBinary(BooleanSupplier cancelled) throws TunnelUnavailableException;
    }

    public record Timing(Duration notConnectedAfter, Duration firstRetry, Duration maxRetry, Duration poll,
                         Duration readinessPoll) {
        public static Timing standard() {
            return new Timing(Duration.ofMinutes(1), Duration.ofSeconds(5), Duration.ofMinutes(5), Duration.ofSeconds(1),
                Duration.ofSeconds(5));
        }
    }
}
