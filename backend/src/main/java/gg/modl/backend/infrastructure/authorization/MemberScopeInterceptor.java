package gg.modl.backend.infrastructure.authorization;

import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.instance.MinecraftCredential;
import gg.modl.backend.serverkey.ServerKeyErrors;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class MemberScopeInterceptor implements HandlerInterceptor {
    static final String REFUSED_MESSAGE = "This server key is for a server behind a proxy and cannot use this endpoint";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(request.getAttribute(RequestAttribute.MINECRAFT_CREDENTIAL) instanceof MinecraftCredential credential)
            || !credential.isMemberOnly()) {
            return true;
        }
        if (handler instanceof HandlerMethod method && method.hasMethodAnnotation(MemberAccessible.class)) {
            return true;
        }
        throw new ForbiddenException(REFUSED_MESSAGE, ServerKeyErrors.SCOPE);
    }
}
