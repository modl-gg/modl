package gg.modl.backend.auth.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.AuthConfiguration;
import gg.modl.backend.auth.session.AuthMethod;
import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.auth.session.SessionService;
import gg.modl.backend.auth.signin.SecondFactorDecision;
import gg.modl.backend.auth.signin.SecondFactorPolicy;
import gg.modl.backend.infrastructure.filter.SessionAuthenticationFilter;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.infrastructure.rest.RequestHeader;
import gg.modl.backend.infrastructure.util.CookieUtil;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class SessionAuthenticationFilterTest {
    private static final String COOKIE_NAME = "MODL_SESSION";
    private static final String SESSION_ID = "token-123";
    private static final String SESSION_KEY = "session-key-abc";
    private static final Server SERVER = TestServers.server("Alpha", "admin@example.com");

    private final SessionService sessionService = mock(SessionService.class);
    private final SecondFactorPolicy secondFactorPolicy = mock(SecondFactorPolicy.class);
    private final SessionAuthenticationFilter filter = newFilter(sessionService, secondFactorPolicy);

    @BeforeEach
    void admitByDefault() {
        when(secondFactorPolicy.decide(any(), any(), any())).thenReturn(SecondFactorDecision.ADMIT);
    }

    @Test
    void aSessionWaitingForEnrolmentIsRefusedOnPanelRoutesWithItsCode() throws Exception {
        when(sessionService.authenticateAllowingPendingSecondFactor(SERVER, SESSION_ID, SESSION_KEY)).thenReturn(Optional.of(activeSession()));
        when(secondFactorPolicy.decide(any(), any(), any())).thenReturn(SecondFactorDecision.ENROLLMENT_REQUIRED);
        MockHttpServletRequest request = panelRequest("/v1/panel/tickets");
        request.addHeader(RequestHeader.SESSION_KEY, SESSION_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"code\":\"SECOND_FACTOR_ENROLLMENT_REQUIRED\""));
        assertNull(chain.getRequest());
        assertNull(request.getAttribute(RequestAttribute.SESSION));
    }

    @Test
    void aRestrictedSessionIsAnonymousOnPublicRoutes() throws Exception {
        when(sessionService.authenticateAllowingPendingSecondFactor(SERVER, SESSION_ID, SESSION_KEY)).thenReturn(Optional.of(activeSession()));
        when(secondFactorPolicy.decide(any(), any(), any())).thenReturn(SecondFactorDecision.ANONYMOUS);
        MockHttpServletRequest request = panelRequest("/v1/public/tickets/t1");
        request.addHeader(RequestHeader.SESSION_KEY, SESSION_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest());
        assertNull(request.getAttribute(RequestAttribute.SESSION));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNull(response.getCookie(COOKIE_NAME));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void refreshesSessionCookieAsSecureHostOnlyLaxOverHttps() throws Exception {
        Cookie refreshedCookie = refreshCookie(true);

        assertEquals(SESSION_ID, refreshedCookie.getValue());
        assertEquals((int) AuthConfiguration.MIN_SESSION_DURATION_SECONDS, refreshedCookie.getMaxAge());
        assertNull(refreshedCookie.getDomain());
        assertEquals("/", refreshedCookie.getPath());
        assertTrue(refreshedCookie.isHttpOnly());
        assertTrue(refreshedCookie.getSecure());
        assertEquals("Lax", refreshedCookie.getAttribute("SameSite"));
    }

    @Test
    void refreshesSessionCookieWithoutSecureFlagOverPlainHttp() throws Exception {
        Cookie refreshedCookie = refreshCookie(false);

        assertFalse(refreshedCookie.getSecure());
        assertNull(refreshedCookie.getDomain());
        assertEquals("Lax", refreshedCookie.getAttribute("SameSite"));
    }

    @Test
    void cookieWithoutSessionKeyHeaderLeavesTheRequestUnauthenticated() throws Exception {
        MockHttpServletRequest request = panelRequest("/v1/panel/tickets");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertNull(request.getAttribute(RequestAttribute.SESSION));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNull(response.getCookie(COOKIE_NAME));
        verify(sessionService, never()).authenticateAllowingPendingSecondFactor(any(), anyString(), anyString());
    }

    @Test
    void wrongSessionKeyLeavesTheRequestUnauthenticated() throws Exception {
        when(sessionService.authenticateAllowingPendingSecondFactor(SERVER, SESSION_ID, "stolen-cookie-guess")).thenReturn(Optional.empty());
        MockHttpServletRequest request = panelRequest("/v1/panel/tickets");
        request.addHeader(RequestHeader.SESSION_KEY, "stolen-cookie-guess");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertNull(request.getAttribute(RequestAttribute.SESSION));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNull(response.getCookie(COOKIE_NAME));
    }

    @Test
    void matchingSessionKeyAuthenticatesThePanelUser() throws Exception {
        AuthSessionData session = activeSession();
        when(sessionService.authenticateAllowingPendingSecondFactor(SERVER, SESSION_ID, SESSION_KEY)).thenReturn(Optional.of(session));
        MockHttpServletRequest request = panelRequest("/v1/panel/tickets");
        request.addHeader(RequestHeader.SESSION_KEY, SESSION_KEY);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertEquals(session, request.getAttribute(RequestAttribute.SESSION));
        assertEquals("staff@example.com", SecurityContextHolder.getContext().getAuthentication().getName());
    }

    @Test
    void triesEveryCandidateSessionCookieUpToTheCap() throws Exception {
        AuthSessionData session = activeSession();
        when(sessionService.authenticateAllowingPendingSecondFactor(SERVER, "candidate-4", SESSION_KEY)).thenReturn(Optional.of(session));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/panel/tickets");
        request.setAttribute(RequestAttribute.SERVER, SERVER);
        request.setCookies(
            new Cookie(COOKIE_NAME, "candidate-1"),
            new Cookie(COOKIE_NAME, "candidate-2"),
            new Cookie(COOKIE_NAME, "candidate-3"),
            new Cookie(COOKIE_NAME, "candidate-4"),
            new Cookie(COOKIE_NAME, "candidate-5"));
        request.addHeader(RequestHeader.SESSION_KEY, SESSION_KEY);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertEquals(session, request.getAttribute(RequestAttribute.SESSION));
        verify(sessionService, never()).authenticateAllowingPendingSecondFactor(SERVER, "candidate-5", SESSION_KEY);
    }

    private Cookie refreshCookie(boolean secureRequest) throws Exception {
        when(sessionService.authenticateAllowingPendingSecondFactor(SERVER, SESSION_ID, SESSION_KEY)).thenReturn(Optional.of(activeSession()));

        MockHttpServletRequest request = panelRequest("/v1/panel/auth/me");
        request.setSecure(secureRequest);
        request.addHeader(RequestHeader.SESSION_KEY, SESSION_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        verify(sessionService).authenticateAllowingPendingSecondFactor(SERVER, SESSION_ID, SESSION_KEY);
        Cookie refreshedCookie = response.getCookie(COOKIE_NAME);
        assertNotNull(refreshedCookie);
        return refreshedCookie;
    }

    private static MockHttpServletRequest panelRequest(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setAttribute(RequestAttribute.SERVER, SERVER);
        request.setCookies(new Cookie(COOKIE_NAME, SESSION_ID));
        return request;
    }

    private static AuthSessionData activeSession() {
        return new AuthSessionData(
            SESSION_ID, "staff@example.com", Instant.now(), Instant.ofEpochMilli(System.currentTimeMillis() + 1000), null, null, "digest",
            AuthMethod.EMAIL_CODE, false);
    }

    private static SessionAuthenticationFilter newFilter(SessionService sessionService, SecondFactorPolicy secondFactorPolicy) {
        AuthConfiguration authConfiguration = new AuthConfiguration();
        authConfiguration.setSessionCookieName(COOKIE_NAME);
        return new SessionAuthenticationFilter(sessionService, new CookieUtil(authConfiguration), secondFactorPolicy);
    }
}
