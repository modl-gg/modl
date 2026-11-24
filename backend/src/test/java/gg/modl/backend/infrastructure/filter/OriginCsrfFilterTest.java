package gg.modl.backend.infrastructure.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.AuthConfiguration;
import gg.modl.backend.infrastructure.web.HttpOrigin;
import gg.modl.backend.infrastructure.web.SameOriginPolicy;
import gg.modl.backend.instance.PublicUrls;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class OriginCsrfFilterTest {
    private static final String BLOCKED_BODY =
        "{\"success\":false,\"status\":403,\"error\":\"Cross-site request blocked\",\"message\":\"Cross-site request blocked\",\"code\":\"PERMISSION_DENIED\"}";

    private final AuthConfiguration authConfiguration = new AuthConfiguration();
    private final OriginCsrfFilter filter = new OriginCsrfFilter(authConfiguration, policy());

    @Test
    void sameOriginFetchMetadataIsAllowed() throws Exception {
        MockHttpServletRequest request = cookieRequest("POST");
        request.addHeader("Sec-Fetch-Site", "same-origin");

        assertPassed(request);
    }

    @Test
    void originMatchingTheEffectiveRequestOriginIsAllowed() throws Exception {
        MockHttpServletRequest request = cookieRequest("PATCH");
        request.addHeader("Origin", "http://192.168.1.5:8080");

        assertPassed(request);
    }

    @Test
    void originMatchingTheConfiguredPublicUrlIsAllowed() throws Exception {
        MockHttpServletRequest request = cookieRequest("DELETE");
        request.addHeader("Origin", "https://panel.example.com");

        assertPassed(request);
    }

    @Test
    void crossSiteOriginIsBlocked() throws Exception {
        MockHttpServletRequest request = cookieRequest("POST");
        request.addHeader("Origin", "https://evil.example.net");

        assertBlocked(request);
    }

    @Test
    void sameSiteOtherPortIsBlockedEvenWithFetchMetadata() throws Exception {
        MockHttpServletRequest request = cookieRequest("POST");
        request.addHeader("Sec-Fetch-Site", "same-site");
        request.addHeader("Origin", "http://192.168.1.5:8081");

        assertBlocked(request);
    }

    @Test
    void refererIsConsultedOnlyWhenOriginIsAbsent() throws Exception {
        MockHttpServletRequest sameOriginReferer = cookieRequest("POST");
        sameOriginReferer.addHeader("Referer", "http://192.168.1.5:8080/panel/tickets");
        assertPassed(sameOriginReferer);

        MockHttpServletRequest crossSiteReferer = cookieRequest("POST");
        crossSiteReferer.addHeader("Referer", "https://evil.example.net/attack");
        assertBlocked(crossSiteReferer);

        MockHttpServletRequest originWins = cookieRequest("POST");
        originWins.addHeader("Origin", "https://evil.example.net");
        originWins.addHeader("Referer", "http://192.168.1.5:8080/panel/tickets");
        assertBlocked(originWins);
    }

    @Test
    void stateChangingRequestWithoutOriginOrRefererIsBlocked() throws Exception {
        assertBlocked(cookieRequest("PUT"));
    }

    @Test
    void requestsWithoutASessionCookieAreNotChecked() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/public/tickets");
        effectiveOrigin(request);
        request.addHeader("Origin", "https://evil.example.net");

        assertPassed(request);
    }

    @Test
    void safeMethodsAreNotChecked() throws Exception {
        MockHttpServletRequest request = cookieRequest("GET");
        request.addHeader("Origin", "https://evil.example.net");

        assertPassed(request);
    }

    private void assertPassed(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(request, chain.getRequest());
        assertEquals(200, response.getStatus());
    }

    private void assertBlocked(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNull(chain.getRequest());
        assertEquals(403, response.getStatus());
        assertEquals(BLOCKED_BODY, response.getContentAsString());
        assertEquals("application/json", response.getContentType());
    }

    private MockHttpServletRequest cookieRequest(String method) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/v1/panel/settings/general");
        effectiveOrigin(request);
        request.setCookies(new Cookie(authConfiguration.getSessionCookieName(), "session-id"));
        return request;
    }

    private static void effectiveOrigin(MockHttpServletRequest request) {
        request.setScheme("http");
        request.setServerName("192.168.1.5");
        request.setServerPort(8080);
    }

    private static SameOriginPolicy policy() {
        PublicUrls publicUrls = mock(PublicUrls.class);
        when(publicUrls.publicOrigin()).thenReturn(new HttpOrigin("https", "panel.example.com", -1));
        return new SameOriginPolicy(publicUrls);
    }
}
