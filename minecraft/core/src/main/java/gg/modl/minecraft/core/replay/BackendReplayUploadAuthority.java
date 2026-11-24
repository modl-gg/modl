package gg.modl.minecraft.core.replay;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import gg.modl.minecraft.core.connection.BackendConnections;
import gg.modl.minecraft.core.util.HttpBodies;
import gg.modl.minecraft.core.util.UrlEncoding;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class BackendReplayUploadAuthority implements ReplayUploadAuthority {
    private static final String INIT_PATH = "/v1/minecraft/replays/upload";
    private static final String CONFIRM_PATH = "/v1/minecraft/replays/confirm/";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int OK = 200;

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
        HttpURLConnection connection = open(CONFIRM_PATH + UrlEncoding.encode(grant.getReplayId()));
        try {
            write(connection, new byte[0]);
            int statusCode = connection.getResponseCode();
            connections.reportIfRefused(connection, statusCode);
            if (statusCode != OK) {
                throw new IOException("Confirm upload failed (HTTP " + statusCode + ") for replay " + grant.getReplayId()
                        + ": " + HttpBodies.readQuietly(connection, statusCode));
            }
        } finally {
            connection.disconnect();
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
}
