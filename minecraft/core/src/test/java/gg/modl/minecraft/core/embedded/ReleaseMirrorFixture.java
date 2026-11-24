package gg.modl.minecraft.core.embedded;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

final class ReleaseMirrorFixture implements AutoCloseable {
    private static final String ROOT = "/releases/";
    private static final int OK = 200;
    private static final int NOT_FOUND = 404;

    private final HttpServer server;
    private final ExecutorService handlers;
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();

    private ReleaseMirrorFixture(HttpServer server, ExecutorService handlers) {
        this.server = server;
        this.handlers = handlers;
    }

    static ReleaseMirrorFixture start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ExecutorService handlers = Executors.newCachedThreadPool();
        server.setExecutor(handlers);
        ReleaseMirrorFixture fixture = new ReleaseMirrorFixture(server, handlers);
        server.createContext(ROOT, fixture::handle);
        server.start();
        return fixture;
    }

    void publish(String version, String file, byte[] content) {
        files.put(path(version, file), content);
    }

    int requests(String version, String file) {
        AtomicInteger count = requests.get(path(version, file));
        return count == null ? 0 : count.get();
    }

    int totalRequests() {
        int total = 0;
        for (AtomicInteger count : requests.values()) total += count.get();
        return total;
    }

    String baseUrl() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/releases";
    }

    @Override
    public void close() {
        server.stop(0);
        handlers.shutdownNow();
    }

    private static String path(String version, String file) {
        return "download/v" + version + "/" + file;
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath().substring(ROOT.length());
        requests.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
        byte[] content = files.get(path);
        if (content == null) {
            exchange.sendResponseHeaders(NOT_FOUND, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(OK, content.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(content);
        }
    }
}
