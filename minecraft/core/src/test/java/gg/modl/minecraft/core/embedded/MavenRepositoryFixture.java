package gg.modl.minecraft.core.embedded;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class MavenRepositoryFixture implements AutoCloseable {
    private static final String ROOT = "/maven2/";
    private static final int OK = 200;
    private static final int NOT_FOUND = 404;
    private static final long HOLD_SECONDS = 30;

    private final HttpServer server;
    private final ExecutorService handlers;
    private final Map<String, byte[]> artifacts = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> held = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> responseGates = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> arrivals = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();

    private MavenRepositoryFixture(HttpServer server, ExecutorService handlers) {
        this.server = server;
        this.handlers = handlers;
    }

    static MavenRepositoryFixture start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ExecutorService handlers = Executors.newCachedThreadPool();
        server.setExecutor(handlers);
        MavenRepositoryFixture fixture = new MavenRepositoryFixture(server, handlers);
        server.createContext(ROOT, fixture::handle);
        server.start();
        return fixture;
    }

    void publish(MavenArtifact artifact, byte[] content) {
        artifacts.put(artifact.repositoryPath(), content);
    }

    CountDownLatch holdHalfway(MavenArtifact artifact) {
        CountDownLatch release = new CountDownLatch(1);
        held.put(artifact.repositoryPath(), release);
        return release;
    }

    void respondAfter(MavenArtifact artifact, CountDownLatch condition) {
        responseGates.put(artifact.repositoryPath(), condition);
    }

    CountDownLatch arrivalOf(MavenArtifact artifact) {
        return arrival(artifact.repositoryPath());
    }

    int requests(MavenArtifact artifact) {
        AtomicInteger count = requests.get(artifact.repositoryPath());
        return count == null ? 0 : count.get();
    }

    String repositoryUrl() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + ROOT;
    }

    @Override
    public void close() {
        held.values().forEach(CountDownLatch::countDown);
        responseGates.values().forEach(CountDownLatch::countDown);
        server.stop(0);
        handlers.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath().substring(ROOT.length());
        requests.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
        arrival(path).countDown();
        awaitQuietly(responseGates.get(path));
        byte[] content = artifacts.get(path);
        if (content == null) {
            exchange.sendResponseHeaders(NOT_FOUND, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(OK, content.length);
        try (OutputStream body = exchange.getResponseBody()) {
            int half = content.length / 2;
            body.write(content, 0, half);
            body.flush();
            awaitQuietly(held.get(path));
            body.write(content, half, content.length - half);
        }
    }

    private CountDownLatch arrival(String path) {
        return arrivals.computeIfAbsent(path, ignored -> new CountDownLatch(1));
    }

    static boolean awaitQuietly(CountDownLatch latch) {
        if (latch == null) return true;
        try {
            return latch.await(HOLD_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
