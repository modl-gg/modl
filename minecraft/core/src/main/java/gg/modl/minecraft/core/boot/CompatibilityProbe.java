package gg.modl.minecraft.core.boot;

import gg.modl.minecraft.core.connection.BackendConnections;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.core.util.Scheduling;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class CompatibilityProbe {
    static final long INTERVAL_SECONDS = 30;
    private static final String PATH = "/v2/minecraft/compatibility";
    private static final int TIMEOUT_MILLIS = 10_000;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "modl-compatibility-probe");
        thread.setDaemon(true);
        return thread;
    });

    static CompatibilityProbe start(BackendConnections connections, PluginLogger logger, Runnable onCompatible) {
        CompatibilityProbe probe = new CompatibilityProbe();
        Scheduling.withFixedDelay(probe.executor, () -> {
            try {
                if (accepts(connections)) onCompatible.run();
            } catch (IOException | RuntimeException failure) {
                logger.debug("The modl compatibility probe failed: " + failure);
            }
        }, INTERVAL_SECONDS, INTERVAL_SECONDS, TimeUnit.SECONDS, logger);
        return probe;
    }

    void stop() {
        executor.shutdownNow();
    }

    private static boolean accepts(BackendConnections connections) throws IOException {
        HttpURLConnection connection = connections.open(PATH);
        try {
            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(TIMEOUT_MILLIS);
            return connection.getResponseCode() == HttpURLConnection.HTTP_NO_CONTENT;
        } finally {
            connection.disconnect();
        }
    }
}
