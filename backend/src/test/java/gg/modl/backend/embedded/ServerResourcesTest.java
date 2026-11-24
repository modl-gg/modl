package gg.modl.backend.embedded;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.net.URLClassLoader;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class ServerResourcesTest {
    @Test
    void waitsForServerThreadsAndReportsTheOnesThatKeepRunning() throws Exception {
        try (URLClassLoader serverLoader = new URLClassLoader(new URL[0], null)) {
            CountDownLatch release = new CountDownLatch(1);
            Thread finishing = serverThread(serverLoader, "finishing", () -> sleep(50));
            Thread stuck = serverThread(serverLoader, "stuck", () -> awaitQuietly(release));
            Thread exempt = serverThread(serverLoader, "exempt", () -> awaitQuietly(release));

            List<Thread> survivors = ServerResources.awaitServerThreads(serverLoader, Duration.ofMillis(500), Set.of("exempt"));

            release.countDown();
            assertThat(survivors).containsExactly(stuck);
            assertThat(finishing.isAlive()).isFalse();
            stuck.join();
            exempt.join();
        }
    }

    private static Thread serverThread(ClassLoader loader, String name, Runnable body) {
        Thread thread = new Thread(body, name);
        thread.setContextClassLoader(loader);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
