package gg.modl.backend.ai.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

public final class LocalAiServer implements AutoCloseable {
    private final HttpServer server;
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();

    private LocalAiServer(HttpServer server) {
        this.server = server;
    }

    public static LocalAiServer start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return new LocalAiServer(server);
    }

    public LocalAiServer on(String path, HttpHandler handler) {
        server.createContext(path, exchange -> {
            requests.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI(),
                Map.copyOf(exchange.getRequestHeaders()), new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            handler.handle(exchange);
        });
        return this;
    }

    public LocalAiServer json(String path, int status, String body) {
        return on(path, exchange -> respond(exchange, status, body));
    }

    public URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public List<Recorded> requests() {
        return List.copyOf(requests);
    }

    public static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    public record Recorded(String method, URI uri, Map<String, List<String>> headers, String body) {
        public String header(String name) {
            return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(entry -> entry.getValue().getFirst())
                .findFirst()
                .orElse(null);
        }
    }
}
