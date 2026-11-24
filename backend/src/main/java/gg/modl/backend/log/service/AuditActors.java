package gg.modl.backend.log.service;

import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.service.StaffLookupCache;
import gg.modl.backend.staff.service.SuperAdminStaffSynthesizer;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuditActors {
    private final StaffLookupCache staffLookupCache;

    public AuditActor account(Server server, @Nullable String email) {
        if (email == null || email.isBlank()) {
            return AuditActor.named(AuditActor.UNKNOWN_STAFF);
        }
        return staffLookupCache.findByEmail(server, email)
            .map(Staff::getUsername)
            .filter(username -> username != null && !username.isBlank())
            .map(AuditActor::named)
            .orElseGet(() -> AuditActor.named(RoleAuthorization.isSuperAdminEmail(server, email)
                ? SuperAdminStaffSynthesizer.SUPER_ADMIN_USERNAME
                : AuditActor.UNKNOWN_STAFF));
    }
}
