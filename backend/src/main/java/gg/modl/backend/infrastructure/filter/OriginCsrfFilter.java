package gg.modl.backend.infrastructure.filter;

import gg.modl.backend.auth.AuthConfiguration;
import gg.modl.backend.infrastructure.exception.FilterErrorResponse;
import gg.modl.backend.infrastructure.web.HttpOrigin;
import gg.modl.backend.infrastructure.web.SameOriginPolicy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class OriginCsrfFilter extends OncePerRequestFilter {
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final String FETCH_SITE_HEADER = "Sec-Fetch-Site";
    private static final String SAME_ORIGIN = "same-origin";
    private static final String REJECTION_MESSAGE = "Cross-site request blocked";

    private final AuthConfiguration authConfiguration;
    private final SameOriginPolicy sameOriginPolicy;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return SAFE_METHODS.contains(request.getMethod()) || !hasSessionCookie(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        if (isSameOrigin(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        FilterErrorResponse.writeJson(response, HttpStatus.FORBIDDEN, REJECTION_MESSAGE);
    }

    private boolean isSameOrigin(HttpServletRequest request) {
        if (SAME_ORIGIN.equalsIgnoreCase(request.getHeader(FETCH_SITE_HEADER))) {
            return true;
        }
        HttpOrigin requestOrigin = HttpOrigin.of(request);
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null) {
            return sameOriginPolicy.isTrustedOrigin(origin, requestOrigin);
        }
        return sameOriginPolicy.isTrustedOrigin(request.getHeader(HttpHeaders.REFERER), requestOrigin);
    }

    private boolean hasSessionCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        String sessionCookieName = authConfiguration.getSessionCookieName();
        return Arrays.stream(cookies).map(Cookie::getName).anyMatch(sessionCookieName::equals);
    }
}
