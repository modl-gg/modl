package gg.modl.minecraft.core.embedded;

import gg.modl.minecraft.core.connection.BackendEndpoint;
import gg.modl.minecraft.core.util.PluginLogger;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class EmbeddedServerHandle {
    static final String PUBLIC_URL = "publicUrl";
    private static final long CLOSE_TIMEOUT_SECONDS = 15;

    private final AutoCloseable server;
    private final URLClassLoader classLoader;
    private final EmbeddedServerLock lock;
    private final BackendEndpoint endpoint;
    private final PluginLogger logger;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final CountDownLatch released = new CountDownLatch(1);

    private EmbeddedServerHandle(AutoCloseable server, URLClassLoader classLoader, EmbeddedServerLock lock,
                                 BackendEndpoint endpoint, PluginLogger logger) {
        this.server = server;
        this.classLoader = classLoader;
        this.lock = lock;
        this.endpoint = endpoint;
        this.logger = logger;
    }

    static EmbeddedServerHandle start(List<Path> jars, EmbeddedServerLock lock, BackendEndpoint endpoint, PluginLogger logger,
                                      ServerEntrypoint entrypoint) throws Exception {
        URLClassLoader classLoader = IsolatedClassLoaderFactory.create(jars);
        AutoCloseable server;
        try {
            server = runInContextOf(classLoader, entrypoint);
        } catch (Throwable failure) {
            closeClassLoader(classLoader, logger);
            throw failure;
        }
        return new EmbeddedServerHandle(server, classLoader, lock, endpoint, logger);
    }

    private static AutoCloseable runInContextOf(URLClassLoader classLoader, ServerEntrypoint entrypoint) throws Exception {
        Thread current = Thread.currentThread();
        ClassLoader previousContextLoader = current.getContextClassLoader();
        current.setContextClassLoader(classLoader);
        try {
            return entrypoint.start(classLoader);
        } finally {
            current.setContextClassLoader(previousContextLoader);
        }
    }

    public BackendEndpoint endpoint() {
        return endpoint;
    }

    public String publicUrl() {
        return detail(PUBLIC_URL);
    }

    public boolean close() {
        if (closed.compareAndSet(false, true)) {
            Thread stopper = new Thread(this::stop, "modl-embedded-server-stop");
            stopper.setDaemon(true);
            stopper.setContextClassLoader(classLoader);
            stopper.start();
        }
        return awaitStopped();
    }

    private boolean awaitStopped() {
        boolean stopped = false;
        try {
            stopped = released.await(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!stopped) logger.warning("The built-in panel did not stop within " + CLOSE_TIMEOUT_SECONDS + " seconds.");
        return stopped;
    }

    private void stop() {
        try {
            closeServer();
            closeClassLoader(classLoader, logger);
        } finally {
            lock.close();
            released.countDown();
        }
    }

    private void closeServer() {
        try {
            server.close();
        } catch (Exception e) {
            logger.warning("The built-in panel did not stop cleanly: " + e.getMessage());
        }
    }

    private static void closeClassLoader(URLClassLoader classLoader, PluginLogger logger) {
        try {
            classLoader.close();
        } catch (IOException e) {
            logger.warning("Could not release the built-in panel libraries: " + e.getMessage());
        }
    }

    private String detail(String key) {
        String value = details().get(key);
        return value == null ? "" : value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> details() {
        if (closed.get() || !(server instanceof Supplier)) return Collections.emptyMap();
        Object details = ((Supplier<?>) server).get();
        return details instanceof Map ? (Map<String, String>) details : Collections.<String, String>emptyMap();
    }
}
