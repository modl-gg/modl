package gg.modl.backend.infrastructure.filter;

import gg.modl.backend.infrastructure.rest.RouteGroups;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.util.matcher.RequestMatcher;

final class StaticContentRequestMatcher implements RequestMatcher {

    @Override
    public boolean matches(HttpServletRequest request) {
        return isReadOnly(request.getMethod()) && !RouteGroups.classify(request).isApi();
    }

    private static boolean isReadOnly(String method) {
        return HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method);
    }
}
