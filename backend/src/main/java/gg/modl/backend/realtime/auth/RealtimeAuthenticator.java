package gg.modl.backend.realtime.auth;

import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.auth.session.SessionService;
import gg.modl.backend.infrastructure.rest.RequestHeader;
import gg.modl.backend.infrastructure.util.CookieUtil;
import gg.modl.backend.infrastructure.web.HttpOrigin;
import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.instance.MinecraftCredential;
import gg.modl.backend.instance.MinecraftCredentialVerifier;
import gg.modl.backend.network.NetworkRolePolicy;
import gg.modl.backend.server.data.Server;
import gg.modl.proto.modl.v1.ClientHello;
import gg.modl.proto.modl.v1.ClientKind;
import gg.modl.proto.modl.v1.ErrorCode;
import gg.modl.proto.modl.v1.ServerRole;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import gg.modl.backend.update.compat.PluginCompatibilityPolicy;

@Component
@RequiredArgsConstructor
public class RealtimeAuthenticator {
    public static final String VERSION_MISMATCH_REASON = "Version mismatch: modl server ";
    static final int MAXIMUM_INSTANCE_ID_LENGTH = 64;
    static final int MAXIMUM_SERVER_NAME_LENGTH = 64;
    static final int MAXIMUM_PLUGIN_VERSION_LENGTH = 64;

    private final MinecraftCredentialVerifier credentialVerifier;
    private final InstanceContext instanceContext;
    private final SessionService sessionService;
    private final CookieUtil cookieUtil;
    private final RealtimeOriginValidator originValidator;
    private final PluginCompatibilityPolicy compatibility;

    public RealtimePrincipal authenticate(HttpHeaders headers, @Nullable HttpOrigin requestOrigin, ClientHello hello)
        throws RealtimeAuthenticationException {
        if (hello.getClientKind() == ClientKind.CLIENT_KIND_PANEL) {
            return authenticatePanel(headers, requestOrigin, hello);
        }
        if (hello.getClientKind() == ClientKind.CLIENT_KIND_MINECRAFT_PLUGIN) {
            return authenticateMinecraft(headers, hello);
        }
        throw new RealtimeAuthenticationException(ErrorCode.ERROR_CODE_UNAUTHORIZED, "Unsupported realtime client kind");
    }

    private RealtimePrincipal authenticatePanel(HttpHeaders headers, @Nullable HttpOrigin requestOrigin, ClientHello hello)
        throws RealtimeAuthenticationException {
        if (!originValidator.isAllowedPanelOrigin(headers.getOrigin(), requestOrigin)) {
            throw new RealtimeAuthenticationException(ErrorCode.ERROR_CODE_FORBIDDEN, "Origin is not allowed");
        }

        Server server = instanceContext.instance();
        validateHelloScope(server, hello);

        List<String> sessionTokens = cookieUtil.sessionTokens(headers.getOrEmpty(HttpHeaders.COOKIE));
        if (sessionTokens.isEmpty()) {
            throw new RealtimeAuthenticationException(ErrorCode.ERROR_CODE_UNAUTHORIZED, "Missing panel session");
        }

        String sessionKey = optionalString(hello.hasSessionKey(), hello.getSessionKey());
        for (String sessionToken : sessionTokens) {
            Optional<AuthSessionData> session = sessionService.authenticate(server, sessionToken, sessionKey);
            if (session.isPresent()) {
                return RealtimePrincipal.panel(server, session.get().getEmail(), session.get().getId());
            }
        }
        throw new RealtimeAuthenticationException(ErrorCode.ERROR_CODE_UNAUTHORIZED, "Invalid panel session");
    }

    private RealtimePrincipal authenticateMinecraft(HttpHeaders headers, ClientHello hello) throws RealtimeAuthenticationException {
        MinecraftCredential credential = credentialVerifier.verify(headers.getFirst(RequestHeader.API_KEY))
            .orElseThrow(() -> new RealtimeAuthenticationException(ErrorCode.ERROR_CODE_UNAUTHORIZED, "Invalid API key"));

        requireCompatiblePlugin(hello);
        Server server = instanceContext.instance();
        validateHelloScope(server, hello);
        ServerRole role = roleFor(credential, hello.getServerRole());
        return RealtimePrincipal.minecraft(server,
            bounded(optionalString(hello.hasServerInstanceId(), hello.getServerInstanceId()), MAXIMUM_INSTANCE_ID_LENGTH),
            credential, role, serverNameOf(credential, hello),
            bounded(optionalString(hello.hasPluginVersion(), hello.getPluginVersion()), MAXIMUM_PLUGIN_VERSION_LENGTH));
    }

    private void requireCompatiblePlugin(ClientHello hello) throws RealtimeAuthenticationException {
        if (hello.hasPluginVersion() && !compatibility.compatibilityOf(hello.getPluginVersion()).isCompatible()) {
            throw RealtimeAuthenticationException.versionMismatch(
                VERSION_MISMATCH_REASON + compatibility.serverVersion(), compatibility.serverVersion());
        }
    }

    static ServerRole roleFor(MinecraftCredential credential, ServerRole requested) throws RealtimeAuthenticationException {
        return NetworkRolePolicy.admittedRole(credential, requested)
            .orElseThrow(() -> new RealtimeAuthenticationException(ErrorCode.ERROR_CODE_FORBIDDEN, NetworkRolePolicy.MEMBER_KEY_AS_CORE));
    }

    @Nullable
    private String serverNameOf(MinecraftCredential credential, ClientHello hello) {
        if (credential.keyName() != null) {
            return credential.keyName();
        }
        return bounded(optionalString(hello.hasServerName(), hello.getServerName()), MAXIMUM_SERVER_NAME_LENGTH);
    }

    @Nullable
    private static String bounded(@Nullable String value, int maximumLength) {
        if (value == null) {
            return null;
        }
        return value.length() > maximumLength ? value.substring(0, maximumLength) : value;
    }

    private void validateHelloScope(Server server, ClientHello hello) throws RealtimeAuthenticationException {
        if (hello.hasTenantId() && !hello.getTenantId().isBlank() && !hello.getTenantId().equals(server.getId())) {
            throw new RealtimeAuthenticationException(ErrorCode.ERROR_CODE_FORBIDDEN, "Invalid tenant scope");
        }
        if (hello.hasServerId() && !hello.getServerId().isBlank() && !hello.getServerId().equals(server.getId())) {
            throw new RealtimeAuthenticationException(ErrorCode.ERROR_CODE_FORBIDDEN, "Invalid server scope");
        }
    }

    @Nullable
    private String optionalString(boolean present, String value) {
        if (!present || value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
