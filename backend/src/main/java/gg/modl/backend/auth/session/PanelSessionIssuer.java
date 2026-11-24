package gg.modl.backend.auth.session;

import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.infrastructure.util.CookieUtil;
import gg.modl.backend.infrastructure.web.HttpOrigin;
import gg.modl.backend.instance.InstanceService;
import gg.modl.backend.server.data.Server;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PanelSessionIssuer {
    private final CookieUtil cookieUtil;
    private final InstanceService instanceService;
    private final ApplicationEventPublisher eventPublisher;

    public void publish(Server server, IssuedSession issued, HttpServletRequest request, HttpServletResponse response) {
        attach(issued, request, response);
        if (isOwner(server, issued.session().getEmail())) {
            recordPanelOrigin(request);
            eventPublisher.publishEvent(new OwnerSessionIssuedEvent());
        }
    }

    public void attach(IssuedSession issued, HttpServletRequest request, HttpServletResponse response) {
        response.addCookie(cookieUtil.createSessionCookie(request, issued.session().getId()));
    }

    public void expire(HttpServletRequest request, HttpServletResponse response) {
        response.addCookie(cookieUtil.createExpiredSessionCookie(request));
    }

    private void recordPanelOrigin(HttpServletRequest request) {
        try {
            observedPanelOrigin(request).ifPresent(instanceService::capturePublicUrlIfUnknown);
        } catch (RuntimeException e) {
            log.warn("Could not record the panel URL from the owner's login", e);
        }
    }

    private static Optional<HttpOrigin> observedPanelOrigin(HttpServletRequest request) {
        HttpOrigin requestOrigin = HttpOrigin.of(request);
        HttpOrigin browserOrigin = HttpOrigin.parseExact(request.getHeader(HttpHeaders.ORIGIN))
            .filter(origin -> origin.sameHost(requestOrigin))
            .orElse(requestOrigin);
        return browserOrigin.isLoopback() ? Optional.empty() : Optional.of(browserOrigin);
    }

    private static boolean isOwner(Server server, String sessionEmail) {
        String ownerEmail = EmailAddressUtil.normalize(server.getAdminEmail());
        return ownerEmail != null && ownerEmail.equals(sessionEmail);
    }
}
