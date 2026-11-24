package gg.modl.minecraft.core.util;

import gg.modl.minecraft.core.support.RecordingPluginLogger;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FuturesTest {
    private final RecordingPluginLogger logger = new RecordingPluginLogger();

    @Test
    void theCallbackSeesTheSourceFailureUnchanged() {
        IllegalStateException refused = new IllegalStateException("refused");
        CompletableFuture<String> failed = new CompletableFuture<>();
        failed.completeExceptionally(refused);
        List<Throwable> seen = new ArrayList<>();

        Futures.whenComplete(failed, (result, failure) -> seen.add(failure), logger);

        assertEquals(1, seen.size());
        assertSame(refused, seen.get(0));
        assertTrue(logger.warnings().isEmpty());
    }

    @Test
    void aFailingCallbackIsLoggedOnce() {
        Futures.whenComplete(CompletableFuture.completedFuture("done"), (result, failure) -> {
            throw new IllegalStateException("callback bug");
        }, logger);

        assertEquals(1, logger.warnings().size());
        assertTrue(logger.warnings().get(0).contains("callback bug"), logger.warnings().get(0));
    }

    @Test
    void aBackgroundRequestThatFailsStaysAtDebug() {
        CompletableFuture<String> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("offline"));
        List<String> results = new ArrayList<>();

        Futures.whenSucceeded(failed, results::add, logger);

        assertTrue(results.isEmpty());
        assertTrue(logger.warnings().isEmpty());
        assertEquals(1, logger.debugs().size());
    }

    @Test
    void aFailingActionAfterASuccessIsAWarning() {
        Futures.whenSucceeded(CompletableFuture.completedFuture("done"), result -> {
            throw new IllegalStateException("action bug");
        }, logger);

        assertEquals(1, logger.warnings().size());
        assertTrue(logger.debugs().isEmpty());
    }

    @Test
    void aSuccessfulResultReachesTheAction() {
        List<String> results = new ArrayList<>();

        Futures.whenSucceeded(CompletableFuture.completedFuture("done"), results::add, logger);

        assertEquals(Collections.singletonList("done"), results);
    }
}
