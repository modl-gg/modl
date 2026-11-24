package gg.modl.backend.infrastructure.filter;

import gg.modl.backend.infrastructure.exception.FilterErrorResponse;
import gg.modl.backend.infrastructure.proto.ProtobufErrorResponseWriter;
import gg.modl.backend.infrastructure.ratelimit.RateLimitGate;
import gg.modl.backend.infrastructure.rest.RESTSecurityRole;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.infrastructure.rest.RequestHeader;
import gg.modl.backend.infrastructure.rest.RouteGroups;
import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.instance.MinecraftCredential;
import gg.modl.backend.instance.MinecraftCredentialVerifier;
import gg.modl.backend.server.data.Server;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class ApiKeyFilter extends OncePerRequestFilter {
    private static final List<SimpleGrantedAuthority> MINECRAFT_AUTHORITIES =
        List.of(new SimpleGrantedAuthority(RESTSecurityRole.MINECRAFT));
    private static final List<SimpleGrantedAuthority> MEMBER_AUTHORITIES =
        List.of(new SimpleGrantedAuthority(RESTSecurityRole.MINECRAFT_MEMBER));
    static final String UNAUTHENTICATED_MESSAGE = "A valid API key is required";
    private static final String UNAUTHENTICATED_CODE = "UNAUTHENTICATED";

    private final MinecraftCredentialVerifier credentialVerifier;
    private final InstanceContext instanceContext;
    private final ProtobufErrorResponseWriter protobufErrorResponseWriter;
    private final RateLimitGate rateLimitGate;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !RouteGroups.classify(request).isMinecraftApi();
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain chain
    ) throws ServletException, IOException {
        if (!rateLimitGate.admitCredentialAttempt(request, response)) {
            return;
        }
        Optional<MinecraftCredential> credential = credentialVerifier.verify(request.getHeader(RequestHeader.API_KEY));
        if (credential.isEmpty()) {
            writeUnauthorized(request, response);
            return;
        }
        rateLimitGate.refundCredentialAttempt(request);

        Server server = instanceContext.instance();
        request.setAttribute(RequestAttribute.SERVER, server);
        request.setAttribute(RequestAttribute.MINECRAFT_CREDENTIAL, credential.get());

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(server.getId(), null,
            credential.get().isMemberOnly() ? MEMBER_AUTHORITIES : MINECRAFT_AUTHORITIES);
        authentication.setDetails(server);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        chain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (protobufErrorResponseWriter.shouldWriteProtobuf(request)) {
            protobufErrorResponseWriter.write(response, HttpServletResponse.SC_UNAUTHORIZED, UNAUTHENTICATED_CODE,
                UNAUTHENTICATED_MESSAGE);
            return;
        }
        FilterErrorResponse.writeJson(response, HttpStatus.UNAUTHORIZED, UNAUTHENTICATED_MESSAGE, UNAUTHENTICATED_CODE);
    }
}
