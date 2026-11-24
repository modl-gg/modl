package gg.modl.backend.infrastructure.filter;

import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.infrastructure.rest.RouteGroups;
import gg.modl.backend.instance.InstanceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.filter.OncePerRequestFilter;

@RequiredArgsConstructor
public class InstanceRequestFilter extends OncePerRequestFilter {
    private final InstanceContext instanceContext;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !RouteGroups.classify(request).isPanelOrPublic();
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain chain
    ) throws ServletException, IOException {
        request.setAttribute(RequestAttribute.SERVER, instanceContext.instance());
        chain.doFilter(request, response);
    }
}
