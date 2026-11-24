package gg.modl.minecraft.core.embedded;

import gg.modl.minecraft.core.support.RecordingPluginLogger;
import gg.modl.minecraft.core.util.PluginLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddedServerLockReloadTest {
    private static final int COLLECTION_ROUNDS = 20;
    private static final long CLEANER_PAUSE_MILLIS = 100;
    private static final long PROBE_SECONDS = 30;

    @TempDir
    Path folder;

    @Test
    void theChannelKeptByAReloadedPluginNeverReleasesThePreviousStartsLock() throws Exception {
        Path file = folder.resolve("server.lock");
        try (EmbeddedServerLock previous = EmbeddedServerLock.acquire(file, () -> false, new RecordingPluginLogger())) {
            giveUpInAnAbandonedLoader(file);
            for (int round = 0; round < COLLECTION_ROUNDS; round++) {
                System.gc();
                TimeUnit.MILLISECONDS.sleep(CLEANER_PAUSE_MILLIS);
            }

            assertEquals(LockProbeProcess.HELD, probe(file), "another process could take the previous start's lock");
        }
    }

    private static void giveUpInAnAbandonedLoader(Path file) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(classPath(), ClassLoader.getSystemClassLoader().getParent())) {
            Class<?> lock = Class.forName(EmbeddedServerLock.class.getName(), true, loader);
            Class<?> logger = Class.forName(PluginLogger.class.getName(), false, loader);
            Method acquire = lock.getDeclaredMethod("acquire", Path.class, BooleanSupplier.class, logger, long.class);
            acquire.setAccessible(true);
            BooleanSupplier neverCancelled = () -> false;

            assertThrows(InvocationTargetException.class,
                    () -> acquire.invoke(null, file, neverCancelled, null, EmbeddedServerLock.RETRY_MILLIS));
        }
    }

    private static int probe(Path file) throws Exception {
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                LockProbeProcess.class.getName(), file.toString()).start();
        assertTrue(process.waitFor(PROBE_SECONDS, TimeUnit.SECONDS), "the lock probe did not finish");
        return process.exitValue();
    }

    private static URL[] classPath() throws Exception {
        List<URL> urls = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (!entry.isEmpty()) urls.add(Paths.get(entry).toUri().toURL());
        }
        return urls.toArray(new URL[0]);
    }
}
