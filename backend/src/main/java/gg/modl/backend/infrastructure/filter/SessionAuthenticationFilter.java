package gg.modl.backend.infrastructure.filter;

import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.auth.session.SessionService;
import gg.modl.backend.auth.signin.SecondFactorDecision;
import gg.modl.backend.auth.signin.SecondFactorPolicy;
import gg.modl.backend.infrastructure.exception.FilterErrorResponse;
import gg.modl.backend.infrastructure.rest.RESTSecurityRole;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.infrastructure.rest.RequestHeader;
import gg.modl.backend.infrastructure.rest.RouteGroups;
import gg.modl.backend.infrastructure.util.CookieUtil;
import gg.modl.backend.server.data.Server;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
@Component
@RequiredArgsConstructor
public class SessionAuthenticationFilter extends OncePerRequestFilter {
    private final SessionService sessionService;
    private final CookieUtil cookieUtil;
    private final SecondFactorPolicy secondFactorPolicy;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        return !RouteGroups.classify(request).isPanelOrPublic();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        List<String> sessionTokens = cookieUtil.sessionTokens(request);
        String sessionKey = request.getHeader(RequestHeader.SESSION_KEY);
        Server server = (Server) request.getAttribute(RequestAttribute.SERVER);

        if (server != null && sessionKey != null && !sessionKey.isBlank()) {
            for (String sessionToken : sessionTokens) {
                Optional<AuthSessionData> session = sessionService.authenticateAllowingPendingSecondFactor(server, sessionToken, sessionKey);
                log.debug("SessionAuthenticationFilter: session authentication result={}", session.isPresent() ? "accepted" : "rejected");
                if (session.isEmpty()) {
                    continue;
                }
                SecondFactorDecision decision = secondFactorPolicy.decide(session.get(), RouteGroups.classify(request), request.getMethod());
                String rejectionCode = decision.code();
                String rejectionMessage = decision.message();
                if (rejectionCode != null && rejectionMessage != null) {
                    FilterErrorResponse.writeJson(response, HttpStatus.FORBIDDEN, rejectionMessage, rejectionCode);
                    return;
                }
                if (decision == SecondFactorDecision.ADMIT) {
                    admit(request, response, session.get(), sessionToken);
                }
                break;
            }
        }

        filterChain.doFilter(request, response);
    }

    private void admit(HttpServletRequest request, HttpServletResponse response, AuthSessionData session, String sessionToken) {
        request.setAttribute(RequestAttribute.SESSION, session);

        List<SimpleGrantedAuthority> authorities = List.of(
            new SimpleGrantedAuthority(RESTSecurityRole.USER)
        );

        UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken(
                session.getEmail(),
                null,
                authorities
            );

        authentication.setDetails(session);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        response.addCookie(cookieUtil.createSessionCookie(request, sessionToken));
    }
}
