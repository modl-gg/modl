package com.alessiodp.libby.download;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

final class ArtifactServer implements AutoCloseable {
    private static final int NOT_FOUND = 404;
    private static final int OK = 200;

    private final HttpServer server;
    private final Map<String, byte[]> artifacts = new ConcurrentHashMap<>();
    private final Map<String, Deque<Integer>> failures = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();

    private ArtifactServer(HttpServer server) {
        this.server = server;
    }

    static ArtifactServer start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ArtifactServer artifactServer = new ArtifactServer(server);
        server.createContext("/", artifactServer::handle);
        server.start();
        return artifactServer;
    }

    void serve(String path, byte[] content) {
        artifacts.put(path, content);
    }

    synchronized void failNext(String path, int status) {
        failures.computeIfAbsent(path, ignored -> new ArrayDeque<>()).add(status);
    }

    int requests(String path) {
        AtomicInteger count = requests.get(path);
        return count == null ? 0 : count.get();
    }

    String url(String path) {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + path;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requests.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
        Integer failure = nextFailure(path);
        byte[] content = artifacts.get(path);
        if (failure != null || content == null) {
            exchange.sendResponseHeaders(failure != null ? failure : NOT_FOUND, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(OK, content.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(content);
        }
    }

    private synchronized Integer nextFailure(String path) {
        Deque<Integer> pending = failures.get(path);
        return pending == null ? null : pending.poll();
    }
}
