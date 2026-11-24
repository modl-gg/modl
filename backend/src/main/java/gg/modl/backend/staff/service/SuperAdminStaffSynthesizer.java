package gg.modl.backend.staff.service;

import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Staff;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.Objects;

public final class SuperAdminStaffSynthesizer {
    public static final String SUPER_ADMIN_USERNAME = "Admin";

    private SuperAdminStaffSynthesizer() {
    }

    public static Staff synthesizeFor(Server server, String adminEmail, Instant now) {
        return Staff.builder()
            .email(EmailAddressUtil.requireNormalized(adminEmail))
            .username(SUPER_ADMIN_USERNAME)
            .roleId(RoleAuthorization.SUPER_ADMIN_ROLE_ID)
            .createdAt(Objects.requireNonNullElse(server.getCreatedAt(), now))
            .updatedAt(now)
            .build();
    }

    @Nullable
    public static Staff synthesizeIfAdminEmail(Server server, String email, Instant now) {
        String adminEmail = server.getAdminEmail();
        if (adminEmail == null || !RoleAuthorization.isSuperAdminEmail(server, email)) {
            return null;
        }
        return synthesizeFor(server, adminEmail, now);
    }
}
