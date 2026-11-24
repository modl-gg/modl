package gg.modl.minecraft.features.reporter;

import gg.modl.minecraft.core.connection.BackendConnections;
import gg.modl.minecraft.core.replay.BackendReplayUploadAuthority;
import gg.modl.minecraft.core.replay.ReplayUploadAuthority;
import gg.modl.minecraft.core.replay.ReplayUploadGrant;
import gg.modl.minecraft.core.replay.ReplayUploadRequest;
import gg.modl.minecraft.core.util.HttpBodies;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ModlBackendReplayUploader implements AutoCloseable {
    private static final String USER_AGENT = "modl-minecraft";
    private static final int CONNECT_TIMEOUT_MS = (int) Duration.ofSeconds(10).toMillis();
    private static final int UPLOAD_READ_TIMEOUT_MS = (int) Duration.ofMinutes(5).toMillis();

    private final ReplayUploadAuthority authority;
    private final BackendConnections connections;
    private final ExecutorService uploadExecutor;
    private final boolean ownsUploadExecutor;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong nextUploadId = new AtomicLong();
    private final Map<Long, CompletableFuture<String>> pendingUploads = new ConcurrentHashMap<>();

    private ModlBackendReplayUploader(BackendConnections connections, ExecutorService uploadExecutor,
                                      boolean ownsUploadExecutor) {
        this.authority = new BackendReplayUploadAuthority(connections);
        this.connections = connections;
        this.uploadExecutor = uploadExecutor;
        this.ownsUploadExecutor = ownsUploadExecutor;
    }

    public static ModlBackendReplayUploader direct(BackendConnections connections) {
        return new ModlBackendReplayUploader(connections, createUploadExecutor(), true);
    }

    static ModlBackendReplayUploader direct(BackendConnections connections, ExecutorService uploadExecutor) {
        return new ModlBackendReplayUploader(connections, uploadExecutor, false);
    }

    public CompletableFuture<String> uploadAsync(File replayFile, String mcVersion) {
        return uploadAsync(replayFile, mcVersion, null, null);
    }

    public CompletableFuture<String> uploadAsync(File replayFile, String mcVersion, @Nullable UUID targetUuid,
                                                @Nullable String targetName) {
        throwIfClosed();

        CompletableFuture<String> future = new CompletableFuture<>();
        Long uploadId = nextUploadId.incrementAndGet();
        pendingUploads.put(uploadId, future);
        if (closed.get()) {
            pendingUploads.remove(uploadId);
            RejectedExecutionException exception = new RejectedExecutionException("Replay uploader is closed");
            future.completeExceptionally(exception);
            throw exception;
        }

        Runnable uploadTask = () -> {
            ReplayUploadGrant grant = null;
            try {
                throwIfClosed();
                grant = authority.requestUpload(
                        new ReplayUploadRequest(mcVersion, replayFile.length(), targetUuid, targetName));
                throwIfClosed();
                uploadToStorage(replayFile, grant);
                throwIfClosed();
                authority.confirmUpload(grant);
                future.complete(grant.getReplayId());
            } catch (Exception e) {
                future.completeExceptionally(new RuntimeException("Replay upload failed: " + e.getMessage(), e));
                abandonQuietly(grant);
            } finally {
                pendingUploads.remove(uploadId);
            }
        };

        try {
            uploadExecutor.execute(uploadTask);
        } catch (RejectedExecutionException e) {
            pendingUploads.remove(uploadId);
            future.completeExceptionally(e);
            throw e;
        }

        return future;
    }

    @Override
    public void close() {
        closed.set(true);
        RejectedExecutionException closedException = new RejectedExecutionException("Replay uploader is closed");
        pendingUploads.values().forEach(future -> future.completeExceptionally(closedException));
        if (ownsUploadExecutor) {
            uploadExecutor.shutdownNow();
        }
    }

    private void uploadToStorage(File file, ReplayUploadGrant grant) throws IOException {
        URL uploadUrl = new URL(connections.endpoint().resolveReference(grant.getUploadUrl()));
        HttpURLConnection connection = (HttpURLConnection) uploadUrl.openConnection();
        try {
            connection.setRequestMethod(grant.getMethod());
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(UPLOAD_READ_TIMEOUT_MS);
            connection.setFixedLengthStreamingMode(file.length());
            connection.setDoOutput(true);

            if (!applySignedHeaders(connection, grant.getRequiredHeaders())) {
                connection.setRequestProperty("Content-Type", "application/octet-stream");
            }
            connection.setRequestProperty("User-Agent", USER_AGENT);

            try (OutputStream os = connection.getOutputStream()) {
                Files.copy(file.toPath(), os);
            }

            int statusCode = connection.getResponseCode();
            if (statusCode < 200 || statusCode >= 300) {
                throw new IOException("Storage upload failed (HTTP " + statusCode + "): "
                        + HttpBodies.readQuietly(connection, statusCode));
            }
        } finally {
            connection.disconnect();
        }
    }

    private static boolean applySignedHeaders(HttpURLConnection connection, Map<String, String> signedHeaders) {
        boolean contentTypeSupplied = false;
        for (Map.Entry<String, String> header : signedHeaders.entrySet()) {
            String name = header.getKey();
            String value = header.getValue();
            if (name == null || value == null || value.trim().isEmpty() || "Content-Length".equalsIgnoreCase(name)) {
                continue;
            }
            if ("Content-Type".equalsIgnoreCase(name)) contentTypeSupplied = true;
            connection.setRequestProperty(name, value);
        }
        return contentTypeSupplied;
    }

    private void abandonQuietly(@Nullable ReplayUploadGrant grant) {
        if (grant == null) return;
        try {
            authority.abandonUpload(grant);
        } catch (RuntimeException ignored) {
        }
    }

    private void throwIfClosed() {
        if (closed.get()) {
            throw new RejectedExecutionException("Replay uploader is closed");
        }
    }

    private static ExecutorService createUploadExecutor() {
        return Executors.newSingleThreadExecutor(new ThreadFactory() {
            private final AtomicInteger nextId = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "modl-replay-upload-" + nextId.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        });
    }
}
