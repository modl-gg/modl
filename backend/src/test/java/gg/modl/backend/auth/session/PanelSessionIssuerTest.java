package gg.modl.backend.auth.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.AuthConfiguration;
import gg.modl.backend.infrastructure.util.CookieUtil;
import gg.modl.backend.infrastructure.web.HttpOrigin;
import gg.modl.backend.instance.InstanceService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PanelSessionIssuerTest {
    private static final Server SERVER = TestServers.server("Alpha", "owner@example.com");

    private final InstanceService instanceService = mock(InstanceService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final AuthConfiguration authConfiguration = new AuthConfiguration();
    private final PanelSessionIssuer issuer = new PanelSessionIssuer(new CookieUtil(authConfiguration), instanceService, eventPublisher);

    @Test
    void ownerLoginCapturesTheOriginItArrivedOn() {
        issuer.publish(SERVER, sessionFor("owner@example.com"), request(), new MockHttpServletResponse());

        verify(instanceService).capturePublicUrlIfUnknown(new HttpOrigin("https", "panel.example.com", 8443));
    }

    @Test
    void ownerLoginPrefersTheBrowserOriginWhenItNamesTheSameHost() {
        MockHttpServletRequest request = request();
        request.addHeader("Origin", "https://panel.example.com");

        issuer.publish(SERVER, sessionFor("owner@example.com"), request, new MockHttpServletResponse());

        verify(instanceService).capturePublicUrlIfUnknown(new HttpOrigin("https", "panel.example.com", -1));
    }

    @Test
    void ownerLoginIgnoresBrowserOriginsForOtherHosts() {
        MockHttpServletRequest request = request();
        request.addHeader("Origin", "https://elsewhere.example.net");

        issuer.publish(SERVER, sessionFor("owner@example.com"), request, new MockHttpServletResponse());

        verify(instanceService).capturePublicUrlIfUnknown(new HttpOrigin("https", "panel.example.com", 8443));
    }

    @Test
    void ownerLoginFromALoopbackAddressIsNeverCaptured() {
        MockHttpServletRequest request = request();
        request.setServerName("127.0.0.1");
        request.addHeader("Origin", "http://127.0.0.1:8443");

        issuer.publish(SERVER, sessionFor("owner@example.com"), request, new MockHttpServletResponse());

        verify(instanceService, never()).capturePublicUrlIfUnknown(any());
    }

    @Test
    void captureFailureNeverFailsTheLogin() {
        when(instanceService.capturePublicUrlIfUnknown(any())).thenThrow(new IllegalStateException("database unavailable"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        issuer.publish(SERVER, sessionFor("owner@example.com"), request(), response);

        assertEquals("session-id", response.getCookie(authConfiguration.getSessionCookieName()).getValue());
    }

    @Test
    void issuedSessionToStringRevealsNoSecrets() {
        IssuedSession issued = sessionFor("staff@example.com");

        String rendered = issued.toString();

        assertFalse(rendered.contains("session-key"));
        assertFalse(rendered.contains("session-id"));
        assertFalse(rendered.contains("digest"));
    }

    @Test
    void staffLoginNeverCapturesThePublicUrl() {

        issuer.publish(SERVER, sessionFor("staff@example.com"), request(), new MockHttpServletResponse());

        verify(instanceService, never()).capturePublicUrlIfUnknown(any());
    }

    @Test
    void onlyAnOwnerSessionAnnouncesTheOwnerSignIn() {
        issuer.publish(SERVER, sessionFor("staff@example.com"), request(), new MockHttpServletResponse());
        verify(eventPublisher, never()).publishEvent(any(Object.class));

        issuer.publish(SERVER, sessionFor("owner@example.com"), request(), new MockHttpServletResponse());
        verify(eventPublisher).publishEvent(new OwnerSessionIssuedEvent());
    }

    @Test
    void issuedSessionCookieCarriesTheSessionIdNotTheKey() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        issuer.publish(SERVER, sessionFor("staff@example.com"), request(), response);

        assertEquals("session-id", response.getCookie(authConfiguration.getSessionCookieName()).getValue());
    }

    private static IssuedSession sessionFor(String normalizedEmail) {
        AuthSessionData session = new AuthSessionData("session-id", normalizedEmail, Instant.now(), Instant.now(), null, null, "digest",
            AuthMethod.EMAIL_CODE, false);
        return new IssuedSession(session, "session-key");
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/panel/auth/verify-email-code");
        request.setScheme("https");
        request.setServerName("panel.example.com");
        request.setServerPort(8443);
        return request;
    }
}
