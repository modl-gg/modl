package gg.modl.backend.infrastructure.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.auth.AuthConfiguration;
import gg.modl.backend.infrastructure.proto.ProtobufErrorResponseWriter;
import gg.modl.backend.infrastructure.proto.ProtobufMediaTypes;
import gg.modl.backend.infrastructure.ratelimit.BucketPool;
import gg.modl.backend.infrastructure.ratelimit.RateLimitConfig;
import gg.modl.backend.infrastructure.ratelimit.RateLimitConfig.RateLimitTier;
import gg.modl.backend.infrastructure.ratelimit.RateLimitGate;
import gg.modl.backend.infrastructure.rest.ApiVersion;
import gg.modl.backend.infrastructure.rest.ClassifiedRoute;
import gg.modl.backend.infrastructure.rest.RouteGroup;
import gg.modl.backend.infrastructure.rest.RouteGroups;
import gg.modl.backend.infrastructure.web.ClientIpResolver;
import jakarta.servlet.http.Cookie;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RouteGroupClassificationCharacterizationTest {

    private record Expected(RouteGroup group, ApiVersion version, String normalizedPath) {
    }

    private static final Map<String, Expected> MATRIX = buildMatrix();

    private static final List<String> ENCODED_PANEL_PATHS = List.of(
        "/%761/panel/settings/api-keys/panel/reveal",
        "/v1/%70anel/settings/api-keys/panel/reveal",
        "/%76%31/%70%61%6e%65%6c/settings/api-keys/panel/reveal",
        "/v1;jsessionid=x/panel;a=b/settings/api-keys/panel/reveal",
        "//v1//panel/settings/api-keys/panel/reveal",
        "/v1/panel%2Fsettings/api-keys/panel/reveal"
    );

    private static final List<String> ENCODED_INVITATION_ACCEPT_PATHS = List.of(
        "/v1/public/staff/invitations/accept;x=1",
        "/%761/public/staff/invitations/%61ccept",
        "//v1//public/staff/invitations/accept/",
        "/v1/public%2Fstaff/invitations/accept"
    );

    private static final List<String> ENCODED_MINECRAFT_PATHS = List.of(
        "/%762/minecraft/players/login",
        "/v3/%6dinecraft/players/sync",
        "/v1/minecraft;x=1/players/login"
    );

    private static Map<String, Expected> buildMatrix() {
        Map<String, Expected> matrix = new LinkedHashMap<>();
        put(matrix, "/v1", RouteGroup.OTHER_API, ApiVersion.V1, "/v1");
        put(matrix, "/v1/", RouteGroup.OTHER_API, ApiVersion.V1, "/v1");
        put(matrix, "/v1/panel", RouteGroup.PANEL, ApiVersion.V1, "/v1/panel");
        put(matrix, "/v1/panel/", RouteGroup.PANEL, ApiVersion.V1, "/v1/panel");
        put(matrix, "/v1/panelfoo", RouteGroup.OTHER_API, ApiVersion.V1, "/v1/panelfoo");
        put(matrix, "/v1/panel/auth", RouteGroup.PANEL_AUTH, ApiVersion.V1, "/v1/panel/auth");
        put(matrix, "/v1/panel/auth/send-email-code", RouteGroup.PANEL_AUTH, ApiVersion.V1, "/v1/panel/auth/send-email-code");
        put(matrix, "/v1/panel/auth/email/send-code", RouteGroup.PANEL_AUTH, ApiVersion.V1, "/v1/panel/auth/email/send-code");
        put(matrix, "/v1/panel/authx", RouteGroup.PANEL, ApiVersion.V1, "/v1/panel/authx");
        put(matrix, "/v1/panel/players/uuid-1/punishments", RouteGroup.PANEL, ApiVersion.V1, "/v1/panel/players/uuid-1/punishments");
        put(matrix, "/v1/panel/settings/api-keys/panel/reveal", RouteGroup.PANEL, ApiVersion.V1, "/v1/panel/settings/api-keys/panel/reveal");
        put(matrix, "/v1/public", RouteGroup.PUBLIC, ApiVersion.V1, "/v1/public");
        put(matrix, "/v1/publicfoo", RouteGroup.OTHER_API, ApiVersion.V1, "/v1/publicfoo");
        put(matrix, "/v1/public/tickets", RouteGroup.PUBLIC, ApiVersion.V1, "/v1/public/tickets");
        put(matrix, "/v1/public/setup/claim", RouteGroup.PUBLIC, ApiVersion.V1, "/v1/public/setup/claim");
        put(matrix, "/v1/public/setup/sign-in", RouteGroup.PUBLIC, ApiVersion.V1, "/v1/public/setup/sign-in");
        put(matrix, "/v1/public/setup/probe/nonce", RouteGroup.PUBLIC, ApiVersion.V1, "/v1/public/setup/probe/nonce");
        put(matrix, "/v1/panel/setup", RouteGroup.PANEL, ApiVersion.V1, "/v1/panel/setup");
        put(matrix, "/v1/panel/setup/steps/identity", RouteGroup.PANEL, ApiVersion.V1, "/v1/panel/setup/steps/identity");
        put(matrix, "/v1/panel/servers/connected", RouteGroup.PANEL, ApiVersion.V1, "/v1/panel/servers/connected");
        put(matrix, "/v1/panel/settings/mail/test", RouteGroup.PANEL, ApiVersion.V1, "/v1/panel/settings/mail/test");
        put(matrix, "/v1/panel/account/two-factor/verify", RouteGroup.PANEL, ApiVersion.V1, "/v1/panel/account/two-factor/verify");
        put(matrix, "/v1/minecraft", RouteGroup.MINECRAFT, ApiVersion.V1, "/v1/minecraft");
        put(matrix, "/v1/minecraftfoo", RouteGroup.OTHER_API, ApiVersion.V1, "/v1/minecraftfoo");
        put(matrix, "/v1/minecraft/players/login", RouteGroup.MINECRAFT, ApiVersion.V1, "/v1/minecraft/players/login");
        put(matrix, "/v1/minecraft/panel/login-link", RouteGroup.MINECRAFT, ApiVersion.V1, "/v1/minecraft/panel/login-link");
        put(matrix, "/v2/minecraft/players/login", RouteGroup.MINECRAFT, ApiVersion.V2, "/v2/minecraft/players/login");
        put(matrix, "/v2", RouteGroup.OTHER_API, ApiVersion.V2, "/v2");
        put(matrix, "/v2/other", RouteGroup.OTHER_API, ApiVersion.V2, "/v2/other");
        put(matrix, "/v3/minecraft/players/sync", RouteGroup.MINECRAFT, ApiVersion.V3, "/v3/minecraft/players/sync");
        put(matrix, "/v3/other", RouteGroup.OTHER_API, ApiVersion.V3, "/v3/other");
        put(matrix, "/v3foo", RouteGroup.STATIC_CONTENT, ApiVersion.NONE, "/v3foo");
        put(matrix, "/v1/realtime/ws", RouteGroup.REALTIME, ApiVersion.V1, "/v1/realtime/ws");
        put(matrix, "/v1/realtime/ws/extra", RouteGroup.OTHER_API, ApiVersion.V1, "/v1/realtime/ws/extra");
        put(matrix, "/v1/health", RouteGroup.HEALTH, ApiVersion.V1, "/v1/health");
        put(matrix, "/actuator", RouteGroup.ACTUATOR, ApiVersion.NONE, "/actuator");
        put(matrix, "/actuator/health", RouteGroup.ACTUATOR, ApiVersion.NONE, "/actuator/health");
        put(matrix, "/actuatorx", RouteGroup.STATIC_CONTENT, ApiVersion.NONE, "/actuatorx");
        put(matrix, "/v1/storage/objects", RouteGroup.OTHER_API, ApiVersion.V1, "/v1/storage/objects");
        put(matrix, "/v1/storage/objects/evidence/file.png", RouteGroup.STORAGE_OBJECT, ApiVersion.V1, "/v1/storage/objects/evidence/file.png");
        put(matrix, "/v1/storage/objectsx/a", RouteGroup.OTHER_API, ApiVersion.V1, "/v1/storage/objectsx/a");
        put(matrix, "/v10/panel", RouteGroup.STATIC_CONTENT, ApiVersion.NONE, "/v10/panel");
        put(matrix, "/V1/panel", RouteGroup.STATIC_CONTENT, ApiVersion.NONE, "/V1/panel");
        put(matrix, "/v1/PANEL", RouteGroup.OTHER_API, ApiVersion.V1, "/v1/PANEL");
        put(matrix, "/error", RouteGroup.STATIC_CONTENT, ApiVersion.NONE, "/error");
        put(matrix, "/", RouteGroup.STATIC_CONTENT, ApiVersion.NONE, "/");
        put(matrix, "/panel/settings", RouteGroup.STATIC_CONTENT, ApiVersion.NONE, "/panel/settings");
        put(matrix, "/assets/index-abc123.js", RouteGroup.STATIC_CONTENT, ApiVersion.NONE, "/assets/index-abc123.js");
        put(matrix, "/setup", RouteGroup.STATIC_CONTENT, ApiVersion.NONE, "/setup");
        put(matrix, "/v1/panel/../public/x", RouteGroup.MALFORMED, ApiVersion.NONE, "/v1/panel/../public/x");
        put(matrix, "/assets/./index.js", RouteGroup.MALFORMED, ApiVersion.NONE, "/assets/./index.js");
        return matrix;
    }

    private static void put(Map<String, Expected> matrix, String path, RouteGroup group, ApiVersion version, String normalized) {
        matrix.put(path, new Expected(group, version, normalized));
    }

    @Test
    void classificationMatchesGoldenMatrix() {
        MATRIX.forEach((path, expected) -> {
            ClassifiedRoute route = RouteGroups.classify(request("GET", path));
            assertEquals(expected.group(), route.group(), () -> "group path=" + path);
            assertEquals(expected.version(), route.apiVersion(), () -> "version path=" + path);
            assertEquals(expected.normalizedPath(), route.path(), () -> "normalized path=" + path);
            assertEquals(route, RouteGroups.classifyPath(path), () -> "string classification path=" + path);
        });
    }

    @Test
    void percentEncodedPathParameterAndDoubleSlashPanelPathsClassifyAsGuardedPanelRoutes() {
        for (String path : ENCODED_PANEL_PATHS) {
            ClassifiedRoute route = RouteGroups.classify(request("GET", path));
            assertEquals(RouteGroup.PANEL, route.group(), () -> "path=" + path);
            assertEquals("/v1/panel/settings/api-keys/panel/reveal", route.path(), () -> "path=" + path);
            assertTrue(route.requiresPanelPermission(), () -> "path=" + path);
            assertTrue(route.isApi(), () -> "path=" + path);
        }
    }

    @Test
    void encodedMinecraftPathsClassifyAsMinecraftApi() {
        for (String path : ENCODED_MINECRAFT_PATHS) {
            assertTrue(RouteGroups.classify(request("POST", path)).isMinecraftApi(), () -> "path=" + path);
        }
    }

    @Test
    void classificationIsMemoizedPerRequestUriAndRecomputedAfterDispatchChangesIt() {
        MockHttpServletRequest request = request("GET", "/%761/panel/tickets");
        ClassifiedRoute first = RouteGroups.classify(request);

        assertTrue(first == RouteGroups.classify(request));

        request.setRequestURI("/error");
        assertEquals(RouteGroup.STATIC_CONTENT, RouteGroups.classify(request).group());
    }

    @Test
    void invalidPercentEncodingIsMalformedRatherThanStaticContent() {
        ClassifiedRoute route = RouteGroups.classify(request("GET", "/%zz1/panel/settings"));

        assertEquals(RouteGroup.MALFORMED, route.group());
        assertTrue(route.isApi());
    }

    @Test
    void staticContentMatcherNeverPermitsApiRoutesHiddenBehindEncoding() {
        StaticContentRequestMatcher matcher = new StaticContentRequestMatcher();
        for (String path : ENCODED_PANEL_PATHS) {
            assertFalse(matcher.matches(request("GET", path)), () -> "path=" + path);
            assertFalse(matcher.matches(request("HEAD", path)), () -> "path=" + path);
        }
        for (String path : ENCODED_MINECRAFT_PATHS) {
            assertFalse(matcher.matches(request("GET", path)), () -> "path=" + path);
        }
        assertFalse(matcher.matches(request("GET", "/%zz1/panel")));
        assertFalse(matcher.matches(request("GET", "/v1/panel/../../index.html")));
        assertTrue(matcher.matches(request("GET", "/panel/settings")));
        assertTrue(matcher.matches(request("HEAD", "/assets/index-abc123.js")));
        assertFalse(matcher.matches(request("POST", "/panel/settings")));
    }

    @Test
    void apiKeyFilterFiltersExactlyTheMinecraftApi() {
        ApiKeyFilter filter = new ApiKeyFilter(null, null, null, null);
        forEveryPath(path -> assertEquals(!RouteGroups.classifyPath(path).isMinecraftApi(),
            filter.shouldNotFilter(request("GET", path)), () -> "path=" + path));
        for (String path : ENCODED_MINECRAFT_PATHS) {
            assertFalse(filter.shouldNotFilter(request("POST", path)), () -> "path=" + path);
        }
    }

    @Test
    void sessionFilterRunsOnPanelAndPublicRoutesIncludingEncodedOnes() {
        SessionAuthenticationFilter filter = new SessionAuthenticationFilter(null, null, null);
        forEveryPath(path -> assertEquals(!RouteGroups.classifyPath(path).isPanelOrPublic(),
            filter.shouldNotFilter(request("POST", path)), () -> "path=" + path));
        for (String path : ENCODED_PANEL_PATHS) {
            assertFalse(filter.shouldNotFilter(request("GET", path)), () -> "path=" + path);
        }
    }

    @Test
    void panelPermissionFilterGuardsEveryPanelRouteOutsideTheAuthArea() {
        PanelPermissionFilter filter = new PanelPermissionFilter(null, null, null);
        forEveryPath(path -> assertEquals(!RouteGroups.classifyPath(path).requiresPanelPermission(),
            filter.shouldNotFilter(request("GET", path)), () -> "path=" + path));
        for (String path : ENCODED_PANEL_PATHS) {
            assertFalse(filter.shouldNotFilter(request("GET", path)), () -> "path=" + path);
        }
        assertTrue(filter.shouldNotFilter(request("POST", "/v1/%70anel/auth/send-email-code")));
    }

    @Test
    void instanceRequestFilterResolvesTheInstanceForPanelAndPublicRoutes() {
        InstanceRequestFilter filter = new InstanceRequestFilter(null);
        forEveryPath(path -> assertEquals(!RouteGroups.classifyPath(path).isPanelOrPublic(),
            filter.shouldNotFilter(request("GET", path)), () -> "path=" + path));
        for (String path : ENCODED_PANEL_PATHS) {
            assertFalse(filter.shouldNotFilter(request("GET", path)), () -> "path=" + path);
        }
    }

    @Test
    void originCsrfFilterChecksEveryStateChangingRequestCarryingASessionCookie() {
        OriginCsrfFilter filter = new OriginCsrfFilter(new AuthConfiguration(), null);
        forEveryPath(path -> {
            MockHttpServletRequest withCookie = request("POST", path);
            withCookie.setCookies(new Cookie("MODL_SESSION", "token"));
            assertFalse(filter.shouldNotFilter(withCookie), () -> "path=" + path);
            assertTrue(filter.shouldNotFilter(request("POST", path)), () -> "path=" + path);
            MockHttpServletRequest safeMethod = request("GET", path);
            safeMethod.setCookies(new Cookie("MODL_SESSION", "token"));
            assertTrue(filter.shouldNotFilter(safeMethod), () -> "path=" + path);
        });
    }

    @Test
    void protobufErrorWriterUsesVersionThreeClassificationWhenNotNegotiated() {
        ProtobufErrorResponseWriter writer = new ProtobufErrorResponseWriter();
        forEveryPath(path -> assertEquals(RouteGroups.classifyPath(path).apiVersion() == ApiVersion.V3,
            writer.shouldWriteProtobuf(request("GET", path)), () -> "path=" + path));
        assertTrue(writer.shouldWriteProtobuf(request("GET", "/%763/minecraft/players/sync")));
    }

    @Test
    void protobufErrorWriterHonorsVersionShortCircuitsAndAcceptNegotiation() {
        ProtobufErrorResponseWriter writer = new ProtobufErrorResponseWriter();

        assertTrue(writer.shouldWriteProtobuf(withAccept("/v3/minecraft/players/sync", "application/json")));
        assertFalse(writer.shouldWriteProtobuf(withAccept("/v1/panel/players", ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE)));
        assertFalse(writer.shouldWriteProtobuf(withAccept("/v2/minecraft/players/sync", ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE)));
        assertTrue(writer.shouldWriteProtobuf(withAccept("/actuator/health", ProtobufMediaTypes.APPLICATION_X_PROTOBUF_VALUE)));
        assertFalse(writer.shouldWriteProtobuf(request("GET", "/actuator/health")));
    }

    @Test
    void rateLimitTierClassificationUnchangedAtGroupBoundaries() {
        RateLimitConfig config = new RateLimitConfig(new BucketPool());

        assertEquals(RateLimitTier.PANEL_AUDIT, tier(config, "/v1/panel/audit/logs", "GET"));
        assertEquals(RateLimitTier.PANEL_AUDIT, tier(config, "/v1/panel/analytics/tickets", "GET"));
        assertEquals(RateLimitTier.PANEL_HEAVY, tier(config, "/v1/panel/staff/invite", "POST"));
        assertEquals(RateLimitTier.PANEL_STANDARD, tier(config, "/v1/panel/players/uuid", "GET"));
        assertEquals(RateLimitTier.PANEL_STANDARD, tier(config, "/v1/panel", "GET"));
        assertEquals(RateLimitTier.PANEL_STANDARD, tier(config, "/v1/panelfoo", "GET"));
        assertEquals(RateLimitTier.PUBLIC_TICKET_CREATE, tier(config, "/v1/public/tickets", "POST"));
        assertEquals(RateLimitTier.PUBLIC_MEDIA_UPLOAD, tier(config, "/v1/public/media/presign", "POST"));
        assertEquals(RateLimitTier.PANEL_STANDARD, tier(config, "/v1/public", "GET"));
        assertEquals(RateLimitTier.PANEL_STANDARD, tier(config, "/v1/publicfoo", "GET"));
        assertEquals(RateLimitTier.MINECRAFT_LOGIN, tier(config, "/v1/minecraft/players/login", "POST"));
        assertEquals(RateLimitTier.MINECRAFT_STANDARD, tier(config, "/v1/minecraft/players/sync", "POST"));
        assertEquals(RateLimitTier.MINECRAFT_PANEL_LINK, tier(config, "/v1/minecraft/panel/login-link", "POST"));
        assertEquals(RateLimitTier.MINECRAFT_PANEL_LINK, tier(config, "/v1/minecraft/panel/link-account", "POST"));
        assertEquals(RateLimitTier.SETUP_CLAIM, tier(config, "/v1/public/setup/claim", "POST"));
        assertEquals(RateLimitTier.SETUP_CLAIM, tier(config, "/v1/public/setup/sign-in", "POST"));
        assertEquals(RateLimitTier.PANEL_HEAVY, tier(config, "/v1/panel/setup/public-url/check", "POST"));
        assertEquals(RateLimitTier.MAIL_TEST, tier(config, "/v1/panel/settings/mail/test", "POST"));
        assertEquals(RateLimitTier.PANEL_STANDARD, tier(config, "/v1/panel/account/two-factor/verify", "POST"));
        assertEquals(RateLimitTier.INVITATION_ACCEPT, tier(config, "/v1/public/staff/invitations/accept", "POST"));
        assertEquals(RateLimitTier.PUBLIC_STANDARD, tier(config, "/v1/public/setup/status", "GET"));
        assertEquals(RateLimitTier.AUTH_LOGIN_LINK, tier(config, "/v1/panel/auth/link", "POST"));
        assertEquals(RateLimitTier.AUTH_SEND_CODE, tier(config, "/v1/panel/auth/send-email-code", "POST"));
        assertEquals(RateLimitTier.AUTH_SEND_CODE, tier(config, "/v1/panel/auth/email/send-code", "POST"));
        assertEquals(RateLimitTier.AUTH, tier(config, "/v1/panel/auth/verify-email-code", "POST"));
        assertEquals(RateLimitTier.AUTH, tier(config, "/v1/panel/auth/webauthn/login/verify", "POST"));
        assertEquals(RateLimitTier.AUTH, tier(config, "/v1/panel/auth/webauthn/login/start", "POST"));
        assertEquals(RateLimitTier.PANEL_STANDARD, tier(config, "/v1/panel/auth/me", "GET"));
        assertEquals(RateLimitTier.PANEL_STANDARD, tier(config, "/v1/panel/auth/permissions", "GET"));
        assertEquals(RateLimitTier.PANEL_STANDARD, tier(config, "/v1/panel/auth/sessions", "GET"));
        assertEquals(RateLimitTier.PANEL_STANDARD, tier(config, "/v1/panel/auth/webauthn/credentials", "GET"));
        assertEquals(RateLimitTier.AUTH_SECOND_FACTOR, tier(config, "/v1/panel/auth/webauthn/register/options", "POST"));
        assertEquals(RateLimitTier.PANEL_STANDARD, tier(config, "/v1/panel/auth/webauthn/register/verify", "POST"));
        assertEquals(RateLimitTier.AUTH_SECOND_FACTOR, tier(config, "/v1/panel/auth/totp", "POST"));
        assertEquals(RateLimitTier.AUTH_SECOND_FACTOR, tier(config, "/v1/panel/auth/totp/disable", "POST"));
        assertEquals(RateLimitTier.AUTH_SECOND_FACTOR, tier(config, "/v1/panel/auth/sign-in/second-factor", "POST"));
        assertEquals(RateLimitTier.AUTH_PASSWORD, tier(config, "/v1/panel/auth/password/login", "POST"));
        assertEquals(RateLimitTier.AUTH_PASSWORD, tier(config, "/v1/panel/auth/password", "PUT"));
        assertEquals(RateLimitTier.AUTH, tier(config, "/v1/panel/auth/password/reset/inspect", "POST"));
        assertEquals(RateLimitTier.PANEL_STANDARD, tier(config, "/v1/panel/auth/security", "GET"));
        assertEquals(RateLimitTier.PANEL_STANDARD, tier(config, "/v1/panel/auth/logout", "POST"));
        assertEquals(RateLimitTier.ACCOUNT_MINECRAFT_LINK, tier(config, "/v1/panel/account/minecraft-link", "POST"));
        assertEquals(RateLimitTier.STORAGE_OBJECT_READ, tier(config, "/v1/storage/objects/evidence/a.png", "GET"));
        assertEquals(RateLimitTier.STORAGE_OBJECT_UPLOAD, tier(config, "/v1/storage/objects/evidence/a.png", "PUT"));
        assertEquals(RateLimitTier.PANEL_STANDARD, tier(config, "/unknown", "GET"));
    }

    @Test
    void rateLimitTiersCannotBeDodgedWithEncodingOrPathParameters() {
        RateLimitConfig config = new RateLimitConfig(new BucketPool());

        assertEquals(RateLimitTier.AUTH_SEND_CODE, tier(config, "/v1/panel/auth/send-email-code;x=1", "POST"));
        assertEquals(RateLimitTier.AUTH_SEND_CODE, tier(config, "/%761/panel/auth/%73end-email-code", "POST"));
        assertEquals(RateLimitTier.SETUP_CLAIM, tier(config, "//v1/public/setup/claim/", "POST"));
        assertEquals(RateLimitTier.MINECRAFT_LOGIN, tier(config, "/%761/minecraft/players/login", "POST"));
        for (String path : ENCODED_INVITATION_ACCEPT_PATHS) {
            assertEquals(RateLimitTier.INVITATION_ACCEPT, tier(config, path, "POST"), () -> "path=" + path);
        }
    }

    @Test
    void invitationAcceptanceIsRateLimitedInEveryEncoding() {
        RateLimitGate gate = new RateLimitGate(new RateLimitConfig(new BucketPool()), new ClientIpResolver(),
            new ProtobufErrorResponseWriter());

        assertFalse(gate.isExempt(request("POST", "/v1/public/staff/invitations/accept")));
        for (String path : ENCODED_INVITATION_ACCEPT_PATHS) {
            assertFalse(gate.isExempt(request("POST", path)), () -> "path=" + path);
        }
    }

    private static RateLimitTier tier(RateLimitConfig config, String path, String method) {
        return config.getTierFor(RouteGroups.classify(request(method, path)), method);
    }

    private static void forEveryPath(Consumer<String> assertion) {
        MATRIX.keySet().forEach(assertion);
    }

    private static MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }

    private static MockHttpServletRequest withAccept(String path, String accept) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.addHeader("Accept", accept);
        return request;
    }
}
