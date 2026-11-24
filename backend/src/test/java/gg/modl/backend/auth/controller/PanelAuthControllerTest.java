package gg.modl.backend.auth.controller;

import gg.modl.backend.support.TestServers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.AccountOffboarding;
import gg.modl.backend.auth.AuthConfiguration;
import gg.modl.backend.auth.AuthService;
import gg.modl.backend.auth.EmailChangeService;
import gg.modl.backend.auth.ImmediateUnitOfWork;
import gg.modl.backend.auth.LoginCodeRequests;
import gg.modl.backend.auth.link.PanelLoginLinkService;
import gg.modl.backend.auth.repository.PanelSession;
import gg.modl.backend.auth.repository.StaffTotpRepository;
import gg.modl.backend.auth.session.AuthMethod;
import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.auth.session.IssuedSession;
import gg.modl.backend.auth.session.PanelSessionIssuer;
import gg.modl.backend.auth.session.SessionPublicId;
import gg.modl.backend.auth.session.SessionService;
import gg.modl.backend.auth.settings.SignInSettingsService;
import gg.modl.backend.auth.signin.Reauthentication;
import gg.modl.backend.auth.signin.SecondFactorPolicy;
import gg.modl.backend.auth.signin.SignInAttempt;
import gg.modl.backend.auth.signin.SignInClient;
import gg.modl.backend.auth.signin.SignInClientResolver;
import gg.modl.backend.auth.signin.SignInCompletion;
import gg.modl.backend.auth.signin.SignInOutcome;
import gg.modl.backend.email.EmailService;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.infrastructure.util.CookieUtil;
import gg.modl.backend.infrastructure.web.ClientIpResolver;
import gg.modl.backend.instance.InstanceProperties;
import gg.modl.backend.instance.InstanceService;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.service.StaffLookupCache;
import gg.modl.backend.staff.service.StaffProfileService;
import gg.modl.proto.modl.v1.PanelAuthResponse;
import gg.modl.proto.modl.v1.PanelSendEmailCodeRequest;
import gg.modl.proto.modl.v1.PanelSessionsResponse;
import gg.modl.proto.modl.v1.PanelUpdateEmailWithCodeRequest;
import gg.modl.proto.modl.v1.PanelVerifyEmailCodeRequest;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PanelAuthControllerTest {
    private static final InstanceProperties NO_PROXY = new InstanceProperties(null, null, null, null, null, null);

    private final LoginCodeRequests loginCodeRequests = mock(LoginCodeRequests.class);
    private final SignInCompletion signInCompletion = mock(SignInCompletion.class);
    private final SignInSettingsService signInSettings = mock(SignInSettingsService.class);

    @Test
    void logoutInvalidatesOnlyTheKeyVerifiedSessionAndExpiresTheCookie() {
        AuthConfiguration authConfiguration = new AuthConfiguration();
        authConfiguration.setSessionCookieName("MODL_SESSION");
        SessionService sessionService = mock(SessionService.class);
        PanelAuthController controller = createController(authConfiguration, sessionService);
        Server server = server();
        AuthSessionData session = new AuthSessionData("session-1", "staff@example.com", Instant.now(), Instant.now(), null, null, "digest", AuthMethod.EMAIL_CODE, false);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/panel/auth/logout");
        request.setAttribute(RequestAttribute.SERVER, server);
        request.setAttribute(RequestAttribute.SESSION, session);
        request.setCookies(
            new Cookie("MODL_SESSION", "session-1"),
            new Cookie("MODL_SESSION", "someone-elses-session")
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.logout(request, response);

        verify(sessionService).invalidateSession(server, "session-1");
        verify(sessionService, never()).invalidateSession(server, "someone-elses-session");
        verify(sessionService, never()).invalidateAllSessionsForEmail(any(Server.class), eq("staff@example.com"));
        assertEquals(0, response.getCookie("MODL_SESSION").getMaxAge());
    }

    @Test
    void logoutWithoutAVerifiedSessionOnlyExpiresTheCookie() {
        AuthConfiguration authConfiguration = new AuthConfiguration();
        authConfiguration.setSessionCookieName("MODL_SESSION");
        SessionService sessionService = mock(SessionService.class);
        PanelAuthController controller = createController(authConfiguration, sessionService);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/panel/auth/logout");
        request.setAttribute(RequestAttribute.SERVER, server());
        request.setCookies(new Cookie("MODL_SESSION", "stolen-cookie"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.logout(request, response);

        verify(sessionService, never()).invalidateSession(any(Server.class), anyString());
        assertEquals(0, response.getCookie("MODL_SESSION").getMaxAge());
    }

    @Test
    void emailChangeMovesTheCredentialsAndRevokesTheRestOfTheOldAddress() {
        AccountOffboarding accountOffboarding = mock(AccountOffboarding.class);
        AuthConfiguration authConfiguration = new AuthConfiguration();
        authConfiguration.setSessionCookieName("MODL_SESSION");
        AuthService authService = mock(AuthService.class);
        SessionService sessionService = mock(SessionService.class);
        StaffProfileService staffProfileService = mock(StaffProfileService.class);
        PermissionService permissionService = mock(PermissionService.class);
        EmailChangeService emailChangeService = new EmailChangeService(
            permissionService,
            authService,
            staffProfileService,
            mock(InstanceService.class),
            new InstanceProperties(null, null, null, null, null, null),
            accountOffboarding,
            sessionService,
            mock(EmailService.class),
            mock(StaffTotpRepository.class),
            mock(Reauthentication.class),
            new ImmediateUnitOfWork()
        );
        PanelAuthController controller = controller(authService, sessionService, staffProfileService, permissionService, emailChangeService, authConfiguration);
        Server server = server();
        Staff staff = new Staff();
        staff.setEmail("new@example.com");
        AuthSessionData currentSession = new AuthSessionData("old-session", "old@example.com", Instant.now(), Instant.now(), null, null, null, AuthMethod.EMAIL_CODE, false);
        AuthSessionData newSession = new AuthSessionData("new-session", "new@example.com", Instant.now(), Instant.now(), null, null, null, AuthMethod.EMAIL_CODE, false);
        MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/v1/panel/auth/email");
        request.setAttribute(RequestAttribute.SERVER, server);
        request.setAttribute(RequestAttribute.SESSION, currentSession);
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(permissionService.isSuperAdmin(server, "old@example.com")).thenReturn(false);
        when(staffProfileService.isStaffEmailInUse(server, "new@example.com", "old@example.com")).thenReturn(false);
        when(authService.verifyCode(server, "new@example.com", "123456")).thenReturn(true);
        when(staffProfileService.applyStaffEmailChange(server, "old@example.com", "new@example.com")).thenReturn(Optional.of(staff));
        when(sessionService.createSession(server, "new@example.com", AuthMethod.EMAIL_CODE, false, "127.0.0.1", null))
            .thenReturn(new IssuedSession(newSession, "new-session-key"));

        PanelAuthResponse body = controller.updateEmail(request, response, PanelUpdateEmailWithCodeRequest.newBuilder()
            .setNewEmail("new@example.com")
            .setCode("123456")
            .build()).getBody();

        verify(accountOffboarding).transferIdentity(server, "old@example.com", "new@example.com");
        verify(sessionService).createSession(server, "new@example.com", AuthMethod.EMAIL_CODE, false, "127.0.0.1", null);
        assertEquals("new-session-key", body.getSessionKey());
        assertEquals("new-session", response.getCookie("MODL_SESSION").getValue());
    }

    @Test
    void revokeSessionReturnsNotFoundWhenPublicIdUnknown() {
        AuthConfiguration authConfiguration = new AuthConfiguration();
        authConfiguration.setSessionCookieName("MODL_SESSION");
        SessionService sessionService = mock(SessionService.class);
        PanelAuthController controller = createController(authConfiguration, sessionService);
        Server server = server();
        AuthSessionData currentSession = new AuthSessionData("session-1", "staff@example.com", Instant.now(), Instant.now(), null, null, null, AuthMethod.EMAIL_CODE, false);
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/v1/panel/auth/sessions/pub-unknown");
        request.setAttribute(RequestAttribute.SERVER, server);
        request.setAttribute(RequestAttribute.SESSION, currentSession);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertEquals(404, controller.revokeSession(request, response, "pub-unknown").getStatusCode().value());
        verify(sessionService).revokeOwnSession(server, "staff@example.com", "pub-unknown");
        assertEquals(0, response.getCookies().length);
    }

    @Test
    void revokeSessionInvalidatesResolvedSessionWithoutClearingCookieForOtherDevice() {
        AuthConfiguration authConfiguration = new AuthConfiguration();
        authConfiguration.setSessionCookieName("MODL_SESSION");
        SessionService sessionService = mock(SessionService.class);
        PanelAuthController controller = createController(authConfiguration, sessionService);
        Server server = server();
        AuthSessionData currentSession = new AuthSessionData("session-1", "staff@example.com", Instant.now(), Instant.now(), null, null, null, AuthMethod.EMAIL_CODE, false);
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/v1/panel/auth/sessions/pub-2");
        request.setAttribute(RequestAttribute.SERVER, server);
        request.setAttribute(RequestAttribute.SESSION, currentSession);
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(sessionService.revokeOwnSession(server, "staff@example.com", "pub-2")).thenReturn(true);

        assertEquals(200, controller.revokeSession(request, response, "pub-2").getStatusCode().value());
        assertEquals(0, response.getCookies().length);
    }

    @Test
    void revokeCurrentSessionClearsCookie() {
        AuthConfiguration authConfiguration = new AuthConfiguration();
        authConfiguration.setSessionCookieName("MODL_SESSION");
        SessionService sessionService = mock(SessionService.class);
        PanelAuthController controller = createController(authConfiguration, sessionService);
        Server server = server();
        AuthSessionData currentSession = new AuthSessionData("session-1", "staff@example.com", Instant.now(), Instant.now(), null, null, null, AuthMethod.EMAIL_CODE, false);
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/v1/panel/auth/sessions/pub-1");
        request.setAttribute(RequestAttribute.SERVER, server);
        request.setAttribute(RequestAttribute.SESSION, currentSession);
        MockHttpServletResponse response = new MockHttpServletResponse();

        String currentPublicId = SessionPublicId.of("session-1");
        when(sessionService.revokeOwnSession(server, "staff@example.com", currentPublicId)).thenReturn(true);

        assertEquals(200, controller.revokeSession(request, response, currentPublicId).getStatusCode().value());
        assertTrue(response.getCookies().length > 0);
        for (Cookie cookie : response.getCookies()) {
            assertEquals(0, cookie.getMaxAge());
        }
    }

    @Test
    void theSessionListShowsStoredPublicIdsAndMarksTheCurrentOne() {
        SessionService sessionService = mock(SessionService.class);
        PanelAuthController controller = createController(new AuthConfiguration(), sessionService);
        Server server = server();
        Instant createdAt = Instant.parse("2026-11-21T12:00:00.750Z");
        String currentPublicId = SessionPublicId.of("session-1");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/panel/auth/sessions");
        request.setAttribute(RequestAttribute.SERVER, server);
        request.setAttribute(RequestAttribute.SESSION,
            new AuthSessionData("session-1", "staff@example.com", createdAt, createdAt, null, null, "digest", AuthMethod.EMAIL_CODE, false));
        when(sessionService.findAllSessionsForEmail(server, "staff@example.com")).thenReturn(List.of(
            new PanelSession(currentPublicId, "staff@example.com", "digest", "127.0.0.1", "Browser", createdAt, createdAt,
                AuthMethod.EMAIL_CODE, false),
            new PanelSession("other-device", "staff@example.com", "digest", null, null, createdAt, createdAt,
                AuthMethod.PASSWORD, true)));

        PanelSessionsResponse body = (PanelSessionsResponse) controller.getSessions(request).getBody();

        assertEquals(currentPublicId, body.getSessions(0).getId());
        assertTrue(body.getSessions(0).getIsCurrent());
        assertEquals("2026-11-21T12:00:00Z", body.getSessions(0).getCreatedAt());
        assertEquals("other-device", body.getSessions(1).getId());
        assertFalse(body.getSessions(1).getIsCurrent());
    }

    @Test
    void revokeAllSessionsClearsEveryEmailSessionAndCookie() {
        AuthConfiguration authConfiguration = new AuthConfiguration();
        authConfiguration.setSessionCookieName("MODL_SESSION");
        SessionService sessionService = mock(SessionService.class);
        PanelAuthController controller = createController(authConfiguration, sessionService);
        Server server = server();
        AuthSessionData currentSession = new AuthSessionData("session-1", "staff@example.com", Instant.now(), Instant.now(), null, null, null, AuthMethod.EMAIL_CODE, false);
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/v1/panel/auth/sessions");
        request.setAttribute(RequestAttribute.SERVER, server);
        request.setAttribute(RequestAttribute.SESSION, currentSession);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertEquals(200, controller.revokeAllSessions(request, response).getStatusCode().value());
        verify(sessionService).invalidateAllSessionsForEmail(server, "staff@example.com");
        assertTrue(response.getCookies().length > 0);
    }

    @Test
    void verifiedEmailCodeReturnsTheIssuedSessionKey() {
        AuthConfiguration authConfiguration = new AuthConfiguration();
        authConfiguration.setSessionCookieName("MODL_SESSION");
        SessionService sessionService = mock(SessionService.class);
        AuthService authService = mock(AuthService.class);
        PermissionService permissionService = mock(PermissionService.class);
        PanelAuthController controller = controller(authService, sessionService, mock(StaffProfileService.class), permissionService, mock(EmailChangeService.class), authConfiguration);
        Server server = server();
        AuthSessionData session = new AuthSessionData("session-1", "staff@example.com", Instant.now(), Instant.now(), null, null, "digest", AuthMethod.EMAIL_CODE, false);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/panel/auth/verify-email-code");
        request.setRemoteAddr("127.0.0.1");
        request.setAttribute(RequestAttribute.SERVER, server);
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(permissionService.isAuthorizedEmail(server, "staff@example.com")).thenReturn(true);
        when(authService.verifyCode(server, "staff@example.com", "123456")).thenReturn(true);
        when(signInCompletion.complete(eq(server), any(SignInAttempt.class), any(SignInClient.class)))
            .thenReturn(new SignInOutcome.Issued(new IssuedSession(session, "issued-key")));

        PanelAuthResponse body = controller.verifyEmailCode(request, response, PanelVerifyEmailCodeRequest.newBuilder()
            .setEmail("staff@example.com")
            .setCode("123456")
            .build()).getBody();

        assertTrue(body.getSuccess());
        assertEquals("issued-key", body.getSessionKey());
        assertEquals("session-1", response.getCookie("MODL_SESSION").getValue());
    }

    @Test
    void sendingALoginCodeAnswersIdenticallyWhetherOrNotTheAccountExists() {
        PanelAuthController controller = createController(new AuthConfiguration(), mock(SessionService.class));
        Server server = server();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/panel/auth/send-email-code");
        request.setAttribute(RequestAttribute.SERVER, server);

        PanelAuthResponse known = controller.sendEmailCode(request,
            PanelSendEmailCodeRequest.newBuilder().setEmail("staff@example.com").build()).getBody();
        PanelAuthResponse unknown = controller.sendEmailCode(request,
            PanelSendEmailCodeRequest.newBuilder().setEmail("nobody@example.com").build()).getBody();

        assertEquals(known, unknown);
        verify(loginCodeRequests).requestLoginCode(server, "staff@example.com");
        verify(loginCodeRequests).requestLoginCode(server, "nobody@example.com");
    }

    @Test
    void anUnknownAccountRunsTheSameVerificationAndGetsTheSameAnswerAsAWrongCode() {
        AuthService authService = mock(AuthService.class);
        PermissionService permissionService = mock(PermissionService.class);
        PanelAuthController controller = controller(authService, mock(SessionService.class), mock(StaffProfileService.class), permissionService, mock(EmailChangeService.class), new AuthConfiguration());
        Server server = server();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/panel/auth/verify-email-code");
        request.setAttribute(RequestAttribute.SERVER, server);
        when(permissionService.isAuthorizedEmail(server, "staff@example.com")).thenReturn(true);

        ResponseEntity<PanelAuthResponse> wrongCode = controller.verifyEmailCode(request, new MockHttpServletResponse(),
            PanelVerifyEmailCodeRequest.newBuilder().setEmail("staff@example.com").setCode("000000").build());
        ResponseEntity<PanelAuthResponse> unknownAccount = controller.verifyEmailCode(request, new MockHttpServletResponse(),
            PanelVerifyEmailCodeRequest.newBuilder().setEmail("nobody@example.com").setCode("000000").build());

        assertEquals(wrongCode.getStatusCode(), unknownAccount.getStatusCode());
        assertEquals(wrongCode.getBody(), unknownAccount.getBody());
        verify(authService).verifyCode(server, "staff@example.com", "000000");
        verify(authService).verifyCode(server, "nobody@example.com", "000000");
    }

    private PanelAuthController createController(AuthConfiguration authConfiguration, SessionService sessionService) {
        return controller(mock(AuthService.class), sessionService, mock(StaffProfileService.class), mock(PermissionService.class),
            mock(EmailChangeService.class), authConfiguration);
    }

    private PanelAuthController controller(AuthService authService, SessionService sessionService, StaffProfileService staffProfileService,
                                           PermissionService permissionService, EmailChangeService emailChangeService,
                                           AuthConfiguration authConfiguration) {
        PanelSessionIssuer issuer = new PanelSessionIssuer(new CookieUtil(authConfiguration), mock(InstanceService.class),
            mock(ApplicationEventPublisher.class));
        SignInClientResolver clients = new SignInClientResolver(new ClientIpResolver(), NO_PROXY);
        return new PanelAuthController(
            authService,
            sessionService,
            staffProfileService,
            mock(StaffLookupCache.class),
            permissionService,
            mock(RoleAuthorization.class),
            issuer,
            new SignInResponder(signInCompletion, clients, issuer),
            clients,
            signInSettings,
            mock(SecondFactorPolicy.class),
            emailChangeService,
            mock(PanelLoginLinkService.class),
            loginCodeRequests
        );
    }

    private Server server() {
        Server server = TestServers.server("Alpha", "owner@example.com");
        server.setId("server_alpha");
        return server;
    }
}
