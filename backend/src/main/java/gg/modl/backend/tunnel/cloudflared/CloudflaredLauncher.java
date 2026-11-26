package gg.modl.backend.tunnel.cloudflared;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CloudflaredLauncher {
    static final String TOKEN_VARIABLE = "TUNNEL_TOKEN";
    static final String CONFIG_CONTENT = "no-autoupdate: true\n";
    static final String GRACE_PERIOD = "3s";
    static final Duration STOP_GRACE = Duration.ofSeconds(5);
    static final List<String> WINDOWS_VARIABLES = List.of("SystemRoot", "SYSTEMROOT", "windir");

    private static final String BINARY_PREFIX = "cloudflared-";
    private static final String DELETED_SUFFIX = " (deleted)";

    private final TunnelDirectory directory;
    private final String token;
    private final HostPlatform platform;

    public CloudflaredLauncher(TunnelDirectory directory, String token, HostPlatform platform) {
        this.directory = directory;
        this.token = token;
        this.platform = platform;
    }

    List<String> command(Path binary) {
        return List.of(binary.toString(), "tunnel", "--no-autoupdate", "--config", directory.config().toString(),
            "--metrics", "127.0.0.1:0", "--management-diagnostics=false", "--grace-period", GRACE_PERIOD,
            "--protocol", "auto", "run");
    }

    Process start(Path binary) throws TunnelUnavailableException {
        Process process;
        try {
            Files.writeString(directory.config(), CONFIG_CONTENT, StandardCharsets.UTF_8);
            ProcessBuilder builder = new ProcessBuilder(command(binary)).directory(directory.root().toFile())
                .redirectErrorStream(true);
            minimalEnvironment(builder.environment());
            process = builder.start();
        } catch (IOException e) {
            throw new TunnelUnavailableException("cloudflared could not be started from " + binary + " (" + e.getMessage()
                + "). A data folder mounted noexec cannot run programs", e);
        }
        try {
            process.getOutputStream().close();
            Files.writeString(directory.pidFile(), Long.toString(process.pid()), StandardCharsets.UTF_8);
            return process;
        } catch (IOException | RuntimeException e) {
            stop(process.toHandle());
            throw new TunnelUnavailableException("cloudflared could not record its process in " + directory.pidFile() + " ("
                + e.getMessage() + ")", e);
        }
    }

    void stopLeftover() {
        recordedProcess().filter(ProcessHandle::isAlive).filter(this::runsFromTunnelDirectory).ifPresent(handle -> {
            log.info("Stopping cloudflared {} left over from an earlier start", handle.pid());
            stop(handle);
        });
        stopped();
    }

    void stopped() {
        try {
            Files.deleteIfExists(directory.pidFile());
        } catch (IOException e) {
            log.debug("The cloudflared pid file could not be removed", e);
        }
    }

    static void stop(ProcessHandle process) {
        if (!process.isAlive()) {
            return;
        }
        process.destroy();
        if (awaitExit(process)) {
            return;
        }
        log.info("cloudflared did not stop within {} seconds; stopping it forcibly", STOP_GRACE.toSeconds());
        process.destroyForcibly();
        awaitExit(process);
    }

    private boolean runsFromTunnelDirectory(ProcessHandle handle) {
        Path root = realPath(directory.root());
        return handle.info().command().map(command -> command.endsWith(DELETED_SUFFIX)
                ? command.substring(0, command.length() - DELETED_SUFFIX.length()) : command)
            .map(Path::of)
            .filter(path -> path.getFileName() != null && path.getFileName().toString().startsWith(BINARY_PREFIX))
            .map(path -> path.getParent() != null && realPath(path.getParent()).equals(root))
            .orElse(false);
    }

    private void minimalEnvironment(Map<String, String> environment) {
        Map<String, String> inherited = Map.copyOf(environment);
        environment.clear();
        environment.put(TOKEN_VARIABLE, token);
        environment.put("HOME", directory.root().toString());
        if (platform.isWindows()) {
            WINDOWS_VARIABLES.forEach(name -> Optional.ofNullable(inherited.get(name)).ifPresent(value -> environment.put(name, value)));
        }
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException missing) {
            return path.toAbsolutePath().normalize();
        }
    }

    private Optional<ProcessHandle> recordedProcess() {
        try {
            String recorded = Files.readString(directory.pidFile(), StandardCharsets.UTF_8).trim();
            return ProcessHandle.of(Long.parseLong(recorded));
        } catch (IOException | NumberFormatException missingOrUnreadable) {
            return Optional.empty();
        }
    }

    private static boolean awaitExit(ProcessHandle process) {
        try {
            process.onExit().get(STOP_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return !process.isAlive();
        } catch (ExecutionException | TimeoutException notYet) {
            return !process.isAlive();
        }
    }
}
