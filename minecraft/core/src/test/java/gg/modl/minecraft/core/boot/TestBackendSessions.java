package gg.modl.minecraft.core.boot;

import gg.modl.minecraft.api.http.response.StartupResponse;
import gg.modl.minecraft.core.connection.BackendEndpoint;
import gg.modl.minecraft.core.support.TestConnections;

import java.util.Collections;

public final class TestBackendSessions {
    private static final BackendEndpoint UNREACHABLE = BackendEndpoint.of("http://127.0.0.1:1", "modl_key");
    private static final String PANEL_URL = "https://panel.example.test";

    private TestBackendSessions() {
    }

    public static BackendSession unreachable() {
        return withoutRealtime(UNREACHABLE, PANEL_URL);
    }

    public static BackendSession withoutRealtime(BackendEndpoint endpoint, String panelUrl) {
        StartupResponse startup = new StartupResponse(panelUrl, null, "srv-test", false, null,
                Collections.<String>emptyList());
        return new BackendSession(TestConnections.to(endpoint), startup, null);
    }
}
