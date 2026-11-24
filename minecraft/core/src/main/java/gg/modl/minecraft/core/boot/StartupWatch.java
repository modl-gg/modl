package gg.modl.minecraft.core.boot;

import gg.modl.minecraft.core.embedded.EmbeddedServerException;
import gg.modl.minecraft.core.embedded.StartupProgress;
import gg.modl.minecraft.core.util.Futures;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.core.util.Scheduling;

import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class StartupWatch {
    static final long NOTICE_INTERVAL_SECONDS = 15;
    private static final long POLL_MILLIS = 1000;

    private final StartupProgress progress;
    private final PluginLogger logger;
    private final Path serverLog;
    private final long pollMillis;

    StartupWatch(StartupProgress progress, PluginLogger logger, Path serverLog) {
        this(progress, logger, serverLog, POLL_MILLIS);
    }

    StartupWatch(StartupProgress progress, PluginLogger logger, Path serverLog, long pollMillis) {
        this.progress = progress;
        this.logger = logger;
        this.serverLog = serverLog;
        this.pollMillis = pollMillis;
    }

    <T> CompletableFuture<T> watch(CompletableFuture<T> pending) {
        return watch(pending, reason -> { });
    }

    <T> CompletableFuture<T> watch(CompletableFuture<T> pending, Consumer<String> onStall) {
        Watch<T> watch = begin(pending, onStall);
        ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "modl-startup-watch");
            thread.setDaemon(true);
            return thread;
        });
        Scheduling.withFixedDelay(ticker, watch::check, pollMillis, pollMillis, TimeUnit.MILLISECONDS, logger);
        Futures.whenComplete(watch.result(), (value, failure) -> ticker.shutdown(), logger);
        return watch.result();
    }

    <T> Watch<T> begin(CompletableFuture<T> pending) {
        return begin(pending, reason -> { });
    }

    <T> Watch<T> begin(CompletableFuture<T> pending, Consumer<String> onStall) {
        Watch<T> watch = new Watch<>(progress.nowNanos(), onStall);
        Futures.whenComplete(pending, watch::settle, logger);
        return watch;
    }

    final class Watch<T> {
        private final CompletableFuture<T> result = new CompletableFuture<>();
        private final long startNanos;
        private final Consumer<String> onStall;
        private long noticedSteps;

        private Watch(long startNanos, Consumer<String> onStall) {
            this.startNanos = startNanos;
            this.onStall = onStall;
        }

        CompletableFuture<T> result() {
            return result;
        }

        void check() {
            if (result.isDone()) return;
            long idleSeconds = TimeUnit.NANOSECONDS.toSeconds(progress.idleNanosSince(startNanos));
            long limitSeconds = progress.stallTimeoutSeconds();
            if (idleSeconds >= limitSeconds) {
                String reason = stalled(limitSeconds);
                result.completeExceptionally(new EmbeddedServerException(reason));
                try {
                    onStall.accept(reason);
                } catch (RuntimeException failure) {
                    logger.warning("Could not abandon the stalled start of the built-in panel", failure);
                }
                return;
            }
            long steps = idleSeconds / NOTICE_INTERVAL_SECONDS;
            if (steps > noticedSteps) {
                logger.info("Waiting for the built-in panel to start (" + steps * NOTICE_INTERVAL_SECONDS + "s)...");
            }
            noticedSteps = steps;
        }

        private String stalled(long limitSeconds) {
            String details = " Details are in " + serverLog + ".";
            if (!progress.updating()) {
                return "The built-in panel made no startup progress for " + limitSeconds + " seconds." + details;
            }
            return "The built-in panel made no progress for " + limitSeconds + " seconds while it was updating. Restart the"
                    + " Minecraft server to finish or roll back the update." + details;
        }

        private void settle(T value, Throwable failure) {
            if (failure == null) result.complete(value);
            else result.completeExceptionally(startupFailure(failure));
        }
    }

    private static EmbeddedServerException startupFailure(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        if (cause instanceof EmbeddedServerException) return (EmbeddedServerException) cause;
        if (cause instanceof CancellationException) return new EmbeddedServerException("The built-in panel start was cancelled");
        return new EmbeddedServerException("The built-in panel failed to start: " + cause, cause);
    }
}
