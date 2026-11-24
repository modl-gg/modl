package gg.modl.minecraft.core.boot;

import gg.modl.minecraft.api.http.response.StartupResponse;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.core.util.Scheduling;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntToLongFunction;

final class BackendConnector {
    private static final long[] RETRY_DELAYS_SECONDS = {5, 10, 20, 40, 60};
    static final long MISMATCH_RETRY_MILLIS = TimeUnit.SECONDS.toMillis(60);
    static final IntToLongFunction DEFAULT_RETRY_DELAYS_MILLIS = failedAttempts -> TimeUnit.SECONDS.toMillis(
            RETRY_DELAYS_SECONDS[Math.min(failedAttempts, RETRY_DELAYS_SECONDS.length - 1)]);

    private final StartupCall call;
    private final String rejectedCredentialsRemedy;
    private final PluginLogger logger;
    private final Consumer<StartupResponse> onConnected;
    private final IntToLongFunction retryDelaysMillis;
    private final Runnable onFailureReported;
    private final Consumer<VersionMismatchFailure> onMismatch;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private int failedAttempts;
    private boolean failureReported;

    BackendConnector(StartupCall call, String rejectedCredentialsRemedy, PluginLogger logger,
                     Consumer<StartupResponse> onConnected) {
        this(call, rejectedCredentialsRemedy, logger, onConnected, DEFAULT_RETRY_DELAYS_MILLIS);
    }

    BackendConnector(StartupCall call, String rejectedCredentialsRemedy, PluginLogger logger,
                     Consumer<StartupResponse> onConnected, IntToLongFunction retryDelaysMillis) {
        this(call, rejectedCredentialsRemedy, logger, onConnected, retryDelaysMillis, () -> { });
    }

    BackendConnector(StartupCall call, String rejectedCredentialsRemedy, PluginLogger logger,
                     Consumer<StartupResponse> onConnected, IntToLongFunction retryDelaysMillis,
                     Runnable onFailureReported) {
        this(call, rejectedCredentialsRemedy, logger, onConnected, retryDelaysMillis, onFailureReported, mismatch -> { });
    }

    BackendConnector(StartupCall call, String rejectedCredentialsRemedy, PluginLogger logger,
                     Consumer<StartupResponse> onConnected, IntToLongFunction retryDelaysMillis,
                     Runnable onFailureReported, Consumer<VersionMismatchFailure> onMismatch) {
        this.onMismatch = onMismatch;
        this.call = call;
        this.rejectedCredentialsRemedy = rejectedCredentialsRemedy;
        this.logger = logger;
        this.onConnected = onConnected;
        this.retryDelaysMillis = retryDelaysMillis;
        this.onFailureReported = onFailureReported;
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "modl-backend-connector");
            thread.setDaemon(true);
            return thread;
        });
    }

    static String failureMessage(String serverUrl, long retryDelayMillis, Throwable cause, String rejectedCredentialsRemedy) {
        long retrySeconds = TimeUnit.MILLISECONDS.toSeconds(retryDelayMillis);
        if (cause instanceof CredentialsRejectedException) {
            return "The modl server at " + serverUrl + " rejected the API key, retrying in " + retrySeconds + "s. "
                    + rejectedCredentialsRemedy;
        }
        return "Failed to connect to the modl server at " + serverUrl + ", retrying in " + retrySeconds + "s ("
                + (cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName()) + ")";
    }

    void connect() {
        schedule(0);
    }

    void cancel() {
        if (active.getAndSet(false)) executor.shutdownNow();
    }

    boolean isActive() {
        return active.get();
    }

    private synchronized void scheduleNextAttempt() {
        schedule(retryDelaysMillis.applyAsLong(failedAttempts));
    }

    private synchronized void schedule(long delayMillis) {
        if (!active.get()) return;
        try {
            Scheduling.after(executor, this::attempt, delayMillis, TimeUnit.MILLISECONDS, logger);
        } catch (RejectedExecutionException cancelled) {
            active.set(false);
        }
    }

    private void attempt() {
        if (!active.get()) return;
        StartupResponse response;
        try {
            response = call.call();
        } catch (VersionMismatchFailure mismatch) {
            if (!active.get()) return;
            reportMismatch(mismatch);
            schedule(MISMATCH_RETRY_MILLIS);
            return;
        } catch (IOException | RuntimeException failure) {
            if (!active.get()) return;
            synchronized (this) {
                if (failureReported) failedAttempts++;
            }
            logFailure(failure);
            scheduleNextAttempt();
            return;
        }
        if (!active.compareAndSet(true, false)) return;
        executor.shutdown();
        if (hasReportedFailure()) logger.info("Connected to the modl server at " + call.serverUrl() + ".");
        try {
            onConnected.accept(response);
        } catch (RuntimeException failure) {
            logger.warning("modl failed to start after connecting to the server", failure);
        }
    }

    private void logFailure(Throwable failure) {
        long delay;
        synchronized (this) {
            delay = retryDelaysMillis.applyAsLong(failedAttempts);
            failureReported = true;
        }
        logger.warning(failureMessage(call.serverUrl(), delay, failure, rejectedCredentialsRemedy));
        notifyFailureReported();
    }

    private void reportMismatch(VersionMismatchFailure mismatch) {
        synchronized (this) {
            failureReported = true;
        }
        logger.debug("The modl server refused this plugin version: " + mismatch.reason());
        try {
            onMismatch.accept(mismatch);
        } catch (RuntimeException failure) {
            logger.warning("A version mismatch listener failed", failure);
        }
        notifyFailureReported();
    }

    private void notifyFailureReported() {
        try {
            onFailureReported.run();
        } catch (RuntimeException failure) {
            logger.warning("A connection failure listener failed", failure);
        }
    }

    private synchronized boolean hasReportedFailure() {
        return failureReported;
    }
}
