package gg.modl.minecraft.core.connection;

import gg.modl.minecraft.core.boot.PlatformType;
import gg.modl.minecraft.core.boot.VersionMismatchFailure;
import gg.modl.minecraft.core.boot.VersionMismatchReports;
import gg.modl.minecraft.core.plugin.PluginInfo;
import gg.modl.update.CompatibilityHeaders;
import gg.modl.update.VersionCompatibility;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendConnectionsTest {
    private static final String BASE_URL = "http://127.0.0.1:8080/modl";

    private final VersionMismatchReports mismatches = new VersionMismatchReports();
    private final BackendConnections connections = new BackendConnections(BackendEndpoint.of(BASE_URL, "modl_key"),
            BackendClientIdentity.newBoot(PlatformType.VELOCITY), mismatches);

    @Test
    void everyRequestCarriesTheKeyAndThisBootsCompatibilityHeaders() {
        Map<String, String> headers = connections.requestHeaders();

        assertEquals("modl_key", headers.get("X-API-Key"));
        assertEquals("modl-minecraft/" + PluginInfo.VERSION, headers.get("User-Agent"));
        assertEquals(PluginInfo.VERSION, headers.get(CompatibilityHeaders.PLUGIN_VERSION));
        assertEquals("VELOCITY", headers.get(CompatibilityHeaders.PLATFORM));
        assertEquals(headers.get(CompatibilityHeaders.BOOT_ID), connections.requestHeaders().get(CompatibilityHeaders.BOOT_ID));
    }

    @Test
    void everyBootHasItsOwnBootId() {
        BackendConnections otherBoot = new BackendConnections(BackendEndpoint.of(BASE_URL, "modl_key"),
                BackendClientIdentity.newBoot(PlatformType.VELOCITY), mismatches);

        assertNotEquals(connections.requestHeaders().get(CompatibilityHeaders.BOOT_ID),
                otherBoot.requestHeaders().get(CompatibilityHeaders.BOOT_ID));
    }

    @Test
    void pathsResolveAgainstTheEndpoint() {
        assertEquals(BASE_URL + "/v1/minecraft/replays/upload", connections.resolve("/v1/minecraft/replays/upload"));
    }

    @Test
    void onlyAConflictThatNamesTheCompatibilityIsARefusal() throws MalformedURLException {
        assertFalse(connections.refusal(answer(409, Collections.<String, String>emptyMap()), 409).isPresent());
        assertFalse(connections.refusal(answer(400, compatibility("PLUGIN_OLDER")), 400).isPresent());

        VersionMismatchFailure refusal = connections.refusal(answer(409, compatibility("PLUGIN_OLDER")), 409).get();

        assertEquals(VersionCompatibility.PLUGIN_OLDER, refusal.compatibility());
        assertEquals("3.9.0", refusal.serverVersion());
        assertTrue(refusal.reason().contains(BASE_URL));
    }

    @Test
    void aRefusalIsReportedOnlyToTheReportsOfItsOwnLifecycle() throws MalformedURLException {
        List<VersionMismatchFailure> own = new ArrayList<>();
        List<VersionMismatchFailure> other = new ArrayList<>();
        mismatches.listen(own::add);
        new VersionMismatchReports().listen(other::add);

        connections.reportIfRefused(answer(409, compatibility("SERVER_OLDER")), 409);
        connections.reportIfRefused(answer(200, Collections.<String, String>emptyMap()), 200);

        assertEquals(1, own.size());
        assertEquals(VersionCompatibility.SERVER_OLDER, own.get(0).compatibility());
        assertTrue(other.isEmpty());
    }

    private static Map<String, String> compatibility(String value) {
        Map<String, String> headers = new HashMap<>();
        headers.put(CompatibilityHeaders.COMPATIBILITY, value);
        headers.put(CompatibilityHeaders.SERVER_VERSION, "3.9.0");
        return headers;
    }

    private static HttpURLConnection answer(int status, Map<String, String> headers) throws MalformedURLException {
        return new HttpURLConnection(URI.create(BASE_URL).toURL()) {
            @Override
            public int getResponseCode() {
                return status;
            }

            @Override
            public String getHeaderField(String name) {
                return headers.get(name);
            }

            @Override
            public void connect() {
            }

            @Override
            public void disconnect() {
            }

            @Override
            public boolean usingProxy() {
                return false;
            }
        };
    }
}
