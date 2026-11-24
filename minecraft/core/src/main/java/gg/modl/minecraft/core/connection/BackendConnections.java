package gg.modl.minecraft.core.connection;

import gg.modl.minecraft.core.boot.VersionMismatchFailure;
import gg.modl.minecraft.core.boot.VersionMismatchReports;
import gg.modl.minecraft.core.util.HttpConnectionOpener;
import gg.modl.update.CompatibilityHeaders;
import gg.modl.update.VersionCompatibility;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class BackendConnections {
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String USER_AGENT_HEADER = "User-Agent";

    private final BackendEndpoint endpoint;
    private final BackendClientIdentity identity;
    private final VersionMismatchReports mismatches;

    public BackendConnections(BackendEndpoint endpoint, BackendClientIdentity identity, VersionMismatchReports mismatches) {
        this.endpoint = endpoint;
        this.identity = identity;
        this.mismatches = mismatches;
    }

    public BackendEndpoint endpoint() {
        return endpoint;
    }

    public String baseUrl() {
        return endpoint.baseUrl();
    }

    public String resolve(String path) {
        return endpoint.resolve(path);
    }

    public HttpURLConnection open(String path) throws IOException {
        HttpURLConnection connection = HttpConnectionOpener.SYSTEM.open(new URL(endpoint.resolve(path)));
        for (Map.Entry<String, String> header : requestHeaders().entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        return connection;
    }

    public Map<String, String> requestHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(API_KEY_HEADER, endpoint.getApiKey());
        headers.put(USER_AGENT_HEADER, "modl-minecraft/" + identity.getPluginVersion());
        headers.putAll(identity.compatibilityHeaders());
        return headers;
    }

    public Optional<VersionMismatchFailure> refusal(HttpURLConnection connection, int statusCode) {
        if (statusCode != CompatibilityHeaders.REFUSAL_STATUS) return Optional.empty();
        String compatibility = connection.getHeaderField(CompatibilityHeaders.COMPATIBILITY);
        if (compatibility == null) return Optional.empty();
        return Optional.of(new VersionMismatchFailure(VersionCompatibility.fromHeader(compatibility),
                identity.getPluginVersion(), connection.getHeaderField(CompatibilityHeaders.SERVER_VERSION), baseUrl()));
    }

    public void reportIfRefused(HttpURLConnection connection, int statusCode) {
        Optional<VersionMismatchFailure> refusal = refusal(connection, statusCode);
        if (refusal.isPresent()) mismatches.report(refusal.get());
    }

    public VersionMismatchFailure mismatchWith(String serverVersion) {
        return new VersionMismatchFailure(VersionCompatibility.between(identity.getPluginVersion(), serverVersion),
                identity.getPluginVersion(), serverVersion, baseUrl());
    }

    public void report(VersionMismatchFailure mismatch) {
        mismatches.report(mismatch);
    }
}
