package gg.modl.backend.infrastructure.filter;

import static org.junit.jupiter.api.Assertions.assertTrue;
import gg.modl.backend.storage.StorageOrigins;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import gg.modl.backend.auth.AuthConfiguration;
import gg.modl.backend.auth.session.AuthMethod;
import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.auth.session.SessionService;
import gg.modl.backend.auth.signin.SecondFactorDecision;
import gg.modl.backend.auth.signin.SecondFactorPolicy;
import gg.modl.backend.infrastructure.authorization.PanelAccessPolicyResolver;
import gg.modl.backend.infrastructure.proto.ProtobufErrorResponseWriter;
import gg.modl.backend.infrastructure.ratelimit.AccountRateLimitFilter;
import gg.modl.backend.infrastructure.ratelimit.FrozenTimeBuckets;
import gg.modl.backend.infrastructure.ratelimit.RateLimitConfig;
import gg.modl.backend.infrastructure.ratelimit.RateLimitConfig.RateLimitTier;
import gg.modl.backend.infrastructure.ratelimit.RateLimitGate;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.infrastructure.rest.RequestHeader;
import gg.modl.backend.infrastructure.util.CookieUtil;
import gg.modl.backend.infrastructure.web.ClientIpResolver;
import gg.modl.backend.infrastructure.web.SameOriginPolicy;
import gg.modl.backend.instance.MinecraftCredentialVerifier;
import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class V1SecurityChainTest {
    private static final Server SERVER = TestServers.server("Alpha", "owner@example.com");
    private static final String SESSION_ID = "session-id";
    private static final String SESSION_KEY = "session-key";
    private static final String SECOND_OWNER_SESSION_ID = "second-owner-session-id";
    private static final String SECOND_OWNER_SESSION_KEY = "second-owner-session-key";
    private static final String STAFF_SESSION_ID = "staff-session-id";
    private static final String STAFF_SESSION_KEY = "staff-session-key";
    private static final String HEAVY_PANEL_PATH = "/v1/panel/staff/invite";
    private static final String FETCH_SITE_HEADER = "Sec-Fetch-Site";
    private static final String SAME_ORIGIN = "same-origin";
    private static final String MINECRAFT_UNAUTHENTICATED_BODY =
        "{\"success\":false,\"status\":401,\"error\":\"A valid API key is required\",\"message\":\"A valid API key is required\",\"code\":\"UNAUTHENTICATED\"}";
    private static final String UNAUTHENTICATED_BODY =
        "{\"success\":false,\"status\":401,\"error\":\"Authentication required\",\"message\":\"Authentication required\",\"code\":\"UNAUTHENTICATED\"}";

    private static AnnotationConfigWebApplicationContext context;
    private static MockMvc mockMvc;

    @BeforeAll
    static void startSecurityChain() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(ChainConfiguration.class);
        context.refresh();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterAll
    static void stopSecurityChain() {
        context.close();
    }

    @Test
    void authenticatedAuthEndpointsRejectAnonymousRequestsWithTheFrozenBody() throws Exception {
        MvcResult result = perform(get("/v1/panel/auth/me"));

        assertEquals(401, result.getResponse().getStatus());
        assertEquals(UNAUTHENTICATED_BODY, result.getResponse().getContentAsString());
        assertEquals(401, perform(get("/v1/panel/auth/webauthn/credentials")).getResponse().getStatus());
        assertEquals(401, perform(post("/v1/panel/auth/webauthn/register/options")).getResponse().getStatus());
    }

    @Test
    void authenticatedAuthEndpointsNeedTheSessionKeyNotJustTheCookie() throws Exception {
        assertEquals(401, perform(get("/v1/panel/auth/me").cookie(sessionCookie())).getResponse().getStatus());
        assertEquals(200, perform(authenticated(get("/v1/panel/auth/me"))).getResponse().getStatus());
    }

    @Test
    void explicitlyAnonymousAuthEndpointsStayReachable() throws Exception {
        assertEquals(200, perform(post("/v1/panel/auth/send-email-code")).getResponse().getStatus());
        assertEquals(200, perform(post("/v1/panel/auth/verify-email-code")).getResponse().getStatus());
        assertEquals(200, perform(post("/v1/panel/auth/link")).getResponse().getStatus());
        assertEquals(200, perform(post("/v1/panel/auth/logout")).getResponse().getStatus());
        assertEquals(200, perform(post("/v1/panel/auth/webauthn/login/start")).getResponse().getStatus());
        assertEquals(200, perform(get("/v1/public/setup/status")).getResponse().getStatus());
    }

    @Test
    void encodedPanelPrefixesCannotReachPanelHandlersAnonymously() throws Exception {
        for (String path : new String[] {
            "/%761/panel/settings/api-keys/panel/reveal",
            "/v1/%70anel/settings/api-keys/panel/reveal",
            "/%76%31/%70%61%6e%65%6c/settings/api-keys/panel/reveal"
        }) {
            MvcResult anonymous = perform(get(URI.create(path)));
            assertEquals(401, anonymous.getResponse().getStatus(), path);
            assertEquals(UNAUTHENTICATED_BODY, anonymous.getResponse().getContentAsString(), path);
        }
        assertEquals(200, perform(authenticated(get(URI.create("/%761/panel/settings/api-keys/panel/reveal"))))
            .getResponse().getStatus());
    }

    @Test
    void unmatchedApiPathsAreDeniedAndOnlyReadOnlyStaticRequestsPassAnonymously() throws Exception {
        assertEquals(401, perform(get("/v1/unknown")).getResponse().getStatus());
        assertEquals(401, perform(authenticated(get("/v1/unknown"))).getResponse().getStatus());
        assertEquals(401, perform(get("/actuator/env")).getResponse().getStatus());
        assertEquals(404, perform(get("/panel/settings")).getResponse().getStatus());
        assertEquals(401, perform(post("/panel/settings")).getResponse().getStatus());
    }

    @Test
    void minecraftApiStillRequiresTheApiKey() throws Exception {
        assertEquals(401, perform(post("/v1/minecraft/players/login")).getResponse().getStatus());
        assertEquals(401, perform(post(URI.create("/%761/minecraft/players/login"))).getResponse().getStatus());
    }

    @Test
    void minecraftApiRejectionCarriesTheStandardErrorBody() throws Exception {
        MvcResult rejected = perform(post("/v1/minecraft/players/login"));

        assertEquals(401, rejected.getResponse().getStatus());
        assertEquals(MINECRAFT_UNAUTHENTICATED_BODY, rejected.getResponse().getContentAsString());
    }

    @Test
    void accountBudgetsFollowTheAuthenticatedAccountNotTheFirstSessionCookie() throws Exception {
        int capacity = RateLimitTier.PANEL_HEAVY.getCapacity();
        for (int attempt = 0; attempt < capacity; attempt++) {
            MockHttpServletRequestBuilder request = attempt % 2 == 0
                ? behindForgedCookie(attempt, SESSION_ID, SESSION_KEY)
                : behindForgedCookie(attempt, SECOND_OWNER_SESSION_ID, SECOND_OWNER_SESSION_KEY);
            assertEquals(200, perform(request).getResponse().getStatus());
        }

        MvcResult exhausted = perform(behindForgedCookie(capacity, SECOND_OWNER_SESSION_ID, SECOND_OWNER_SESSION_KEY));
        MvcResult otherStaff = perform(behindForgedCookie(capacity, STAFF_SESSION_ID, STAFF_SESSION_KEY));

        assertEquals(429, exhausted.getResponse().getStatus());
        assertNotNull(exhausted.getResponse().getHeader("Retry-After"));
        assertEquals(200, otherStaff.getResponse().getStatus());
    }

    @Test
    void responsesNeverCarryHsts() throws Exception {
        MvcResult result = perform(get("/v1/public/setup/status").secure(true));

        assertNull(result.getResponse().getHeader("Strict-Transport-Security"));
        assertEquals("DENY", result.getResponse().getHeader("X-Frame-Options"));
    }

    @Test
    void everyResponseCarriesTheContentSecurityPolicy() throws Exception {
        for (MvcResult result : new MvcResult[] {perform(get("/v1/public/setup/status")), perform(get("/panel/settings"))}) {
            String policy = result.getResponse().getHeader("Content-Security-Policy");
            assertNotNull(policy);
            assertTrue(policy.contains("script-src 'self'") && policy.contains("frame-ancestors 'none'"), policy);
        }
    }

    private static MvcResult perform(MockHttpServletRequestBuilder builder) throws Exception {
        RequestBuilder request = builder.requestAttr(RequestAttribute.SERVER, SERVER);
        return mockMvc.perform(request).andReturn();
    }

    private static MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder builder) {
        return builder.cookie(sessionCookie()).header(RequestHeader.SESSION_KEY, SESSION_KEY);
    }

    private static Cookie sessionCookie() {
        return sessionCookie(SESSION_ID);
    }

    private static Cookie sessionCookie(String sessionId) {
        return new Cookie(new AuthConfiguration().getSessionCookieName(), sessionId);
    }

    private static MockHttpServletRequestBuilder behindForgedCookie(int attempt, String sessionId, String sessionKey) {
        return post(HEAVY_PANEL_PATH)
            .cookie(sessionCookie("forged-" + attempt), sessionCookie(sessionId))
            .header(RequestHeader.SESSION_KEY, sessionKey)
            .header(FETCH_SITE_HEADER, SAME_ORIGIN);
    }

    @Configuration
    @EnableWebMvc
    @Import(V1SecurityConfig.class)
    static class ChainConfiguration {

        @Bean
        StorageOrigins storageOrigins() {
            return Set::of;
        }

        @Bean
        AuthConfiguration authConfiguration() {
            return new AuthConfiguration();
        }

        @Bean
        SessionAuthenticationFilter sessionAuthenticationFilter(AuthConfiguration authConfiguration) {
            SessionService sessionService = mock(SessionService.class);
            AuthSessionData session = new AuthSessionData(SESSION_ID, "owner@example.com", Instant.now(),
                Instant.ofEpochMilli(System.currentTimeMillis() + 60_000), null, null, "digest", AuthMethod.EMAIL_CODE, false);
            AuthSessionData secondOwnerSession = new AuthSessionData(SECOND_OWNER_SESSION_ID, "owner@example.com", Instant.now(),
                Instant.ofEpochMilli(System.currentTimeMillis() + 60_000), null, null, "second-owner-digest", AuthMethod.EMAIL_CODE, false);
            when(sessionService.authenticateAllowingPendingSecondFactor(SERVER, SECOND_OWNER_SESSION_ID, SECOND_OWNER_SESSION_KEY))
                .thenReturn(Optional.of(secondOwnerSession));
            AuthSessionData staffSession = new AuthSessionData(STAFF_SESSION_ID, "staff@example.com", Instant.now(),
                Instant.ofEpochMilli(System.currentTimeMillis() + 60_000), null, null, "staff-digest", AuthMethod.EMAIL_CODE, false);
            when(sessionService.authenticateAllowingPendingSecondFactor(SERVER, SESSION_ID, SESSION_KEY)).thenReturn(Optional.of(session));
            when(sessionService.authenticateAllowingPendingSecondFactor(SERVER, STAFF_SESSION_ID, STAFF_SESSION_KEY)).thenReturn(Optional.of(staffSession));
            SecondFactorPolicy admitAll = mock(SecondFactorPolicy.class);
            when(admitAll.decide(any(), any(), any())).thenReturn(SecondFactorDecision.ADMIT);
            return new SessionAuthenticationFilter(sessionService, new CookieUtil(authConfiguration), admitAll);
        }

        @Bean
        AccountRateLimitFilter accountRateLimitFilter() {
            return new AccountRateLimitFilter(new RateLimitGate(new RateLimitConfig(FrozenTimeBuckets.pool()), new ClientIpResolver(),
                new ProtobufErrorResponseWriter()));
        }

        @Bean
        ApiKeyFilter apiKeyFilter() {
            return new ApiKeyFilter(mock(MinecraftCredentialVerifier.class), mock(InstanceContext.class), new ProtobufErrorResponseWriter(),
                new RateLimitGate(new RateLimitConfig(FrozenTimeBuckets.pool()), new ClientIpResolver(), new ProtobufErrorResponseWriter()));
        }

        @Bean
        PanelPermissionFilter panelPermissionFilter() {
            PermissionService permissionService = mock(PermissionService.class);
            when(permissionService.isSuperAdmin(any(Server.class), anyString())).thenReturn(true);
            return new PanelPermissionFilter(permissionService, mock(RoleAuthorization.class), mock(PanelAccessPolicyResolver.class));
        }

        @Bean
        OriginCsrfFilter originCsrfFilter(AuthConfiguration authConfiguration) {
            return new OriginCsrfFilter(authConfiguration, new SameOriginPolicy(mock(PublicUrls.class)));
        }

        @Bean
        StubEndpoints stubEndpoints() {
            return new StubEndpoints();
        }
    }

    @RestController
    static class StubEndpoints {

        @GetMapping("/v1/panel/auth/me")
        String me() {
            return "me";
        }

        @GetMapping("/v1/panel/auth/webauthn/credentials")
        String credentials() {
            return "credentials";
        }

        @PostMapping("/v1/panel/auth/webauthn/register/options")
        String registerOptions() {
            return "register";
        }

        @PostMapping({
            "/v1/panel/auth/send-email-code",
            "/v1/panel/auth/verify-email-code",
            "/v1/panel/auth/link",
            "/v1/panel/auth/logout",
            "/v1/panel/auth/webauthn/login/start"
        })
        String anonymousAuth() {
            return "anonymous";
        }

        @PostMapping(HEAVY_PANEL_PATH)
        String invite() {
            return "invited";
        }

        @GetMapping("/v1/panel/settings/api-keys/panel/reveal")
        String reveal() {
            return "secret";
        }

        @GetMapping({"/v1/public/setup/status", "/v1/unknown"})
        String publicEndpoint() {
            return "public";
        }

        @PostMapping("/v1/minecraft/players/login")
        String minecraftLogin() {
            return "login";
        }
    }
}
