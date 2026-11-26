package gg.modl.minecraft.core.replay;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import gg.modl.minecraft.core.connection.BackendConnections;
import gg.modl.minecraft.core.util.HttpBodies;
import gg.modl.minecraft.core.util.UrlEncoding;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class BackendReplayUploadAuthority implements ReplayUploadAuthority {
    private static final String INIT_PATH = "/v1/minecraft/replays/upload";
    private static final String CONFIRM_PATH = "/v1/minecraft/replays/confirm/";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int OK = 200;
    private static final long CONFIRMED = -1L;
    private static final String RETRY_AFTER = "Retry-After";
    private static final Pattern DELAY_SECONDS = Pattern.compile("\\d{1,9}");
    private static final long MIN_RETRY_MS = 1_000L;
    private static final long DEFAULT_RETRY_MS = 5_000L;
    private static final long MAX_RETRY_MS = 30_000L;
    private static final long CONFIRM_BUDGET_MS = 180_000L;

    private final BackendConnections connections;
    private final Gson gson = new Gson();

    public BackendReplayUploadAuthority(BackendConnections connections) {
        this.connections = connections;
    }

    @Override
    public ReplayUploadGrant requestUpload(ReplayUploadRequest request) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("mcVersion", request.getMcVersion());
        body.addProperty("fileSize", request.getFileSize());
        if (request.getTargetUuid() != null) body.addProperty("targetUuid", request.getTargetUuid().toString());
        if (request.getTargetName() != null && !request.getTargetName().isEmpty()) {
            body.addProperty("targetName", request.getTargetName());
        }

        HttpURLConnection connection = open(INIT_PATH);
        try {
            connection.setRequestProperty("Content-Type", "application/json");
            write(connection, gson.toJson(body).getBytes(StandardCharsets.UTF_8));
            int statusCode = connection.getResponseCode();
            connections.reportIfRefused(connection, statusCode);
            String responseBody = HttpBodies.readQuietly(connection, statusCode);
            if (statusCode != OK) throw new IOException("Init upload failed (HTTP " + statusCode + "): " + responseBody);
            return parseGrant(responseBody);
        } finally {
            connection.disconnect();
        }
    }

    @Override
    public void confirmUpload(ReplayUploadGrant grant) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CONFIRM_BUDGET_MS);
        IOException lastFailure = null;
        while (true) {
            long retryAfterMs;
            try {
                retryAfterMs = confirmOnce(grant);
            } catch (ConfirmRefusedException refused) {
                throw refused;
            } catch (IOException unreachable) {
                if (Thread.currentThread().isInterrupted()) throw unreachable;
                lastFailure = unreachable;
                retryAfterMs = DEFAULT_RETRY_MS;
            }
            if (retryAfterMs < 0) return;
            if (System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(retryAfterMs) > deadline) {
                throw new IOException("Confirm upload for replay " + grant.getReplayId() + " was still busy or unreachable after "
                        + TimeUnit.MILLISECONDS.toSeconds(CONFIRM_BUDGET_MS) + " seconds", lastFailure);
            }
            pause(retryAfterMs);
        }
    }

    private long confirmOnce(ReplayUploadGrant grant) throws IOException {
        HttpURLConnection connection = open(CONFIRM_PATH + UrlEncoding.encode(grant.getReplayId()));
        try {
            write(connection, new byte[0]);
            int statusCode = connection.getResponseCode();
            connections.reportIfRefused(connection, statusCode);
            if (statusCode == OK) return CONFIRMED;
            String body = HttpBodies.readQuietly(connection, statusCode);
            if (statusCode == HttpURLConnection.HTTP_UNAVAILABLE) return retryAfterMs(connection.getHeaderField(RETRY_AFTER));
            throw new ConfirmRefusedException("Confirm upload failed (HTTP " + statusCode + ") for replay " + grant.getReplayId() + ": " + body);
        } finally {
            connection.disconnect();
        }
    }

    static long retryAfterMs(@Nullable String retryAfter) {
        if (retryAfter == null || !DELAY_SECONDS.matcher(retryAfter.trim()).matches()) return DEFAULT_RETRY_MS;
        long advised = TimeUnit.SECONDS.toMillis(Long.parseLong(retryAfter.trim()));
        return Math.max(MIN_RETRY_MS, Math.min(advised, MAX_RETRY_MS));
    }

    private static void pause(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while waiting to confirm a replay upload");
        }
    }

    @Override
    public void abandonUpload(ReplayUploadGrant grant) {
    }

    private ReplayUploadGrant parseGrant(String responseBody) throws IOException {
        JsonObject json;
        try {
            json = gson.fromJson(responseBody, JsonObject.class);
        } catch (JsonSyntaxException malformed) {
            throw new IOException("Malformed init response: " + responseBody, malformed);
        }
        if (json == null || !isString(json, "replayId") || !isString(json, "uploadUrl")) {
            throw new IOException("Malformed init response: " + responseBody);
        }
        Map<String, String> requiredHeaders = new LinkedHashMap<>();
        if (json.has("requiredHeaders") && json.get("requiredHeaders").isJsonObject()) {
            for (Map.Entry<String, JsonElement> header : json.getAsJsonObject("requiredHeaders").entrySet()) {
                if (header.getValue() != null && header.getValue().isJsonPrimitive()) {
                    requiredHeaders.put(header.getKey(), header.getValue().getAsString());
                }
            }
        }
        return new ReplayUploadGrant(json.get("replayId").getAsString(), json.get("uploadUrl").getAsString(),
                isString(json, "method") ? json.get("method").getAsString() : null, requiredHeaders);
    }

    private HttpURLConnection open(String path) throws IOException {
        HttpURLConnection connection = connections.open(path);
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setDoOutput(true);
        return connection;
    }

    private static void write(HttpURLConnection connection, byte[] body) throws IOException {
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body);
        }
    }

    private static boolean isString(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonPrimitive();
    }

    private static final class ConfirmRefusedException extends IOException {
        ConfirmRefusedException(String message) {
            super(message);
        }
    }
}
