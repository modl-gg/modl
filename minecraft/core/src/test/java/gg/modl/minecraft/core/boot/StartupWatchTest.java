package gg.modl.minecraft.core.boot;

import gg.modl.minecraft.core.embedded.EmbeddedServerException;
import gg.modl.minecraft.core.embedded.StartupProgress;
import gg.modl.minecraft.core.support.RecordingPluginLogger;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupWatchTest {
    private static final long SECONDS_PER_CLOCK_READ = 10;
    private static final long POLL_MILLIS = 1;
    private static final int MAX_CHECKS = 1000;

    private final AtomicLong now = new AtomicLong();
    private final StartupProgress progress = new StartupProgress(() -> now.addAndGet(TimeUnit.SECONDS.toNanos(SECONDS_PER_CLOCK_READ)));
    private final RecordingPluginLogger logger = new RecordingPluginLogger();
    private final StartupWatch watch = new StartupWatch(progress, logger, Paths.get("plugins/modl/server/logs/server.log"), POLL_MILLIS);

    @Test
    void aStartThatMakesNoProgressFailsAfterTheStallTimeoutAndSaysWhereTheDetailsAre() {
        StartupWatch.Watch<String> stalled = watch.begin(new CompletableFuture<>());

        checkUntilSettled(stalled);

        assertEquals("The built-in panel made no startup progress for 180 seconds. Details are in "
                + Paths.get("plugins/modl/server/logs/server.log") + ".", failureOf(stalled.result()).getMessage());
        assertEquals(Arrays.asList(
                "Waiting for the built-in panel to start (15s)...",
                "Waiting for the built-in panel to start (30s)...",
                "Waiting for the built-in panel to start (45s)...",
                "Waiting for the built-in panel to start (60s)...",
                "Waiting for the built-in panel to start (75s)...",
                "Waiting for the built-in panel to start (90s)...",
                "Waiting for the built-in panel to start (105s)...",
                "Waiting for the built-in panel to start (120s)...",
                "Waiting for the built-in panel to start (135s)...",
                "Waiting for the built-in panel to start (150s)...",
                "Waiting for the built-in panel to start (165s)..."
        ), logger.infos());
    }

    @Test
    void aStartThatKeepsMakingProgressWaitsFarLongerThanTheStallTimeoutWithoutWaitingNotices() {
        CompletableFuture<String> download = new CompletableFuture<>();
        StartupWatch.Watch<String> watched = watch.begin(download);

        for (int check = 0; check < 60; check++) {
            progress.advance();
            watched.check();
        }
        download.complete("started");

        assertEquals("started", watched.result().join());
        assertTrue(logger.infos().isEmpty(), logger.infos().toString());
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(now.get()) > StartupProgress.STALL_TIMEOUT_SECONDS * 2);
    }

    @Test
    void timeThatPassedBeforeAnyoneWaitedDoesNotCountAsAStall() {
        now.addAndGet(TimeUnit.SECONDS.toNanos(StartupProgress.STALL_TIMEOUT_SECONDS * 5));
        CompletableFuture<String> launch = new CompletableFuture<>();
        StartupWatch.Watch<String> watched = watch.begin(launch);

        watched.check();
        watched.check();
        launch.complete("started");

        assertEquals("started", watched.result().join());
    }

    @Test
    void anUpdateWaitsLongerAndSaysHowToRecoverWhenItStalls() {
        StartupWatch.Watch<String> updating = watch.begin(new CompletableFuture<>());
        progress.reportPhase("MIGRATING");

        checkUntilSettled(updating);

        String failure = failureOf(updating.result()).getMessage();
        assertTrue(failure.contains(StartupProgress.UPDATE_STALL_TIMEOUT_SECONDS + " seconds while it was updating"), failure);
        assertTrue(failure.contains("Restart the Minecraft server"), failure);
    }

    @Test
    void aFailedStartSurfacesItsOwnMessage() {
        CompletableFuture<String> failed = new CompletableFuture<>();
        failed.completeExceptionally(new EmbeddedServerException("Port 8080 is already in use."));

        assertEquals("Port 8080 is already in use.", failureOf(watch.begin(failed).result()).getMessage());
    }

    @Test
    void aCancelledStartIsReportedAsCancelled() {
        CompletableFuture<String> cancelled = new CompletableFuture<>();
        cancelled.cancel(false);

        EmbeddedServerException failure = failureOf(watch.begin(cancelled).result());

        assertTrue(failure.getMessage().contains("cancelled"), failure.getMessage());
    }

    @Test
    void theTickerReportsAStallWithoutAnyoneWaitingOnIt() throws Exception {
        CompletableFuture<String> watched = watch.watch(new CompletableFuture<>());

        ExecutionException stalled = assertThrows(ExecutionException.class, () -> watched.get(10, TimeUnit.SECONDS));

        assertInstanceOf(EmbeddedServerException.class, stalled.getCause());
        assertTrue(stalled.getCause().getMessage().contains("no startup progress"), stalled.getCause().getMessage());
    }

    @Test
    void aSettledWatchStopsChecking() {
        CompletableFuture<String> launch = new CompletableFuture<>();
        StartupWatch.Watch<String> watched = watch.begin(launch);
        launch.complete("started");
        long clockBefore = now.get();

        watched.check();

        assertEquals(clockBefore, now.get());
        assertFalse(watched.result().isCompletedExceptionally());
    }

    private static void checkUntilSettled(StartupWatch.Watch<?> watched) {
        for (int check = 0; check < MAX_CHECKS && !watched.result().isDone(); check++) watched.check();
        assertTrue(watched.result().isDone());
    }

    private static EmbeddedServerException failureOf(CompletableFuture<?> result) {
        ExecutionException failure = assertThrows(ExecutionException.class, result::get);
        return assertInstanceOf(EmbeddedServerException.class, failure.getCause());
    }
}
