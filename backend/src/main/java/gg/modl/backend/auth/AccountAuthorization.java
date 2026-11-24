package gg.modl.backend.auth;

import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.infrastructure.exception.UnauthorizedException;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.repository.StaffRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AccountAuthorization {
    private static final String NO_ACCESS = "This account no longer has access to the panel";

    private final StaffRepository staffRepository;

    public void requireAuthorizedForUpdate(Server server, String email) {
        if (RoleAuthorization.isSuperAdminEmail(server, email)) {
            return;
        }
        String identity = EmailAddressUtil.normalize(email);
        boolean member = identity != null && staffRepository.findByEmail(identity)
            .flatMap(found -> staffRepository.findByIdForUpdate(found.requireId()))
            .filter(locked -> identity.equals(locked.getEmail()))
            .isPresent();
        if (!member) {
            throw new UnauthorizedException(NO_ACCESS);
        }
    }
}
