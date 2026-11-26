package gg.modl.minecraft.features.reporter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import gg.modl.minecraft.core.boot.PlatformType;
import gg.modl.minecraft.core.boot.VersionMismatchReports;
import gg.modl.minecraft.core.connection.BackendClientIdentity;
import gg.modl.minecraft.core.connection.BackendConnections;
import gg.modl.minecraft.core.connection.BackendEndpoint;
import gg.modl.minecraft.replay.format.ReplayLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModlBackendReplayUploaderTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesRelativeUploadUrlsAgainstTheBackend() throws IOException, ExecutionException, InterruptedException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        List<String> observedPaths = new CopyOnWriteArrayList<>();
        List<String> initBodies = new CopyOnWriteArrayList<>();
        int port = server.getAddress().getPort();
        String baseUrl = "http://127.0.0.1:" + port;

        server.createContext("/v1/minecraft/replays/upload", exchange -> {
            observedPaths.add(exchange.getRequestURI().getPath());
            initBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"replayId\":\"replay-123\",\"uploadUrl\":\"/storage?sig=abc\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/storage", exchange -> {
            observedPaths.add(exchange.getRequestURI().getPath());
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.createContext("/v1/minecraft/replays/confirm/replay-123", exchange -> {
            observedPaths.add(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.createContext("/", exchange -> {
            observedPaths.add(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(404, 0);
            exchange.close();
        });
        server.start();

        try {
            File replayFile = Files.write(tempDir.resolve("sample.replay"), "replay".getBytes(StandardCharsets.UTF_8)).toFile();
            UUID targetUuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
            ModlBackendReplayUploader uploader = ModlBackendReplayUploader.direct(connections(baseUrl, "key"));

            String replayId = uploader.uploadAsync(replayFile, "1.21.11", targetUuid, "modltarget").get();

            assertEquals("replay-123", replayId);
            assertEquals(List.of(
                    "/v1/minecraft/replays/upload",
                    "/storage",
                    "/v1/minecraft/replays/confirm/replay-123"
            ), observedPaths);
            assertEquals(1, initBodies.size());
            JsonObject initBody = JsonParser.parseString(initBodies.get(0)).getAsJsonObject();
            assertEquals("1.21.11", initBody.get("mcVersion").getAsString());
            assertEquals(replayFile.length(), initBody.get("fileSize").getAsLong());
            assertEquals(targetUuid.toString(), initBody.get("targetUuid").getAsString());
            assertEquals("modltarget", initBody.get("targetName").getAsString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsApiKeyOnlyToTrustedBackendRequests() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        List<String> observedPaths = new CopyOnWriteArrayList<>();
        List<String> backendApiKeys = new CopyOnWriteArrayList<>();
        List<String> storageApiKeys = new CopyOnWriteArrayList<>();
        List<String> storageContentLengths = new CopyOnWriteArrayList<>();
        List<String> storageBodies = new CopyOnWriteArrayList<>();
        int port = server.getAddress().getPort();
        String baseUrl = "http://127.0.0.1:" + port;

        server.createContext("/v1/minecraft/replays/upload", exchange -> {
            observedPaths.add(exchange.getRequestURI().getPath());
            backendApiKeys.add(exchange.getRequestHeaders().getFirst("X-API-Key"));
            byte[] response = ("{\"replayId\":\"replay-headers\",\"uploadUrl\":\"" + baseUrl + "/storage?signature=abc\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/storage", exchange -> {
            observedPaths.add(exchange.getRequestURI().getPath());
            storageApiKeys.add(exchange.getRequestHeaders().getFirst("X-API-Key"));
            storageContentLengths.add(exchange.getRequestHeaders().getFirst("Content-Length"));
            storageBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.createContext("/v1/minecraft/replays/confirm/replay-headers", exchange -> {
            observedPaths.add(exchange.getRequestURI().getPath());
            backendApiKeys.add(exchange.getRequestHeaders().getFirst("X-API-Key"));
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.start();

        try {
            File replayFile = Files.write(tempDir.resolve("headers.replay"), "replay".getBytes(StandardCharsets.UTF_8)).toFile();
            ModlBackendReplayUploader uploader = ModlBackendReplayUploader.direct(connections(baseUrl, "secret-key"));

            String replayId = uploader.uploadAsync(replayFile, "1.21.11").get();

            assertEquals("replay-headers", replayId);
            assertEquals(List.of(
                    "/v1/minecraft/replays/upload",
                    "/storage",
                    "/v1/minecraft/replays/confirm/replay-headers"
            ), observedPaths);
            assertEquals(List.of("secret-key", "secret-key"), backendApiKeys);
            assertEquals(1, storageApiKeys.size());
            assertNull(storageApiKeys.get(0));
            assertEquals(List.of(String.valueOf(replayFile.length())), storageContentLengths);
            assertEquals(List.of("replay"), storageBodies);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void propagatesInitFailureAndSkipsStorageAndConfirm() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        List<String> observedPaths = new CopyOnWriteArrayList<>();
        int port = server.getAddress().getPort();
        String baseUrl = "http://127.0.0.1:" + port;

        server.createContext("/v1/minecraft/replays/upload", exchange -> {
            observedPaths.add(exchange.getRequestURI().getPath());
            byte[] response = "not allowed".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/", exchange -> {
            observedPaths.add(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(500, 0);
            exchange.close();
        });
        server.start();

        try {
            File replayFile = Files.write(tempDir.resolve("init-failure.replay"), "replay".getBytes(StandardCharsets.UTF_8)).toFile();
            ModlBackendReplayUploader uploader = ModlBackendReplayUploader.direct(connections(baseUrl, "secret-key"));

            ExecutionException exception = assertThrows(ExecutionException.class,
                    () -> uploader.uploadAsync(replayFile, "1.21.11").get());

            assertTrue(exception.getCause().getMessage().contains("Replay upload failed"));
            assertTrue(exception.getCause().getCause().getMessage().contains("Init upload failed (HTTP 403): not allowed"));
            assertEquals(List.of("/v1/minecraft/replays/upload"), observedPaths);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void propagatesStorageFailureAndSkipsConfirm() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        List<String> observedPaths = new CopyOnWriteArrayList<>();
        int port = server.getAddress().getPort();
        String baseUrl = "http://127.0.0.1:" + port;

        server.createContext("/v1/minecraft/replays/upload", exchange -> {
            observedPaths.add(exchange.getRequestURI().getPath());
            byte[] response = ("{\"replayId\":\"replay-storage-failure\",\"uploadUrl\":\"" + baseUrl + "/storage\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/storage", exchange -> {
            observedPaths.add(exchange.getRequestURI().getPath());
            exchange.getRequestBody().readAllBytes();
            byte[] response = "storage rejected".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/", exchange -> {
            observedPaths.add(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(500, 0);
            exchange.close();
        });
        server.start();

        try {
            File replayFile = Files.write(tempDir.resolve("storage-failure.replay"), "replay".getBytes(StandardCharsets.UTF_8)).toFile();
            ModlBackendReplayUploader uploader = ModlBackendReplayUploader.direct(connections(baseUrl, "secret-key"));

            ExecutionException exception = assertThrows(ExecutionException.class,
                    () -> uploader.uploadAsync(replayFile, "1.21.11").get());

            assertTrue(exception.getCause().getCause().getMessage().contains("Storage upload failed (HTTP 503): storage rejected"));
            assertEquals(List.of("/v1/minecraft/replays/upload", "/storage"), observedPaths);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void propagatesConfirmFailureAfterStorageUpload() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        List<String> observedPaths = new CopyOnWriteArrayList<>();
        int port = server.getAddress().getPort();
        String baseUrl = "http://127.0.0.1:" + port;

        server.createContext("/v1/minecraft/replays/upload", exchange -> {
            observedPaths.add(exchange.getRequestURI().getPath());
            byte[] response = ("{\"replayId\":\"replay-confirm-failure\",\"uploadUrl\":\"" + baseUrl + "/storage\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/storage", exchange -> {
            observedPaths.add(exchange.getRequestURI().getPath());
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.createContext("/v1/minecraft/replays/confirm/replay-confirm-failure", exchange -> {
            observedPaths.add(exchange.getRequestURI().getPath());
            byte[] response = "confirm rejected".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(502, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            File replayFile = Files.write(tempDir.resolve("confirm-failure.replay"), "replay".getBytes(StandardCharsets.UTF_8)).toFile();
            ModlBackendReplayUploader uploader = ModlBackendReplayUploader.direct(connections(baseUrl, "secret-key"));

            ExecutionException exception = assertThrows(ExecutionException.class,
                    () -> uploader.uploadAsync(replayFile, "1.21.11").get());

            assertTrue(exception.getCause().getCause().getMessage().contains(
                    "Confirm upload failed (HTTP 502) for replay replay-confirm-failure: confirm rejected"));
            assertEquals(List.of(
                    "/v1/minecraft/replays/upload",
                    "/storage",
                    "/v1/minecraft/replays/confirm/replay-confirm-failure"
            ), observedPaths);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesTheConfirmWhileTheBackendAnswersBusy() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        AtomicInteger confirmHits = new AtomicInteger();
        int port = server.getAddress().getPort();
        String baseUrl = "http://127.0.0.1:" + port;

        server.createContext("/v1/minecraft/replays/upload", exchange -> {
            byte[] response = ("{\"replayId\":\"replay-busy\",\"uploadUrl\":\"" + baseUrl + "/storage\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/storage", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.createContext("/v1/minecraft/replays/confirm/replay-busy", exchange -> {
            if (confirmHits.incrementAndGet() == 1) {
                byte[] response = "{\"code\":\"REPLAY_VALIDATION_UNAVAILABLE\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Retry-After", "1");
                exchange.sendResponseHeaders(503, response.length);
                exchange.getResponseBody().write(response);
            } else {
                exchange.sendResponseHeaders(200, 0);
            }
            exchange.close();
        });
        server.start();

        try {
            File replayFile = Files.write(tempDir.resolve("busy.replay"), "replay".getBytes(StandardCharsets.UTF_8)).toFile();
            ModlBackendReplayUploader uploader = ModlBackendReplayUploader.direct(connections(baseUrl, "secret-key"));

            assertEquals("replay-busy", uploader.uploadAsync(replayFile, "1.21.11").get(30, TimeUnit.SECONDS));
            assertEquals(2, confirmHits.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsUnsupportedOrMalformedBackendUrlsBeforeUpload() {
        for (String backendUrl : List.of("ftp://example.com", "file:///tmp/backend", "http:///missing-host", "://bad")) {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> BackendEndpoint.of(backendUrl, "secret-key"));

            assertTrue(exception.getMessage().contains("Unsupported backend URL"));
        }
    }

    @Test
    void acceptsHttpBackendUrls() {
        assertDoesNotThrow(() -> ModlBackendReplayUploader.direct(connections("http://127.0.0.1:8080", "secret-key")));
        assertDoesNotThrow(() -> ModlBackendReplayUploader.direct(connections("https://modl.example.com/base", "secret-key")));
    }

    @Test
    void runsUploadWorkOnProvidedExecutor() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        AtomicBoolean executorUsed = new AtomicBoolean(false);
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(() -> {
                executorUsed.set(true);
                r.run();
            }, "modl-replay-upload-test");
            thread.setDaemon(true);
            return thread;
        });
        int port = server.getAddress().getPort();
        String baseUrl = "http://127.0.0.1:" + port;

        server.createContext("/v1/minecraft/replays/upload", exchange -> {
            byte[] response = ("{\"replayId\":\"replay-executor\",\"uploadUrl\":\"" + baseUrl + "/storage\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/storage", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.createContext("/v1/minecraft/replays/confirm/replay-executor", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.start();

        try {
            File replayFile = Files.write(tempDir.resolve("executor.replay"), "replay".getBytes(StandardCharsets.UTF_8)).toFile();
            ModlBackendReplayUploader uploader = ModlBackendReplayUploader.direct(connections(baseUrl, "secret-key"), executor);

            assertEquals("replay-executor", uploader.uploadAsync(replayFile, "1.21.11").get());
            assertTrue(executorUsed.get());
        } finally {
            server.stop(0);
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void closeShutsDownDefaultExecutor() throws Exception {
        ModlBackendReplayUploader uploader = ModlBackendReplayUploader.direct(connections("http://127.0.0.1:8080", "secret-key"));

        uploader.close();

        File replayFile = Files.write(tempDir.resolve("closed.replay"), "replay".getBytes(StandardCharsets.UTF_8)).toFile();
        assertThrows(RejectedExecutionException.class, () -> uploader.uploadAsync(replayFile, "1.21.11"));
    }

    @Test
    void closeRefusesNewUploadsButLetsAcceptedOnesFinish() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ExecutorService serverExecutor = Executors.newCachedThreadPool();
        CountDownLatch firstUploadStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstUpload = new CountDownLatch(1);
        AtomicInteger confirmHits = new AtomicInteger();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        server.setExecutor(serverExecutor);
        server.createContext("/v1/minecraft/replays/upload", exchange -> {
            firstUploadStarted.countDown();
            try {
                releaseFirstUpload.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] response = ("{\"replayId\":\"replay-close\",\"uploadUrl\":\"" + baseUrl + "/storage\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/storage", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.createContext("/v1/minecraft/replays/confirm/replay-close", exchange -> {
            confirmHits.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.start();

        try {
            File firstReplayFile = Files.write(tempDir.resolve("first-close.replay"),
                    "first".getBytes(StandardCharsets.UTF_8)).toFile();
            File secondReplayFile = Files.write(tempDir.resolve("second-close.replay"),
                    "second".getBytes(StandardCharsets.UTF_8)).toFile();
            ModlBackendReplayUploader uploader = ModlBackendReplayUploader.direct(connections(baseUrl, "secret-key"));

            CompletableFuture<String> firstUpload = uploader.uploadAsync(firstReplayFile, "1.21.11");
            assertTrue(firstUploadStarted.await(5, TimeUnit.SECONDS));
            CompletableFuture<String> queuedUpload = uploader.uploadAsync(secondReplayFile, "1.21.11");

            uploader.close();

            assertThrows(RejectedExecutionException.class, () -> uploader.uploadAsync(secondReplayFile, "1.21.11"));
            assertFalse(firstUpload.isDone());
            releaseFirstUpload.countDown();
            assertEquals("replay-close", firstUpload.get(5, TimeUnit.SECONDS));
            assertEquals("replay-close", queuedUpload.get(5, TimeUnit.SECONDS));
            assertEquals(2, confirmHits.get());
        } finally {
            releaseFirstUpload.countDown();
            server.stop(0);
            serverExecutor.shutdownNow();
            serverExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void anUploadThatOutlivesTheShutdownGraceIsAbandoned() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ExecutorService serverExecutor = Executors.newCachedThreadPool();
        ExecutorService uploadExecutor = Executors.newSingleThreadExecutor();
        AtomicInteger storageHits = new AtomicInteger();
        AtomicInteger confirmHits = new AtomicInteger();
        CountDownLatch initStarted = new CountDownLatch(1);
        CountDownLatch releaseInit = new CountDownLatch(1);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        server.setExecutor(serverExecutor);
        server.createContext("/v1/minecraft/replays/upload", exchange -> {
            initStarted.countDown();
            try { releaseInit.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            byte[] response = ("{\"replayId\":\"replay-abort\",\"uploadUrl\":\"" + baseUrl + "/storage\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/storage", exchange -> {
            storageHits.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.createContext("/v1/minecraft/replays/confirm/replay-abort", exchange -> {
            confirmHits.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        server.start();

        try {
            File replayFile = Files.write(tempDir.resolve("abort-after-close.replay"),
                    "replay".getBytes(StandardCharsets.UTF_8)).toFile();
            ModlBackendReplayUploader uploader = ModlBackendReplayUploader.direct(connections(baseUrl, "secret-key"), uploadExecutor);

            CompletableFuture<String> upload = uploader.uploadAsync(replayFile, "1.21.11");
            assertTrue(initStarted.await(5, TimeUnit.SECONDS));
            uploader.awaitClosed(100, TimeUnit.MILLISECONDS);
            assertTrue(upload.isCompletedExceptionally());
            releaseInit.countDown();

            awaitExecutorDrained(uploadExecutor);

            assertEquals(0, storageHits.get(), "storage should not be hit after the grace ran out");
            assertEquals(0, confirmHits.get(), "confirm should not be hit after the grace ran out");
        } finally {
            releaseInit.countDown();
            server.stop(0);
            serverExecutor.shutdownNow();
            serverExecutor.awaitTermination(5, TimeUnit.SECONDS);
            uploadExecutor.shutdownNow();
            uploadExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void aReplayOverTheUploadLimitIsRefusedBeforeAnyRequest() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            File oversized = tempDir.resolve("oversized.modlreplay").toFile();
            try (RandomAccessFile sparse = new RandomAccessFile(oversized, "rw")) {
                sparse.setLength(ReplayLimits.MAX_COMPRESSED_BYTES + 1);
            }
            ModlBackendReplayUploader uploader = ModlBackendReplayUploader.direct(connections("http://127.0.0.1:9", "secret-key"), executor);

            CompletableFuture<String> upload = uploader.uploadAsync(oversized, "1.21.11");

            ExecutionException failure = assertThrows(ExecutionException.class, () -> upload.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof ReplayFileTooLargeException);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void uploadsBeyondTheWaitingLimitAreRefused() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch blocker = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                try {
                    blocker.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            File replayFile = Files.write(tempDir.resolve("queued.replay"), "replay".getBytes(StandardCharsets.UTF_8)).toFile();
            ModlBackendReplayUploader uploader = ModlBackendReplayUploader.direct(connections("http://127.0.0.1:9", "secret-key"), executor);
            List<CompletableFuture<String>> waiting = new ArrayList<>();
            for (int i = 0; i < ModlBackendReplayUploader.MAX_PENDING_UPLOADS; i++) waiting.add(uploader.uploadAsync(replayFile, "1.21.11"));

            assertThrows(RejectedExecutionException.class, () -> uploader.uploadAsync(replayFile, "1.21.11"));
            assertEquals(ModlBackendReplayUploader.MAX_PENDING_UPLOADS, waiting.size());
        } finally {
            blocker.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void closeKeepsProvidedExecutorCallerOwned() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ModlBackendReplayUploader uploader = ModlBackendReplayUploader.direct(connections("http://127.0.0.1:8080", "secret-key"), executor);

            uploader.close();

            assertFalse(executor.isShutdown());
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static BackendConnections connections(String baseUrl, String apiKey) {
        return new BackendConnections(BackendEndpoint.of(baseUrl, apiKey), BackendClientIdentity.newBoot(PlatformType.SPIGOT),
                new VersionMismatchReports());
    }

    private static void awaitExecutorDrained(ExecutorService executor) throws Exception {
        executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
    }
}
