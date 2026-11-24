package gg.modl.minecraft.core.impl.http;

import gg.modl.minecraft.core.connection.BackendConnections;
import gg.modl.minecraft.core.login.LoginBudget;
import gg.modl.minecraft.api.http.ApiClientException;
import gg.modl.minecraft.api.http.PanelUnavailableException;
import gg.modl.minecraft.core.util.Java8Collections;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

abstract class AbstractModlHttpTransport {
    protected static final String HEADER_CONTENT_TYPE = "Content-Type", HEADER_ACTING_STAFF_ID = "X-Acting-Staff-Id";
    static final int BACKGROUND_LANE_THREADS = 8, BACKGROUND_LANE_QUEUE = 4096,
            LOGIN_LANE_THREADS = 16, LOGIN_LANE_QUEUE = 1024;
    protected static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10), LOGIN_TIMEOUT = LoginBudget.DURATION,
        SYNC_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration MAX_LOGIN_BUSY_WAIT = Duration.ofSeconds(2);
    protected static final int STATUS_UNREACHABLE = -1;
    private static final long EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 5L;
    private static final byte[] EMPTY_BODY = new byte[0];

    protected final BackendConnections connections;
    protected final String basePath;
    protected final Logger logger;
    protected final RequestLane backgroundLane;
    protected final RequestLane loginLane;
    protected final boolean debugMode;
    private final String versionTag;

    protected AbstractModlHttpTransport(BackendConnections connections, String basePath,
                                        boolean debugMode, String versionTag, String threadNamePrefix,
                                        LongSupplier loginClock) {
        this.connections = connections;
        this.basePath = basePath;
        this.debugMode = debugMode;
        this.versionTag = versionTag;
        this.backgroundLane = RequestLane.unbounded(threadNamePrefix + "bg-", BACKGROUND_LANE_THREADS,
            BACKGROUND_LANE_QUEUE, BusyRetry.NEVER);
        this.loginLane = RequestLane.withBudget(threadNamePrefix + "login-", LOGIN_LANE_THREADS, LOGIN_LANE_QUEUE,
            BusyRetry.onceWaitingAtMost(MAX_LOGIN_BUSY_WAIT), LoginBudget.DURATION, loginClock);

        this.logger = Logger.getLogger(getClass().getName());
    }

    private void logFailedResponse(String requestId, HttpRequest request, int statusCode, String message) {
        String prefix = "[REQ-" + requestId + "] ";
        if (statusCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
            logger.severe(prefix + "Authentication failed - check the API key");
        } else if (statusCode == HttpURLConnection.HTTP_BAD_METHOD) {
            logger.severe(prefix + "HTTP 405 - " + request.method + " " + request.url + ": " + message);
        } else if (debugMode) {
            logger.info(prefix + "HTTP " + statusCode + " - " + request.method + " " + request.url + ": " + message);
        }
    }

    public void shutdown() {
        loginLane.shutdown(EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS);
        backgroundLane.shutdown(EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS);
    }

    protected final HttpRequest newRequest(String path, String method, byte @Nullable [] body,
                                           @Nullable Duration timeout, Map<String, String> headers) {
        return new HttpRequest(path, connections.resolve(path), method, body, timeout, headers);
    }

    protected final String generateRequestId() {
        return versionTag + "-" + (System.nanoTime() % 1000000);
    }

    protected final <R> CompletableFuture<R> execute(HttpRequest request, @Nullable String operation, RequestLane lane,
                                                     ResponseDecoder<R> decoder) {
        final Instant startTime = Instant.now();
        final String requestId = generateRequestId();
        final RequestDeadline deadline = lane.startDeadline();

        if (!lane.breaker().allowRequest()) {
            return Java8Collections.failedFuture(new PanelUnavailableException(request.url,
                HttpURLConnection.HTTP_UNAVAILABLE, versionTag + " API is temporarily unavailable (circuit breaker open)"));
        }

        if (debugMode) logRequest(requestId, request);

        final CompletableFuture<R> pending;
        try {
            pending = retryWhenBusy(
                submit(request, operation, lane, lane.busyRetry(), deadline, decoder, startTime, requestId),
                () -> resubmit(request, operation, lane, deadline, decoder, requestId));
        } catch (RejectedExecutionException rejected) {
            lane.breaker().releaseProbe();
            return Java8Collections.failedFuture(rejectedByExecutor(request));
        }

        return pending.exceptionally(throwable -> {
            Throwable cause = unwrapOnce(throwable);
            if (cause instanceof QueuedRequestExpiredException) lane.breaker().releaseProbe();
            else if (cause instanceof ApiClientException) lane.breaker().recordSuccess();
            else lane.breaker().recordFailure();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(versionTag + " HTTP request failed", throwable);
        });
    }

    private <R> CompletableFuture<R> retryWhenBusy(CompletableFuture<R> first, Supplier<CompletableFuture<R>> retry) {
        CompletableFuture<R> settled = new CompletableFuture<>();
        first.thenAccept(settled::complete).exceptionally(failure -> {
            Throwable cause = unwrapOnce(failure);
            if (!(cause instanceof BusyResponseException)) {
                settled.completeExceptionally(cause);
                return null;
            }
            try {
                long delayMillis = ((BusyResponseException) cause).getRetryDelay().toMillis();
                relay(Java8Collections.delay(delayMillis, TimeUnit.MILLISECONDS).thenCompose(elapsed -> retry.get()), settled);
            } catch (RuntimeException rejected) {
                settled.completeExceptionally(rejected);
            }
            return null;
        });
        return settled;
    }

    private static <R> void relay(CompletableFuture<R> source, CompletableFuture<R> target) {
        source.thenAccept(target::complete).exceptionally(failure -> {
            target.completeExceptionally(unwrapOnce(failure));
            return null;
        });
    }

    private static Throwable unwrapOnce(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
    }

    private <R> CompletableFuture<R> resubmit(HttpRequest request, @Nullable String operation, RequestLane lane,
                                              RequestDeadline deadline, ResponseDecoder<R> decoder, String requestId) {
        try {
            return submit(request, operation, lane, BusyRetry.NEVER, deadline, decoder, Instant.now(), requestId);
        } catch (RejectedExecutionException rejected) {
            return Java8Collections.failedFuture(rejectedByExecutor(request));
        }
    }

    private PanelUnavailableException rejectedByExecutor(HttpRequest request) {
        return new PanelUnavailableException(request.url, HttpURLConnection.HTTP_UNAVAILABLE,
            versionTag + " API request rejected (local executor saturated)");
    }

    private <R> CompletableFuture<R> submit(HttpRequest request, @Nullable String operation, RequestLane lane,
                                            BusyRetry busyRetry,
                                            RequestDeadline deadline, ResponseDecoder<R> decoder, Instant startTime,
                                            String requestId) {
        return CompletableFuture.supplyAsync(() -> {
            if (deadline.passed()) {
                throw new QueuedRequestExpiredException(request.url,
                    versionTag + " API request not sent (its caller's deadline passed while it was queued)");
            }
            HttpURLConnection connection = null;
            try {
                connection = open(request, deadline);
                int statusCode = connection.getResponseCode();
                byte[] responseBody = readBody(requestId, connection, statusCode);
                long durationMs = Duration.between(startTime, Instant.now()).toMillis();

                if (debugMode) logResponse(requestId, statusCode, responseBody, durationMs, operation);

                if (statusCode >= 200 && statusCode < 300) {
                    lane.breaker().recordSuccess();
                    return decoder.decode(requestId, responseBody);
                }
                Optional<Duration> retryDelay = busyRetry
                    .delayBeforeRetry(statusCode, connection.getHeaderField(BusyRetry.RETRY_AFTER_HEADER))
                    .filter(deadline::allowsWaiting);
                if (retryDelay.isPresent()) throw new BusyResponseException(retryDelay.get());
                connections.reportIfRefused(connection, statusCode);
                throw toError(requestId, request, statusCode, responseBody);
            } catch (RuntimeException e) {
                throw e;
            } catch (IOException e) {
                throw new PanelUnavailableException(request.url, STATUS_UNREACHABLE,
                    versionTag + " API unreachable: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            } catch (Exception e) {
                throw new RuntimeException(versionTag + " HTTP request failed", e);
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, lane.executor());
    }

    private HttpURLConnection open(HttpRequest request, RequestDeadline deadline) throws IOException {
        HttpURLConnection connection = connections.open(request.path);
        connection.setRequestMethod(request.method);
        connection.setConnectTimeout(deadline.capMillis(CONNECT_TIMEOUT));
        connection.setReadTimeout(deadline.capMillis(readTimeoutOf(request)));
        connection.setInstanceFollowRedirects(true);

        for (Map.Entry<String, String> header : request.headers.entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }

        if (request.body != null) {
            connection.setDoOutput(true);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(request.body);
            }
            connection.setReadTimeout(deadline.capMillis(readTimeoutOf(request)));
        }
        return connection;
    }

    private static Duration readTimeoutOf(HttpRequest request) {
        return request.timeout != null ? request.timeout : CONNECT_TIMEOUT;
    }

    private byte[] readBody(String requestId, HttpURLConnection connection, int statusCode) {
        try {
            InputStream stream = statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) return EMPTY_BODY;
            try (InputStream in = stream) {
                return readAllBytes(in);
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, String.format("[%s-REQ-%s] Failed to read response body", versionTag, requestId), e);
            return EMPTY_BODY;
        }
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    protected abstract void logRequest(String requestId, HttpRequest request);

    protected abstract void logResponse(String requestId, int statusCode, byte[] body, long durationMs,
                                        @Nullable String operation);

    protected abstract FailureBody readFailure(int statusCode, byte[] body);

    final RuntimeException toError(String requestId, HttpRequest request, int statusCode, byte[] body) {
        FailureBody failure = readFailure(statusCode, body);
        logFailedResponse(requestId, request, statusCode, failure.message());
        if (statusCode >= 500 && statusCode < 600) {
            return new PanelUnavailableException(request.url, statusCode,
                versionTag + " API is temporarily unavailable (HTTP " + statusCode + ")", failure.code());
        }
        if (statusCode >= 400 && statusCode < 500) return new ApiClientException(statusCode, failure.message(), failure.code());
        return new RuntimeException(failure.message());
    }

    protected static final class FailureBody {
        private final String message;
        private final @Nullable String code;

        FailureBody(String message, @Nullable String code) {
            this.message = message;
            this.code = code;
        }

        String message() {
            return message;
        }

        @Nullable String code() {
            return code;
        }
    }

    @FunctionalInterface
    protected interface ResponseDecoder<R> {
        R decode(String requestId, byte[] body) throws Exception;
    }

    protected static final class HttpRequest {
        final String path;
        final String url;
        final String method;
        final byte @Nullable [] body;
        final @Nullable Duration timeout;
        final Map<String, String> headers;

        private HttpRequest(String path, String url, String method, byte @Nullable [] body,
                            @Nullable Duration timeout, Map<String, String> headers) {
            this.path = path;
            this.url = url;
            this.method = method;
            this.body = body;
            this.timeout = timeout;
            this.headers = headers;
        }
    }
}
