package gg.modl.backend.realtime.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import gg.modl.backend.infrastructure.rest.RequestHeader;
import gg.modl.backend.infrastructure.util.CookieUtil;
import gg.modl.backend.infrastructure.web.HttpOrigin;
import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.instance.MinecraftCredential;
import gg.modl.backend.instance.MinecraftCredentialVerifier;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.serverkey.ServerKeyScope;
import gg.modl.backend.support.TestServers;
import gg.modl.proto.modl.v1.ClientHello;
import gg.modl.proto.modl.v1.ClientKind;
import gg.modl.proto.modl.v1.ErrorCode;
import gg.modl.proto.modl.v1.ServerRole;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import gg.modl.backend.update.ReleaseBuild;
import gg.modl.backend.update.compat.PluginCompatibilityPolicy;
import gg.modl.backend.update.compat.RefusedServers;
import gg.modl.update.ReleaseVersion;
import gg.modl.backend.infrastructure.logging.OperatorNotice;

class RealtimeAuthenticatorTest {
    private static final HttpOrigin REQUEST_ORIGIN = new HttpOrigin("https", "panel.example.com", -1);
    private static final String PANEL_ORIGIN = "https://panel.example.com";

    private final MinecraftCredentialVerifier apiKeyVerifier = mock(MinecraftCredentialVerifier.class);
    private final InstanceContext instanceContext = instanceContext();
    private final Server server = instanceContext.instance();
    private final SessionService sessionService = mock(SessionService.class);
    private final AuthConfiguration authConfiguration = new AuthConfiguration();
    private final RealtimeOriginValidator originValidator = mock(RealtimeOriginValidator.class);
    private final PluginCompatibilityPolicy compatibility = new PluginCompatibilityPolicy(
        ReleaseBuild.of(ReleaseVersion.parse("3.0.2"), ReleaseVersion.parse("3.0.0")), mock(RefusedServers.class),
        mock(OperatorNotice.class));
    private final RealtimeAuthenticator authenticator = new RealtimeAuthenticator(
        apiKeyVerifier, instanceContext, sessionService, new CookieUtil(authConfiguration), originValidator, compatibility);

    @Test
    void minecraftAuthenticationTreatsServerNameAsAdvisoryMetadata() {
        when(apiKeyVerifier.verify("api-key")).thenReturn(Optional.of(MinecraftCredential.instanceKey()));
        HttpHeaders headers = new HttpHeaders();
        headers.set(RequestHeader.API_KEY, "api-key");
        ClientHello hello = ClientHello.newBuilder()
            .setClientKind(ClientKind.CLIENT_KIND_MINECRAFT_PLUGIN)
            .setServerName("Server 1")
            .build();

        RealtimePrincipal principal = assertDoesNotThrow(() -> authenticator.authenticate(headers, null, hello));

        assertEquals(RealtimeClientKind.MINECRAFT, principal.clientKind());
        assertEquals(server, principal.server());
    }

    @Test
    void aBehindProxyKeyMustSayMember() {
        when(apiKeyVerifier.verify("member-key")).thenReturn(Optional.of(memberCredential()));

        RealtimeAuthenticationException refused = assertThrows(RealtimeAuthenticationException.class,
            () -> authenticator.authenticate(keyed("member-key"), null, minecraftHello(ServerRole.SERVER_ROLE_CORE, "spoofed")));
        assertEquals(ErrorCode.ERROR_CODE_FORBIDDEN, refused.getErrorCode());
        assertThrows(RealtimeAuthenticationException.class,
            () -> authenticator.authenticate(keyed("member-key"), null, minecraftHello(ServerRole.SERVER_ROLE_UNSPECIFIED, "spoofed")));
    }

    @Test
    void aBehindProxyKeyIsNamedByTheKeyNotTheHello() {
        when(apiKeyVerifier.verify("member-key")).thenReturn(Optional.of(memberCredential()));

        RealtimePrincipal principal = assertDoesNotThrow(
            () -> authenticator.authenticate(keyed("member-key"), null, minecraftHello(ServerRole.SERVER_ROLE_MEMBER, "spoofed")));

        assertEquals(ServerRole.SERVER_ROLE_MEMBER, principal.role());
        assertEquals("lobby", principal.serverName());
        assertEquals(false, principal.holdsFullCredential());
    }

    @Test
    void aFullKeyMayBeACoreOrAMemberAndDefaultsToCore() {
        when(apiKeyVerifier.verify("api-key")).thenReturn(Optional.of(MinecraftCredential.instanceKey()));

        RealtimePrincipal unspecified = assertDoesNotThrow(
            () -> authenticator.authenticate(keyed("api-key"), null, minecraftHello(ServerRole.SERVER_ROLE_UNSPECIFIED, "Proxy")));
        RealtimePrincipal member = assertDoesNotThrow(
            () -> authenticator.authenticate(keyed("api-key"), null, minecraftHello(ServerRole.SERVER_ROLE_MEMBER, "survival")));

        assertEquals(ServerRole.SERVER_ROLE_CORE, unspecified.role());
        assertEquals(ServerRole.SERVER_ROLE_MEMBER, member.role());
        assertEquals("survival", member.serverName());
    }

    @Test
    void anOverLongHelloNameIsCut() {
        when(apiKeyVerifier.verify("api-key")).thenReturn(Optional.of(MinecraftCredential.instanceKey()));

        RealtimePrincipal principal = assertDoesNotThrow(
            () -> authenticator.authenticate(keyed("api-key"), null, minecraftHello(ServerRole.SERVER_ROLE_CORE, "x".repeat(300))));

        assertEquals(RealtimeAuthenticator.MAXIMUM_SERVER_NAME_LENGTH, principal.serverName().length());
    }

    @Test
    void anIncompatiblePluginIsRefusedWithAVersionMismatch() {
        when(apiKeyVerifier.verify("api-key")).thenReturn(Optional.of(MinecraftCredential.instanceKey()));
        ClientHello older = minecraftHello(ServerRole.SERVER_ROLE_CORE, "Proxy").toBuilder().setPluginVersion("2.9.0").build();
        ClientHello sameLine = minecraftHello(ServerRole.SERVER_ROLE_CORE, "Proxy").toBuilder().setPluginVersion("3.0.7").build();

        RealtimeAuthenticationException refused = assertThrows(RealtimeAuthenticationException.class,
            () -> authenticator.authenticate(keyed("api-key"), null, older));
        assertEquals(ErrorCode.ERROR_CODE_VERSION_MISMATCH, refused.getErrorCode());
        assertEquals(Optional.of("3.0.2"), refused.serverVersion());
        assertDoesNotThrow(() -> authenticator.authenticate(keyed("api-key"), null, sameLine));
    }

    private static MinecraftCredential memberCredential() {
        return new MinecraftCredential(ServerKeyScope.BEHIND_PROXY, "key-1", "lobby");
    }

    private static HttpHeaders keyed(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(RequestHeader.API_KEY, key);
        return headers;
    }

    private static ClientHello minecraftHello(ServerRole role, String serverName) {
        return ClientHello.newBuilder()
            .setClientKind(ClientKind.CLIENT_KIND_MINECRAFT_PLUGIN)
            .setServerRole(role)
            .setServerName(serverName)
            .build();
    }

    @Test
    void minecraftAuthenticationRejectsUnknownApiKey() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(RequestHeader.API_KEY, "wrong-key");
        ClientHello hello = ClientHello.newBuilder()
            .setClientKind(ClientKind.CLIENT_KIND_MINECRAFT_PLUGIN)
            .build();

        assertThrows(RealtimeAuthenticationException.class, () -> authenticator.authenticate(headers, null, hello));
    }

    @Test
    void panelAuthenticationResolvesTheInstanceAndSessionForSameOriginSessions() {
        when(originValidator.isAllowedPanelOrigin(PANEL_ORIGIN, REQUEST_ORIGIN)).thenReturn(true);
        when(sessionService.authenticate(server, "session-token", "session-key")).thenReturn(Optional.of(session("session-token")));

        RealtimePrincipal principal = assertDoesNotThrow(() ->
            authenticator.authenticate(panelHeaders("session-token"), REQUEST_ORIGIN, panelHello("session-key")));

        assertEquals(RealtimeClientKind.PANEL, principal.clientKind());
        assertEquals(server, principal.server());
        assertEquals("staff@example.com", principal.email());
        assertEquals("session-token", principal.sessionId());
    }

    @Test
    void panelAuthenticationTriesEveryCandidateSessionCookie() {
        when(originValidator.isAllowedPanelOrigin(PANEL_ORIGIN, REQUEST_ORIGIN)).thenReturn(true);
        when(sessionService.authenticate(server, "stale-token", "session-key")).thenReturn(Optional.empty());
        when(sessionService.authenticate(server, "current-token", "session-key")).thenReturn(Optional.of(session("current-token")));

        RealtimePrincipal principal = assertDoesNotThrow(() ->
            authenticator.authenticate(panelHeaders("stale-token", "current-token"), REQUEST_ORIGIN, panelHello("session-key")));

        assertEquals("current-token", principal.sessionId());
    }

    @Test
    void panelAuthenticationRequiresTheSessionKeyInTheHello() {
        when(originValidator.isAllowedPanelOrigin(PANEL_ORIGIN, REQUEST_ORIGIN)).thenReturn(true);
        when(sessionService.authenticate(server, "session-token", null)).thenReturn(Optional.empty());
        ClientHello hello = ClientHello.newBuilder()
            .setClientKind(ClientKind.CLIENT_KIND_PANEL)
            .build();

        RealtimeAuthenticationException failure = assertThrows(RealtimeAuthenticationException.class,
            () -> authenticator.authenticate(panelHeaders("session-token"), REQUEST_ORIGIN, hello));
        assertEquals(ErrorCode.ERROR_CODE_UNAUTHORIZED, failure.getErrorCode());
    }

    @Test
    void panelAuthenticationWithoutSessionCookieFailsBeforeAnyLookup() {
        when(originValidator.isAllowedPanelOrigin(PANEL_ORIGIN, REQUEST_ORIGIN)).thenReturn(true);
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin(PANEL_ORIGIN);

        assertThrows(RealtimeAuthenticationException.class,
            () -> authenticator.authenticate(headers, REQUEST_ORIGIN, panelHello("session-key")));
        verify(sessionService, never()).authenticate(any(), anyString(), anyString());
    }

    @Test
    void panelAuthenticationRejectsCrossOriginHandshakes() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("https://evil.example.com");

        assertThrows(RealtimeAuthenticationException.class,
            () -> authenticator.authenticate(headers, REQUEST_ORIGIN, panelHello("session-key")));
    }

    private HttpHeaders panelHeaders(String... sessionTokens) {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin(PANEL_ORIGIN);
        StringBuilder cookie = new StringBuilder("theme=dark");
        for (String token : sessionTokens) {
            cookie.append("; ").append(authConfiguration.getSessionCookieName()).append('=').append(token);
        }
        headers.add(HttpHeaders.COOKIE, cookie.toString());
        return headers;
    }

    private static ClientHello panelHello(String sessionKey) {
        return ClientHello.newBuilder()
            .setClientKind(ClientKind.CLIENT_KIND_PANEL)
            .setSessionKey(sessionKey)
            .build();
    }

    private static InstanceContext instanceContext() {
        Server server = TestServers.server("Instance Display Name", "admin@example.com");
        server.setId("server-id");
        InstanceContext instanceContext = mock(InstanceContext.class);
        when(instanceContext.instance()).thenReturn(server);
        return instanceContext;
    }

    private static AuthSessionData session(String id) {
        return new AuthSessionData(id, "staff@example.com", Instant.now(), Instant.ofEpochMilli(System.currentTimeMillis() + 1000), null, null, "digest",
            AuthMethod.EMAIL_CODE, true);
    }
}
