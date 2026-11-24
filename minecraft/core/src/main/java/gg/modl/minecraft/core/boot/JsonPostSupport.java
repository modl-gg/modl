package gg.modl.minecraft.core.boot;

import gg.modl.minecraft.core.connection.BackendConnections;
import gg.modl.minecraft.core.util.HttpBodies;
import gg.modl.update.CompatibilityHeaders;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

final class JsonPostSupport {
    private static final int FIRST_SUCCESS_STATUS = 200;
    private static final int FIRST_REDIRECT_STATUS = 300;
    private static final int FIRST_ERROR_STATUS = 400;

    private JsonPostSupport() {}

    static JsonResponse postJson(BackendConnections connections, String path, int connectTimeoutMillis,
                                 int readTimeoutMillis, String jsonBody) throws IOException {
        String urlString = connections.resolve(path);
        HttpURLConnection connection = connections.open(path);
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(connectTimeoutMillis);
            connection.setReadTimeout(readTimeoutMillis);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);

            OutputStream output = connection.getOutputStream();
            output.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            output.flush();

            int status = responseCode(connection, urlString);
            if (status < FIRST_SUCCESS_STATUS || status >= FIRST_ERROR_STATUS) {
                Optional<VersionMismatchFailure> refusal = connections.refusal(connection, status);
                if (refusal.isPresent()) throw refusal.get();
                throw HttpStatusException.withBody(status, HttpBodies.readQuietly(connection, status));
            }
            if (status >= FIRST_REDIRECT_STATUS) throw HttpStatusException.redirect(status, connection.getHeaderField("Location"));
            InputStream stream = connection.getInputStream();
            if (stream == null) throw new IOException("Server returned HTTP " + status + " with no response body");
            return new JsonResponse(HttpBodies.read(stream), connection.getHeaderField(CompatibilityHeaders.SERVER_VERSION));
        } finally {
            connection.disconnect();
        }
    }

    private static int responseCode(HttpURLConnection connection, String urlString) throws IOException {
        try {
            return connection.getResponseCode();
        } catch (IOException e) {
            String errorBody = HttpBodies.readErrorQuietly(connection);
            throw new IOException("Request to " + urlString + " failed: " + (errorBody.isEmpty() ? e.getMessage() : errorBody), e);
        }
    }

    static final class JsonResponse {
        private final String body;
        private final String serverVersion;

        JsonResponse(String body, String serverVersion) {
            this.body = body;
            this.serverVersion = serverVersion;
        }

        String body() {
            return body;
        }

        String serverVersion() {
            return serverVersion;
        }
    }
}
