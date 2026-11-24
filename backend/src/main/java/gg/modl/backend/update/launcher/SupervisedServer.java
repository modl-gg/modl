package gg.modl.backend.update.launcher;

import gg.modl.update.EmbeddedUpdateContract;
import gg.modl.update.UpdateFiles;
import gg.modl.update.UpdatePhase;
import gg.modl.update.UpdateTransaction;
import org.jspecify.annotations.Nullable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class SupervisedServer {
    static final Duration DEADLINE = Duration.ofMinutes(15);
    static final Duration STOP_GRACE = Duration.ofSeconds(30);
    private static final long POLL_MILLIS = 1000;

    private final UpdateFiles files;
    private final String launcherDescriptor;
    private final PrintStream out;
    private volatile @Nullable Process child;
    private volatile boolean stopping;

    SupervisedServer(UpdateFiles files, String launcherDescriptor, PrintStream out) {
        this.files = files;
        this.launcherDescriptor = launcherDescriptor;
        this.out = out;
        Runtime.getRuntime().addShutdownHook(new Thread(this::stopChild, "modl-launcher-stop"));
    }

    boolean stopping() {
        return stopping;
    }

    ChildExit run(List<String> command, boolean deadline) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command)
            .directory(Path.of("").toAbsolutePath().toFile())
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .redirectInput(ProcessBuilder.Redirect.PIPE);
        builder.environment().put(EmbeddedUpdateContract.LAUNCHER_ENVIRONMENT, launcherDescriptor);
        Process process = builder.start();
        child = process;
        long budgetNanos = DEADLINE.toNanos();
        long last = System.nanoTime();
        while (!process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
            long now = System.nanoTime();
            if (deadline && !inBackup()) {
                budgetNanos -= now - last;
            }
            last = now;
            if (deadline && budgetNanos <= 0 && !committedOrGone()) {
                out.println("modl did not start within " + DEADLINE.toMinutes() + " minutes; stopping it");
                stop(process);
                return new ChildExit(process.exitValue(), true);
            }
        }
        return new ChildExit(process.exitValue(), false);
    }

    private boolean inBackup() {
        try {
            UpdateTransaction pending = files.readPending();
            return pending != null && pending.phase() == UpdatePhase.BACKING_UP;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean committedOrGone() {
        try {
            return files.readState().pending() == null;
        } catch (IOException e) {
            return false;
        }
    }

    private void stopChild() {
        stopping = true;
        Process process = child;
        if (process != null && process.isAlive()) {
            stop(process);
        }
    }

    static void stop(Process process) {
        try {
            OutputStream pipe = process.getOutputStream();
            pipe.close();
        } catch (IOException ignored) {
            process.destroy();
        }
        try {
            if (!process.waitFor(STOP_GRACE.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    record ChildExit(int code, boolean timedOut) {
        boolean restartForUpdate() {
            return code == EmbeddedUpdateContract.RESTART_FOR_UPDATE_EXIT_CODE;
        }

        boolean restartRequested() {
            return code == EmbeddedUpdateContract.RESTART_EXIT_CODE;
        }
    }
}
