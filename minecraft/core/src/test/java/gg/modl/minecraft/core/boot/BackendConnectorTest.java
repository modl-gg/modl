package gg.modl.minecraft.core.boot;

import gg.modl.minecraft.api.http.response.StartupResponse;
import gg.modl.minecraft.core.support.RecordingPluginLogger;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendConnectorTest {
    private static final String SERVER_URL = "http://127.0.0.1:8080";
    private static final String REMEDY = "Check remote.api-key in connection.yml.";

    @Test
    void backsOffThenRepeatsTheLongestDelay() {
        long[] expectedSeconds = {5, 10, 20, 40, 60, 60, 60};
        for (int failedAttempts = 0; failedAttempts < expectedSeconds.length; failedAttempts++) {
            assertEquals(TimeUnit.SECONDS.toMillis(expectedSeconds[failedAttempts]),
                    BackendConnector.DEFAULT_RETRY_DELAYS_MILLIS.applyAsLong(failedAttempts));
        }
    }

    @Test
    void failureMessageNamesTheServerAndTheRetryDelay() {
        assertEquals("Failed to connect to the modl server at http://127.0.0.1:8080, retrying in 10s (Connection refused)",
                BackendConnector.failureMessage(SERVER_URL, 10_000, new IOException("Connection refused"), REMEDY));
    }

    @Test
    void rejectedCredentialsAreReportedAsARejectedApiKey() {
        String message = BackendConnector.failureMessage(SERVER_URL, 5_000,
                new CredentialsRejectedException(HttpStatusException.withBody(401, "")), REMEDY);

        assertEquals("The modl server at http://127.0.0.1:8080 rejected the API key, retrying in 5s. " + REMEDY, message);
    }

    @Test
    void retriesUntilTheServerAnswersThenConnectsExactlyOnce() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger connections = new AtomicInteger();
        CountDownLatch connected = new CountDownLatch(1);
        StartupCall call = new FakeStartupCall(() -> {
            if (attempts.incrementAndGet() < 3) throw new IOException("Connection refused");
            return new StartupResponse();
        });
        RecordingPluginLogger logger = new RecordingPluginLogger();
        BackendConnector connector = new BackendConnector(call, REMEDY, logger, response -> {
            connections.incrementAndGet();
            connected.countDown();
        }, failures -> 0);

        connector.connect();

        assertTrue(connected.await(5, TimeUnit.SECONDS));
        assertEquals(3, attempts.get());
        assertEquals(1, connections.get());
        assertFalse(connector.isActive());
        assertEquals(2, logger.warnings().size());
        assertTrue(logger.infos().contains("Connected to the modl server at " + SERVER_URL + "."));
    }

    @Test
    void anImmediateConnectionThatSucceedsAtOnceLogsNothing() throws Exception {
        CountDownLatch connected = new CountDownLatch(1);
        RecordingPluginLogger logger = new RecordingPluginLogger();
        BackendConnector connector = new BackendConnector(new FakeStartupCall(StartupResponse::new), REMEDY, logger,
                response -> connected.countDown(), BackendConnector.DEFAULT_RETRY_DELAYS_MILLIS);

        connector.connect();

        assertTrue(connected.await(5, TimeUnit.SECONDS));
        assertTrue(logger.infos().isEmpty(), logger.infos().toString());
        assertTrue(logger.warnings().isEmpty(), logger.warnings().toString());
    }

    @Test
    void anImmediateConnectionThatFailsRetriesFromTheShortestDelay() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        List<Integer> delaysAsked = new CopyOnWriteArrayList<>();
        CountDownLatch connected = new CountDownLatch(1);
        RecordingPluginLogger logger = new RecordingPluginLogger();
        BackendConnector connector = new BackendConnector(new FakeStartupCall(() -> {
            if (attempts.incrementAndGet() < 3) throw new IOException("Connection refused");
            return new StartupResponse();
        }), REMEDY, logger, response -> connected.countDown(), failures -> {
            delaysAsked.add(failures);
            return 0;
        });

        connector.connect();

        assertTrue(connected.await(5, TimeUnit.SECONDS));
        assertEquals(Arrays.asList(0, 0, 1, 1), delaysAsked);
        assertEquals(2, logger.warnings().size());
        assertTrue(logger.infos().contains("Connected to the modl server at " + SERVER_URL + "."));
    }

    @Test
    void theFailureListenerRunsAfterEveryReportedFailure() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch connected = new CountDownLatch(1);
        RecordingPluginLogger logger = new RecordingPluginLogger();
        List<Integer> warningsSeenByListener = new CopyOnWriteArrayList<>();
        BackendConnector connector = new BackendConnector(new FakeStartupCall(() -> {
            if (attempts.incrementAndGet() < 3) throw new IOException("Connection refused");
            return new StartupResponse();
        }), REMEDY, logger, response -> connected.countDown(), failures -> 0,
                () -> warningsSeenByListener.add(logger.warnings().size()));

        connector.connect();

        assertTrue(connected.await(5, TimeUnit.SECONDS));
        assertEquals(Arrays.asList(1, 2), warningsSeenByListener);
    }

    @Test
    void aCancelledConnectorNeverReportsAConnectionThatCompletesAfterwards() throws Exception {
        CountDownLatch attemptStarted = new CountDownLatch(1);
        CountDownLatch releaseAttempt = new CountDownLatch(1);
        AtomicInteger connections = new AtomicInteger();
        StartupCall call = new FakeStartupCall(() -> {
            attemptStarted.countDown();
            awaitUninterruptibly(releaseAttempt);
            return new StartupResponse();
        });
        BackendConnector connector = new BackendConnector(call, REMEDY, new RecordingPluginLogger(),
                response -> connections.incrementAndGet(), failures -> 0);

        connector.connect();
        assertTrue(attemptStarted.await(5, TimeUnit.SECONDS));
        connector.cancel();
        releaseAttempt.countDown();

        TimeUnit.MILLISECONDS.sleep(200);
        assertEquals(0, connections.get());
        assertFalse(connector.isActive());
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private interface Attempt {
        StartupResponse run() throws IOException;
    }

    private static final class FakeStartupCall implements StartupCall {
        private final Attempt attempt;

        private FakeStartupCall(Attempt attempt) {
            this.attempt = attempt;
        }

        @Override
        public String serverUrl() {
            return SERVER_URL;
        }

        @Override
        public StartupResponse call() throws IOException {
            return attempt.run();
        }
    }
}
