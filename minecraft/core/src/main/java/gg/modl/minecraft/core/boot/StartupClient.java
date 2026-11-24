package gg.modl.minecraft.core.boot;

import com.google.gson.Gson;
import gg.modl.minecraft.api.http.request.StartupRequest;
import gg.modl.minecraft.api.http.response.StartupResponse;
import gg.modl.minecraft.core.connection.BackendConnections;
import gg.modl.minecraft.core.connection.PanelUrl;

import java.io.IOException;

final class StartupClient implements StartupCall {
    private static final String STARTUP_PATH = "/v2/minecraft/startup";
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int READ_TIMEOUT_MILLIS = 30_000;

    private final BackendConnections connections;
    private final StartupRequest request;
    private final Gson gson = new Gson();

    StartupClient(BackendConnections connections, StartupRequest request) {
        this.connections = connections;
        this.request = request;
    }

    @Override
    public String serverUrl() {
        return connections.baseUrl();
    }

    @Override
    public StartupResponse call() throws IOException {
        JsonPostSupport.JsonResponse reply;
        try {
            reply = JsonPostSupport.postJson(connections, STARTUP_PATH, CONNECT_TIMEOUT_MILLIS, READ_TIMEOUT_MILLIS,
                    gson.toJson(request));
        } catch (HttpStatusException rejected) {
            if (rejected.isCredentialRejection()) throw new CredentialsRejectedException(rejected);
            throw rejected;
        }
        StartupResponse response;
        try {
            response = gson.fromJson(reply.body(), StartupResponse.class);
        } catch (RuntimeException malformed) {
            throw new IOException("Malformed startup response", malformed);
        }
        if (response == null || !PanelUrl.isValid(response.getPanelUrl())) {
            throw new IOException("Startup response has no valid panelUrl");
        }
        requireCompatible(response.getModlVersion() != null ? response.getModlVersion() : reply.serverVersion());
        return response;
    }

    private void requireCompatible(String serverVersion) throws VersionMismatchFailure {
        VersionMismatchFailure mismatch = connections.mismatchWith(serverVersion);
        if (!mismatch.compatibility().isCompatible()) throw mismatch;
    }
}
