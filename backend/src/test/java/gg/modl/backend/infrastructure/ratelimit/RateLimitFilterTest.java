package gg.modl.backend.infrastructure.ratelimit;

import gg.modl.backend.infrastructure.web.ClientNetworks;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.infrastructure.proto.ProtobufErrorResponseWriter;
import gg.modl.backend.infrastructure.proto.ProtobufMediaTypes;
import gg.modl.backend.infrastructure.ratelimit.RateLimitConfig.RateLimitTier;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.infrastructure.rest.RouteGroups;
import gg.modl.backend.infrastructure.web.ClientIpResolver;
import gg.modl.backend.instance.MinecraftCredential;
import gg.modl.backend.serverkey.ServerKeyScope;
import gg.modl.proto.modl.v1.ApiError;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTest {
    private static final String SHARED_IP = "203.0.113.7";
    private static final String HEAVY_PANEL_PATH = "/v1/panel/staff/invite";
    private static final String FIRST_STAFF = "first@example.com";
    private static final String SECOND_STAFF = "second@example.com";

    @Test
    void v3RateLimitRejectionReturnsBinaryApiError() throws Exception {
        RateLimitConfig rateLimitConfig = mock(RateLimitConfig.class);
        Bucket bucket = mock(Bucket.class);
        when(rateLimitConfig.getTierFor(RouteGroups.classifyPath("/v3/minecraft/players/sync"), "POST"))
            .thenReturn(RateLimitTier.MINECRAFT_STANDARD);
        when(rateLimitConfig.clientBucket(RateLimitTier.MINECRAFT_STANDARD, "127.0.0.1")).thenReturn(bucket);
        when(bucket.tryConsumeAndReturnRemaining(1))
            .thenReturn(ConsumptionProbe.rejected(0, 2_000_000_000L, 2_000_000_000L));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v3/minecraft/players/sync");
        request.addHeader("Accept", ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new RateLimitFilter(gate(rateLimitConfig)).doFilter(request, response, chain);

        assertEquals(429, response.getStatus());
        assertEquals(ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE, response.getContentType());
        ApiError error = ApiError.parseFrom(response.getContentAsByteArray());
        assertEquals(429, error.getStatusCode());
        assertEquals("RATE_LIMITED", error.getCode());
        assertEquals("Rate limit exceeded", error.getMessage());
        assertNull(chain.getRequest());
    }

    @Test
    void v1RateLimitRejectionPreservesJsonErrorBody() throws Exception {
        RateLimitConfig rateLimitConfig = mock(RateLimitConfig.class);
        Bucket bucket = mock(Bucket.class);
        when(rateLimitConfig.getTierFor(RouteGroups.classifyPath("/v1/minecraft/players/sync"), "POST"))
            .thenReturn(RateLimitTier.MINECRAFT_STANDARD);
        when(rateLimitConfig.clientBucket(RateLimitTier.MINECRAFT_STANDARD, "127.0.0.1")).thenReturn(bucket);
        when(bucket.tryConsumeAndReturnRemaining(1))
            .thenReturn(ConsumptionProbe.rejected(0, 2_000_000_000L, 2_000_000_000L));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/minecraft/players/sync");
        request.addHeader("Accept", ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new RateLimitFilter(gate(rateLimitConfig)).doFilter(request, response, chain);

        assertEquals(429, response.getStatus());
        assertEquals("application/json", response.getContentType());
        assertTrue(response.getContentAsString().contains("\"error\":\"Rate limit exceeded\""));
        assertTrue(response.getContentAsString().contains("\"code\":\"RATE_LIMITED\""));
        assertTrue(response.getContentAsString().contains("\"retryAfterSeconds\":2"));
        assertNull(chain.getRequest());
        assertEquals("2", response.getHeader("Retry-After"));
        assertEquals("2", response.getHeader("X-RateLimit-Retry-After"));
        assertEquals(1, response.getHeaders("X-RateLimit-Retry-After").size());
        assertNull(response.getHeader("X-Rate-Limit-Retry-After-Seconds"));
    }

    @Test
    void acceptedRequestCarriesASingleRemainingHeader() throws Exception {
        RateLimitConfig rateLimitConfig = mock(RateLimitConfig.class);
        Bucket bucket = mock(Bucket.class);
        when(rateLimitConfig.getTierFor(RouteGroups.classifyPath("/v1/public/tickets"), "GET"))
            .thenReturn(RateLimitTier.PUBLIC_STANDARD);
        when(rateLimitConfig.clientBucket(RateLimitTier.PUBLIC_STANDARD, "127.0.0.1")).thenReturn(bucket);
        when(bucket.tryConsumeAndReturnRemaining(1)).thenReturn(ConsumptionProbe.consumed(41, 0));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/public/tickets");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new RateLimitFilter(gate(rateLimitConfig)).doFilter(request, response, chain);

        assertEquals(request, chain.getRequest());
        assertEquals("41", response.getHeader("X-RateLimit-Remaining"));
        assertEquals(1, response.getHeaders("X-RateLimit-Remaining").size());
        assertNull(response.getHeader("X-Rate-Limit-Remaining"));
    }

    @Test
    void encodedApiPathsAreRateLimitedLikeTheirDecodedForm() {
        RateLimitGate gate = gate(mock(RateLimitConfig.class));
        RateLimitFilter filter = new RateLimitFilter(gate);
        AccountRateLimitFilter accountFilter = new AccountRateLimitFilter(gate);

        for (OncePerRequestFilterProbe probe : new OncePerRequestFilterProbe[] {filter::shouldNotFilter, accountFilter::shouldNotFilter}) {
            assertFalse(probe.skips(new MockHttpServletRequest("GET", "/%761/panel/settings/api-keys/panel/reveal")));
            assertTrue(probe.skips(new MockHttpServletRequest("GET", "/%761/health")));
            assertTrue(probe.skips(new MockHttpServletRequest("GET", "/panel/settings")));
            assertFalse(probe.skips(new MockHttpServletRequest("POST", "/panel/settings")));
        }
    }

    @Test
    void staticAssetRequestsBypassRateLimiting() throws Exception {
        RateLimitConfig rateLimitConfig = mock(RateLimitConfig.class);
        RateLimitFilter filter = new RateLimitFilter(gate(rateLimitConfig));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/assets/index-abc123.js");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertEquals(request, chain.getRequest());
        verifyNoInteractions(rateLimitConfig);
    }

    @Test
    void staffBehindOneAddressSpendSeparateAccountBudgets() throws Exception {
        TwoStageLimiter limiter = new TwoStageLimiter();
        limiter.exhaustAccountBudget(FIRST_STAFF);

        assertEquals(429, limiter.perform(authenticated(FIRST_STAFF, "first-session")).getStatus());
        assertEquals(200, limiter.perform(authenticated(SECOND_STAFF, "second-session")).getStatus());
    }

    @Test
    void rotatingAForgedFirstSessionCookieDoesNotGrantAFreshBudget() throws Exception {
        TwoStageLimiter limiter = new TwoStageLimiter();
        for (int attempt = 0; attempt < RateLimitTier.PANEL_HEAVY.getCapacity(); attempt++) {
            assertEquals(200, limiter.perform(authenticated(FIRST_STAFF, "forged-" + attempt)).getStatus());
        }

        MockHttpServletResponse rejected = limiter.perform(authenticated(FIRST_STAFF, "forged-final"));

        assertEquals(429, rejected.getStatus());
        assertTrue(Long.parseLong(rejected.getHeader("Retry-After")) > 0);
    }

    @Test
    void thePerAddressCeilingRejectsBeforeAuthenticationIsConsidered() throws Exception {
        TwoStageLimiter limiter = new TwoStageLimiter();
        assertTrue(limiter.config.resolveBucket(SHARED_IP, RateLimitTier.PANEL_CLIENT_CEILING)
            .tryConsume(RateLimitTier.PANEL_CLIENT_CEILING.getCapacity()));

        MockHttpServletResponse response = limiter.perform(authenticated(SECOND_STAFF, "second-session"));

        assertEquals(429, response.getStatus());
        assertNull(limiter.handler.getRequest());
        assertEquals(RateLimitTier.PANEL_HEAVY.getCapacity(),
            limiter.config.accountBucket(RateLimitTier.PANEL_HEAVY, SECOND_STAFF, SHARED_IP).orElseThrow().getAvailableTokens());
    }

    @Test
    void remainingHeaderReportsTheLowestOfTheCeilingAndAccountBudgets() throws Exception {
        TwoStageLimiter limiter = new TwoStageLimiter();
        int ceilingCapacity = RateLimitTier.PANEL_CLIENT_CEILING.getCapacity();

        MockHttpServletResponse accountLowest = limiter.perform(authenticated(FIRST_STAFF, "first-session"));
        assertTrue(limiter.config.resolveBucket(SHARED_IP, RateLimitTier.PANEL_CLIENT_CEILING).tryConsume(ceilingCapacity - 6));
        MockHttpServletResponse ceilingLowest = limiter.perform(authenticated(SECOND_STAFF, "second-session"));

        assertEquals(String.valueOf(RateLimitTier.PANEL_HEAVY.getCapacity() - 1), accountLowest.getHeader("X-RateLimit-Remaining"));
        assertEquals("4", ceilingLowest.getHeader("X-RateLimit-Remaining"));
        assertEquals(1, ceilingLowest.getHeaders("X-RateLimit-Remaining").size());
    }

    @Test
    void accountStageRejectionCarriesNoStaleRemainingHeader() throws Exception {
        TwoStageLimiter limiter = new TwoStageLimiter();
        limiter.exhaustAccountBudget(FIRST_STAFF);

        MockHttpServletResponse rejected = limiter.perform(authenticated(FIRST_STAFF, "first-session"));

        assertEquals(429, rejected.getStatus());
        assertNull(rejected.getHeader("X-RateLimit-Remaining"));
        assertTrue(Long.parseLong(rejected.getHeader("X-RateLimit-Retry-After")) > 0);
    }

    @Test
    void unauthenticatedPanelRequestsFallBackToTheClientAddress() throws Exception {
        TwoStageLimiter limiter = new TwoStageLimiter();
        for (int attempt = 0; attempt < RateLimitTier.PANEL_HEAVY.getCapacity(); attempt++) {
            assertEquals(200, limiter.perform(anonymous(SHARED_IP)).getStatus());
        }

        assertEquals(429, limiter.perform(anonymous(SHARED_IP)).getStatus());
        assertEquals(200, limiter.perform(anonymous("198.51.100.4")).getStatus());
        assertEquals(200, limiter.perform(authenticated(FIRST_STAFF, "first-session")).getStatus());
    }

    @Test
    void anIpv6Slash64SpendsOneBudgetWhateverAddressItRotatesTo() throws Exception {
        TwoStageLimiter limiter = new TwoStageLimiter();
        String network = ClientNetworks.keyOf("2001:db8:1:2::1");
        assertTrue(limiter.config.accountBucket(RateLimitTier.PANEL_HEAVY, null, network).orElseThrow()
            .tryConsume(RateLimitTier.PANEL_HEAVY.getCapacity()));

        MockHttpServletResponse response = limiter.perform(anonymous("2001:db8:1:2:dead:beef:1:2"));

        assertEquals(429, response.getStatus());
        assertEquals(200, limiter.perform(anonymous("2001:db8:1:3::1")).getStatus());
    }

    @Test
    void aServerKeyOverItsBudgetIsRefusedWhileAnotherKeyOnTheSameAddressContinues() throws Exception {
        TwoStageLimiter limiter = new TwoStageLimiter();
        MinecraftCredential lobby = new MinecraftCredential(ServerKeyScope.BEHIND_PROXY, "lobby-key", "lobby");
        MinecraftCredential survival = new MinecraftCredential(ServerKeyScope.BEHIND_PROXY, "survival-key", "survival");
        assertTrue(limiter.config.credentialBucket(RateLimitTier.MINECRAFT_STANDARD, lobby).orElseThrow()
            .tryConsume(RateLimitTier.MINECRAFT_STANDARD.getCapacity()));

        assertEquals(429, limiter.perform(plugin(lobby)).getStatus());
        assertEquals(200, limiter.perform(plugin(survival)).getStatus());
        assertEquals(200, limiter.perform(plugin(MinecraftCredential.internalKey())).getStatus());
    }

    private static MockHttpServletRequest plugin(MinecraftCredential credential) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v3/minecraft/players/sync");
        request.setRemoteAddr("127.0.0.1");
        request.setAttribute(RequestAttribute.MINECRAFT_CREDENTIAL, credential);
        return request;
    }

    private static RateLimitGate gate(RateLimitConfig rateLimitConfig) {
        return new RateLimitGate(rateLimitConfig, new ClientIpResolver(), new ProtobufErrorResponseWriter());
    }

    private static MockHttpServletRequest authenticated(String accountEmail, String firstSessionCookie) {
        MockHttpServletRequest request = anonymous(SHARED_IP);
        request.setCookies(new Cookie("MODL_SESSION", firstSessionCookie), new Cookie("MODL_SESSION", "authenticated-session"));
        AuthSessionData session = new AuthSessionData();
        session.setEmail(accountEmail);
        request.setAttribute(RequestAttribute.SESSION, session);
        return request;
    }

    private static MockHttpServletRequest anonymous(String clientIp) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", HEAVY_PANEL_PATH);
        request.setRemoteAddr(clientIp);
        return request;
    }

    @FunctionalInterface
    private interface OncePerRequestFilterProbe {
        boolean skips(MockHttpServletRequest request);
    }

    private static final class TwoStageLimiter {
        private final RateLimitConfig config = new RateLimitConfig(FrozenTimeBuckets.pool());
        private final RateLimitGate gate = gate(config);
        private final RateLimitFilter clientStage = new RateLimitFilter(gate);
        private final AccountRateLimitFilter accountStage = new AccountRateLimitFilter(gate);
        private MockFilterChain handler = new MockFilterChain();

        MockHttpServletResponse perform(MockHttpServletRequest request) throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();
            handler = new MockFilterChain();
            clientStage.doFilter(request, response, (stagedRequest, stagedResponse) ->
                accountStage.doFilter(stagedRequest, stagedResponse, handler));
            return response;
        }

        void exhaustAccountBudget(String accountEmail) {
            assertTrue(config.accountBucket(RateLimitTier.PANEL_HEAVY, accountEmail, SHARED_IP).orElseThrow()
                .tryConsume(RateLimitTier.PANEL_HEAVY.getCapacity()));
        }
    }
}
