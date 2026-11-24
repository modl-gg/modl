package gg.modl.backend.database.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.TestDialects;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class EmbeddedReloadTest {
    private static final Duration COLLECTION_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration OVERLAP = Duration.ofSeconds(1);
    private static final Duration SHORT_LOCK_WAIT = Duration.ofMillis(750);
    private static final String RELOAD_SETTINGS = "reload";
    private static final String RELOAD_PASSWORD = "a password hashed before the reload";

    @TempDir
    Path dataDirectory;

    @Test
    void aRowWrittenBeforeAReloadIsReadByTheNextInstanceInTheSameJvm() throws Exception {
        try (IsolatedKernel first = IsolatedKernel.create()) {
            first.start(dataDirectory);
            first.writeSettings(RELOAD_SETTINGS);
            System.out.println("MEASURE isolated close after a write: " + timed(first::stop).toMillis() + " ms");
        }
        try (IsolatedKernel second = IsolatedKernel.create()) {
            second.start(dataDirectory);
            assertTrue(second.hasSettings(RELOAD_SETTINGS));
            System.out.println("MEASURE isolated close after a read: " + timed(second::stop).toMillis() + " ms");
        }
    }

    @Test
    void afterCloseTheOldLoaderHasNoRegisteredDriverAndIsCollected() throws Exception {
        IsolatedKernel kernel = IsolatedKernel.create();
        kernel.start(dataDirectory);
        kernel.writeSettings(RELOAD_SETTINGS);
        assertTrue(kernel.registeredDrivers() > 0);

        kernel.stop();

        assertEquals(0, kernel.registeredDrivers());
        WeakReference<ClassLoader> loader = kernel.abandon();
        Duration collected = awaitCollected(loader);
        System.out.println("MEASURE old loader collected after " + collected.toMillis() + " ms");
    }

    @Test
    void aPasswordHashedBeforeCloseDoesNotKeepTheOldLoaderAlive() throws Exception {
        IsolatedKernel kernel = IsolatedKernel.create();
        kernel.start(dataDirectory);
        assertTrue(kernel.hashPassword(RELOAD_PASSWORD));

        kernel.stop();

        WeakReference<ClassLoader> loader = kernel.abandon();
        Duration collected = awaitCollected(loader);
        System.out.println("MEASURE old loader collected after a password hash in " + collected.toMillis() + " ms");
    }

    @ParameterizedTest(name = "{0}", allowZeroInvocations = true)
    @MethodSource("gg.modl.backend.support.sql.TestDialects#external")
    void afterCloseOnAnExternalDatabaseTheOldLoaderIsCollected(DialectTarget target) throws Exception {
        SqlDatabaseProperties database = TestDialects.freshDatabase(target);
        IsolatedKernel kernel = IsolatedKernel.create();
        kernel.startExternal(dataDirectory, target, database);
        kernel.writeSettings(RELOAD_SETTINGS);

        kernel.stop();

        assertEquals(0, kernel.registeredDrivers());
        WeakReference<ClassLoader> loader = kernel.abandon();
        Duration collected = awaitCollected(loader);
        System.out.println("MEASURE old loader on " + target + " collected after " + collected.toMillis() + " ms");
    }

    @Test
    void anOverlappingStartWaitsForThePreviousInstanceToReleaseTheDatabase() throws Exception {
        try (IsolatedKernel previous = IsolatedKernel.create(); IsolatedKernel next = IsolatedKernel.create()) {
            previous.start(dataDirectory);
            previous.writeSettings(RELOAD_SETTINGS);
            long started = System.nanoTime();
            CompletableFuture<Void> nextStart = CompletableFuture.runAsync(() -> unchecked(() -> next.start(dataDirectory)));
            Thread.sleep(OVERLAP);
            assertFalse(nextStart.isDone(), "the next instance started beside a running one");

            previous.stop();
            nextStart.join();

            Duration waited = Duration.ofNanos(System.nanoTime() - started);
            System.out.println("MEASURE overlapping start waited " + waited.toMillis() + " ms");
            assertTrue(waited.compareTo(FileInstanceLock.WAIT_BUDGET) < 0);
            assertTrue(next.hasSettings(RELOAD_SETTINGS));
            next.stop();
        }
    }

    @Test
    void aConnectionHeldAcrossCloseKeepsTheLockSoTheNextAcquireFailsClosedAfterItsWait() throws Exception {
        try (IsolatedKernel stuck = IsolatedKernel.create()) {
            stuck.start(dataDirectory);
            stuck.holdConnection();
            stuck.stop();
            assertNotNull(System.getProperty(marker()));

            long started = System.nanoTime();
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> FileInstanceLock.acquire(
                dataDirectory.resolve("database"), new LockRelease(new ConnectionTracker(), ClassLoader.getPlatformClassLoader()),
                SHORT_LOCK_WAIT));
            Duration waited = Duration.ofNanos(System.nanoTime() - started);

            assertTrue(failure.getMessage().contains("has not released its database"), failure.getMessage());
            assertTrue(waited.compareTo(SHORT_LOCK_WAIT) >= 0, waited.toString());
            assertNotNull(System.getProperty(marker()));
            assertOtherProcessesAreLockedOut();
            stuck.releaseHeldConnection();
        }
    }

    private void assertOtherProcessesAreLockedOut() throws InterruptedException {
        try (ChildJvm probe = ChildJvm.start(InstanceHolderProcess.class, dataDirectory.toString())) {
            String outcome = probe.awaitLineStartingWith(InstanceHolderProcess.READY, InstanceHolderProcess.FAILED);
            assertTrue(outcome.contains("Another process is using"), outcome);
        }
    }

    private String marker() {
        return FileInstanceLock.markerName(FileInstanceLock.lockFile(dataDirectory.resolve("database")));
    }

    private static Duration awaitCollected(WeakReference<ClassLoader> loader) throws InterruptedException {
        long started = System.nanoTime();
        while (loader.get() != null) {
            if (System.nanoTime() - started >= COLLECTION_TIMEOUT.toNanos()) {
                break;
            }
            System.gc();
            Thread.sleep(100);
        }
        assertNull(loader.get(), "the old server class loader is still reachable after " + COLLECTION_TIMEOUT);
        return Duration.ofNanos(System.nanoTime() - started);
    }

    private static Duration timed(KernelAction action) throws Exception {
        long started = System.nanoTime();
        action.run();
        return Duration.ofNanos(System.nanoTime() - started);
    }

    private static void unchecked(KernelAction action) {
        try {
            action.run();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @FunctionalInterface
    private interface KernelAction {
        void run() throws Exception;
    }
}
