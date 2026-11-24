package gg.modl.backend.settings.controller;

import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.server.data.Server;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class SettingsChangeInvalidation implements HandlerInterceptor {
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final int FIRST_ERROR_STATUS = 400;

    private final SettingsInvalidationPublisher publisher;

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                @Nullable Exception failure) {
        if (failure == null && response.getStatus() < FIRST_ERROR_STATUS && WRITE_METHODS.contains(request.getMethod())
            && !changesNothing(handler) && request.getAttribute(RequestAttribute.SERVER) instanceof Server server) {
            publisher.invalidateSettings(server);
        }
    }

    private static boolean changesNothing(Object handler) {
        return handler instanceof HandlerMethod method && method.hasMethodAnnotation(ChangesNoSettings.class);
    }
}
