package gg.modl.backend.auth;

import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class LoginCodeRequests {
    private final PermissionService permissionService;
    private final AuthService authService;

    @Async("emailTaskExecutor")
    public void requestLoginCode(Server server, String email) {
        try {
            if (permissionService.isAuthorizedEmail(server, email)) {
                authService.sendUserLoginCode(server, email);
            }
        } catch (RuntimeException e) {
            log.warn("Could not issue a login code for {}", EmailAddressUtil.mask(email), e);
        }
    }
}
