package gg.modl.backend.support;

import com.google.gson.Gson;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

public final class ApiClient {
    private static final int RATE_LIMIT_RETRIES = 5;
    private static final int OCTET = 256;
    private static final int HTTP_PORT = 80;
    private static final int UNREAD_ANSWER_TIMEOUT_MILLIS = 10_000;
    private static final AtomicInteger REQUESTS = new AtomicInteger();

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String sessionToken;
    private final String sessionKey;
    private final String panelOrigin;
    private final Gson gson = new Gson();

    public ApiClient() {
        this(TestServerCredentials.baseUrl(), TestServerCredentials.apiKey(), TestServerCredentials.sessionToken(),
            TestServerCredentials.sessionKey() == null ? "" : TestServerCredentials.sessionKey());
    }

    ApiClient(String baseUrl, String apiKey, String sessionToken, String sessionKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.sessionToken = sessionToken;
        this.sessionKey = sessionKey;
        this.panelOrigin = TestServerCredentials.originOf(baseUrl);
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    public HttpResponse<String> minecraftGet(String path) throws IOException, InterruptedException {
        return send(minecraftBuilder(path)
            .GET()
            .build());
    }

    public HttpResponse<String> minecraftPost(String path, Object body) throws IOException, InterruptedException {
        return send(minecraftBuilder(path)
            .header("Content-Type", "application/json")
            .POST(jsonBody(body))
            .build());
    }

    public HttpResponse<String> minecraftPatch(String path, Object body) throws IOException, InterruptedException {
        return send(minecraftBuilder(path)
            .header("Content-Type", "application/json")
            .method("PATCH", jsonBody(body))
            .build());
    }

    public HttpResponse<String> minecraftPatchAs(String actingStaffId, String path, Object body)
        throws IOException, InterruptedException {
        return send(minecraftBuilder(path)
            .header("X-Acting-Staff-Id", actingStaffId)
            .header("Content-Type", "application/json")
            .method("PATCH", jsonBody(body))
            .build());
    }

    public HttpResponse<String> panelGet(String path) throws IOException, InterruptedException {
        return send(panelBuilder(path).GET().build());
    }

    public HttpResponse<String> panelPost(String path, Object body) throws IOException, InterruptedException {
        return send(panelBuilder(path)
            .header("Content-Type", "application/json")
            .POST(jsonBody(body))
            .build());
    }

    public HttpResponse<String> panelPut(String path, Object body) throws IOException, InterruptedException {
        return send(panelBuilder(path)
            .header("Content-Type", "application/json")
            .PUT(jsonBody(body))
            .build());
    }

    public HttpResponse<String> panelPatch(String path, Object body) throws IOException, InterruptedException {
        return send(panelBuilder(path)
            .header("Content-Type", "application/json")
            .method("PATCH", jsonBody(body))
            .build());
    }

    public HttpResponse<String> panelDelete(String path) throws IOException, InterruptedException {
        return send(panelBuilder(path).DELETE().build());
    }

    public HttpResponse<String> publicGet(String path) throws IOException, InterruptedException {
        return send(newBuilder(path)
            .GET()
            .build());
    }

    public HttpResponse<String> publicPost(String path, Object body) throws IOException, InterruptedException {
        return send(newBuilder(path)
            .header("Content-Type", "application/json")
            .POST(jsonBody(body))
            .build());
    }

    public HttpResponse<String> rawGet(String path) throws IOException, InterruptedException {
        return send(newBuilder(path).GET().build());
    }

    public ApiClient withSession(String token, String key) {
        return new ApiClient(baseUrl, apiKey, token, key);
    }

    public HttpResponse<String> minecraftPutFile(String path, HttpRequest.BodyPublisher body)
        throws IOException, InterruptedException {
        return send(minecraftBuilder(path)
            .header("Content-Type", "application/octet-stream")
            .PUT(body)
            .build());
    }

    public int minecraftDeclareFile(String path, long declaredLength) throws IOException {
        URI uri = URI.create(baseUrl + path);
        int port = uri.getPort() > 0 ? uri.getPort() : HTTP_PORT;
        try (Socket socket = new Socket(uri.getHost(), port)) {
            socket.setSoTimeout(UNREAD_ANSWER_TIMEOUT_MILLIS);
            String headers = String.join("\r\n",
                "PUT " + uri.getRawPath() + " HTTP/1.1",
                "Host: " + uri.getAuthority(),
                "X-API-Key: " + apiKey,
                "Accept: application/json",
                "Content-Type: application/octet-stream",
                "Content-Length: " + declaredLength,
                "Connection: close",
                "", "");
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            BufferedReader answer = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String statusLine = answer.readLine();
            if (statusLine == null) {
                throw new IOException("The server closed the connection without an answer");
            }
            return Integer.parseInt(statusLine.split(" ")[1]);
        }
    }

    private HttpRequest.Builder minecraftBuilder(String path) {
        return newBuilder(path).header("X-API-Key", apiKey);
    }

    private HttpRequest.Builder panelBuilder(String path) {
        return newBuilder(path)
            .header("Origin", panelOrigin)
            .header("Referer", panelOrigin + "/panel")
            .header("Cookie", "MODL_SESSION=" + sessionToken)
            .header("X-Modl-Session-Key", sessionKey);
    }

    private HttpRequest.Builder newBuilder(String path) {
        return HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .header("X-Forwarded-For", distinctClientAddress())
            .header("User-Agent", "modl-backend-test-suite");
    }

    private static String distinctClientAddress() {
        int request = REQUESTS.incrementAndGet();
        return "203.0." + (request / OCTET % OCTET) + "." + (request % OCTET);
    }

    private HttpRequest.BodyPublisher jsonBody(Object body) {
        if (body == null) {
            return HttpRequest.BodyPublishers.noBody();
        }
        String json = (body instanceof String s) ? s : gson.toJson(body);
        return HttpRequest.BodyPublishers.ofString(json);
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        for (int attempt = 0; attempt < RATE_LIMIT_RETRIES; attempt++) {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 429) {
                return response;
            }
            Thread.sleep(1000L * (attempt + 1));
        }
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
