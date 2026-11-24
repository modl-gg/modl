package gg.modl.backend.infrastructure.util;

import gg.modl.backend.auth.AuthConfiguration;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CookieUtil {
    private static final String SAME_SITE_ATTRIBUTE = "SameSite";
    private static final String SAME_SITE_LAX = "Lax";
    private static final int MAX_SESSION_TOKEN_CANDIDATES = 4;
    private static final String COOKIE_SEPARATOR = ";";
    private static final String NAME_VALUE_SEPARATOR = "=";

    private final AuthConfiguration authConfiguration;

    public Cookie createSessionCookie(HttpServletRequest request, String sessionId) {
        Cookie cookie = baseCookie(request, sessionId);
        cookie.setMaxAge((int) authConfiguration.getSessionDurationSeconds());
        return cookie;
    }

    public Cookie createExpiredSessionCookie(HttpServletRequest request) {
        Cookie cookie = baseCookie(request, "");
        cookie.setMaxAge(0);
        return cookie;
    }

    public List<String> sessionTokens(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        Stream<Cookie> all = cookies == null ? Stream.empty() : Arrays.stream(cookies);
        return candidates(all
            .filter(cookie -> authConfiguration.getSessionCookieName().equals(cookie.getName()))
            .map(Cookie::getValue));
    }

    public List<String> sessionTokens(List<String> cookieHeaders) {
        return candidates(cookieHeaders.stream()
            .flatMap(header -> Arrays.stream(header.split(COOKIE_SEPARATOR)))
            .map(pair -> pair.trim().split(NAME_VALUE_SEPARATOR, 2))
            .filter(pair -> pair.length == 2 && authConfiguration.getSessionCookieName().equals(pair[0].trim()))
            .map(pair -> pair[1].trim()));
    }

    private static List<String> candidates(Stream<String> values) {
        return values
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .limit(MAX_SESSION_TOKEN_CANDIDATES)
            .toList();
    }

    private Cookie baseCookie(HttpServletRequest request, String value) {
        Cookie cookie = new Cookie(authConfiguration.getSessionCookieName(), value);
        cookie.setHttpOnly(true);
        cookie.setSecure(request.isSecure());
        cookie.setPath("/");
        cookie.setAttribute(SAME_SITE_ATTRIBUTE, SAME_SITE_LAX);
        return cookie;
    }
}
