package gg.modl.backend.auth.signin;

import gg.modl.backend.infrastructure.rest.RequestHeader;
import gg.modl.backend.infrastructure.web.ClientIpResolver;
import gg.modl.backend.infrastructure.web.ForwardedHeaders;
import gg.modl.backend.instance.InstanceProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SignInClientResolver {
    private final ClientIpResolver clientIpResolver;
    private final InstanceProperties instanceProperties;

    public SignInClient resolve(HttpServletRequest request) {
        return new SignInClient(clientIpResolver.resolve(request), request.getHeader(RequestHeader.USER_AGENT), identifiedNetwork(request));
    }

    private Optional<String> identifiedNetwork(HttpServletRequest request) {
        boolean forwardedWithoutTrust = ForwardedHeaders.present(request) && !instanceProperties.trustsProxyHeaders();
        return forwardedWithoutTrust ? Optional.empty() : Optional.of(clientIpResolver.resolveNetwork(request));
    }
}
