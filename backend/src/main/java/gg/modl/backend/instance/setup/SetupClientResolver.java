package gg.modl.backend.instance.setup;

import gg.modl.backend.infrastructure.web.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SetupClientResolver {
    private final ClientIpResolver clientIpResolver;

    public SetupClient resolve(HttpServletRequest request) {
        return SetupClient.identifiedBy(clientIpResolver.resolveNetwork(request));
    }
}
