package gg.modl.backend.update.compat;

import gg.modl.backend.infrastructure.rest.ClassifiedRoute;
import gg.modl.backend.infrastructure.rest.RESTMappingV2;
import gg.modl.backend.infrastructure.rest.RouteGroups;
import gg.modl.update.CompatibilityHeaders;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class PluginCompatibilityInterceptor implements HandlerInterceptor {
    private final PluginCompatibilityPolicy policy;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        ClassifiedRoute route = RouteGroups.classify(request);
        if (!route.isMinecraftApi() || route.path().equals(RESTMappingV2.MINECRAFT_COMPATIBILITY)) {
            return true;
        }
        PluginIdentity identity = PluginIdentity.fromHeaders(request);
        if (identity != null) {
            policy.requireCompatible(identity);
            response.setHeader(CompatibilityHeaders.SERVER_VERSION, policy.serverVersion());
        }
        return true;
    }
}
