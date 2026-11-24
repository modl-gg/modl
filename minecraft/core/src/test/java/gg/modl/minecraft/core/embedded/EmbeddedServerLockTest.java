package gg.modl.minecraft.core.embedded;

import gg.modl.minecraft.core.support.RecordingPluginLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddedServerLockTest {
    private static final long WAIT_SECONDS = 10;

    @TempDir
    Path folder;

    private final RecordingPluginLogger logger = new RecordingPluginLogger();

    @Test
    void aSecondHolderInTheSameJvmWaitsUntilTheFirstReleases() throws Exception {
        Path file = folder.resolve("server/server.lock");
        EmbeddedServerLock first = EmbeddedServerLock.acquire(file, () -> false, logger);

        CompletableFuture<EmbeddedServerLock> second = CompletableFuture.supplyAsync(() -> acquire(file, () -> false));
        TimeUnit.MILLISECONDS.sleep(EmbeddedServerLock.RETRY_MILLIS * 3);
        assertFalse(second.isDone());

        first.close();

        second.get(WAIT_SECONDS, TimeUnit.SECONDS).close();
    }

    @Test
    void aWaitingHolderGivesUpWhenItsStartIsCancelled() throws Exception {
        Path file = folder.resolve("server.lock");
        AtomicBoolean cancelled = new AtomicBoolean();
        try (EmbeddedServerLock held = EmbeddedServerLock.acquire(file, () -> false, logger)) {
            CompletableFuture<EmbeddedServerLock> waiting = CompletableFuture.supplyAsync(() -> acquire(file, cancelled::get));

            cancelled.set(true);

            ExecutionException failure = assertThrows(ExecutionException.class, () -> waiting.get(WAIT_SECONDS, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, failure.getCause());
        }
        EmbeddedServerLock.acquire(file, () -> false, logger).close();
    }

    @Test
    void aPreviousStartThatNeverStopsIsReportedOnceTheWaitRunsOut() throws Exception {
        Path file = folder.resolve("server/server.lock");
        try (EmbeddedServerLock held = EmbeddedServerLock.acquire(file, () -> false, logger)) {
            long keptOpenBefore = EmbeddedServerLock.keptOpenChannels();
            EmbeddedServerException failure = assertThrows(EmbeddedServerException.class,
                    () -> EmbeddedServerLock.acquire(file, () -> false, logger, EmbeddedServerLock.RETRY_MILLIS));

            assertTrue(failure.getMessage().startsWith("The built-in panel started before the reload did not stop within"),
                    failure.getMessage());
            assertEquals(keptOpenBefore + 1, EmbeddedServerLock.keptOpenChannels(),
                    "closing the waiting channel would release the previous start's lock");
        }
    }

    private EmbeddedServerLock acquire(Path file, BooleanSupplier cancelled) {
        try {
            return EmbeddedServerLock.acquire(file, cancelled, logger);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
