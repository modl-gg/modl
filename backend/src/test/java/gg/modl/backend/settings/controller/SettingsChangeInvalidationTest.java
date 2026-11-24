package gg.modl.backend.settings.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.realtime.publish.RealtimeEventPublisher;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import gg.modl.proto.modl.v1.PanelResource;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SettingsChangeInvalidationTest {
    private final RealtimeEventPublisher realtime = mock(RealtimeEventPublisher.class);
    private final SettingsChangeInvalidation invalidation = new SettingsChangeInvalidation(new SettingsInvalidationPublisher(realtime));
    private final Server server = TestServers.server("modl", "owner@example.com");

    @Test
    void aSuccessfulChangeToAnySettingsAreaTellsOtherPanels() {
        for (String path : new String[] {"/v1/panel/settings/storage/limits", "/v1/panel/settings/sign-in",
            "/v1/panel/settings/player-addresses", "/v1/panel/settings/data-retention/limits", "/v1/panel/settings/public-theme",
            "/v1/panel/settings/public-css", "/v1/panel/updates/settings"}) {
            invalidation.afterCompletion(request("PUT", path), response(200), new Object(), null);
        }

        verify(realtime, times(7)).invalidatePanel(server, PanelResource.PANEL_RESOURCE_SETTINGS);
    }

    @Test
    void readsAndRefusedChangesStaySilent() {
        invalidation.afterCompletion(request("GET", "/v1/panel/settings/storage"), response(200), new Object(), null);
        invalidation.afterCompletion(request("PATCH", "/v1/panel/settings/storage/limits"), response(409), new Object(), null);
        invalidation.afterCompletion(request("PATCH", "/v1/panel/settings/storage/limits"), response(200), new Object(),
            new IllegalStateException("failed"));

        verify(realtime, never()).invalidatePanel(server, PanelResource.PANEL_RESOURCE_SETTINGS);
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setAttribute(RequestAttribute.SERVER, server);
        return request;
    }

    private static MockHttpServletResponse response(int status) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(status);
        return response;
    }
}
