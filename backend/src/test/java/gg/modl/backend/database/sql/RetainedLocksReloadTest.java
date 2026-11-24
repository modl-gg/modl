package gg.modl.backend.database.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetainedLocksReloadTest {
    private static final int COLLECTION_ROUNDS = 20;
    private static final Duration CLEANER_PAUSE = Duration.ofMillis(100);

    @TempDir
    Path directory;

    @Test
    void aChannelRetainedByAnAbandonedLoaderNeverReleasesThePreviousInstancesLock() throws Exception {
        Path file = directory.resolve(FileInstanceLock.LOCK_FILE_NAME);
        try (FileChannel previous = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock held = previous.lock()) {
            retainInAnAbandonedLoader(file);
            collectGarbage();

            try (ChildJvm probe = ChildJvm.start(LockProbeProcess.class, file.toString())) {
                assertEquals(LockProbeProcess.HELD,
                    probe.awaitLineStartingWith(LockProbeProcess.HELD, LockProbeProcess.FREE),
                    "another process could take the lock the previous instance still relies on");
            }
        }
    }

    private static WeakReference<ClassLoader> retainInAnAbandonedLoader(Path file) throws Exception {
        try (URLClassLoader loader = IsolatedLoaders.create()) {
            FileChannel waiting = FileChannel.open(file, StandardOpenOption.WRITE);
            IsolatedLoaders.call(loader, () -> {
                Method retain = Class.forName(RetainedLocks.class.getName(), true, loader).getDeclaredMethod("retain", Object.class);
                retain.setAccessible(true);
                return retain.invoke(null, waiting);
            });
            return new WeakReference<>(loader);
        }
    }

    private static void collectGarbage() throws InterruptedException {
        for (int round = 0; round < COLLECTION_ROUNDS; round++) {
            System.gc();
            Thread.sleep(CLEANER_PAUSE);
        }
    }
}
