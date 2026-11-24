package gg.modl.backend.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class ClientIpResolver {

    public String resolve(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    public String resolveNetwork(HttpServletRequest request) {
        return ClientNetworks.keyOf(resolve(request));
    }
}
