package gg.modl.backend.staff.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.AccountOffboarding;
import gg.modl.backend.auth.ImmediateUnitOfWork;
import gg.modl.backend.role.data.StaffRole;
import gg.modl.backend.role.repository.StaffRoleRepository;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.role.service.RankLocks;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.service.GeneralSettingsService;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.dto.response.StaffResponse;
import gg.modl.backend.staff.repository.InvitationRepository;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.support.TestServers;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StaffServiceRoleDisplayTest {

    private static final String ADMIN_EMAIL = "owner@example.com";
    private static final String STALE_EMAIL = "stale@example.com";

    @Test
    void staleSuperAdminRoleStillRendersItsStoredRoleName() {
        StaffRepository staffRepository = mock(StaffRepository.class);
        PermissionService permissionService = mock(PermissionService.class);
        InvitationRepository invitationRepository = mock(InvitationRepository.class);
        Server server = server();

        when(staffRepository.findAll()).thenReturn(List.of(staleSuperAdminStaff()));
        when(invitationRepository.findActive(any(Instant.class))).thenReturn(List.of());
        when(permissionService.resolveRoleNames(eq(server), any()))
            .thenReturn(Map.of(RoleAuthorization.SUPER_ADMIN_ROLE_ID, RoleAuthorization.SUPER_ADMIN_ROLE_NAME));

        List<StaffResponse> staff = staffService(invitationRepository, staffRepository, permissionService).getAllStaff(server);

        assertEquals(RoleAuthorization.SUPER_ADMIN_ROLE_NAME,
            staff.stream().filter(entry -> STALE_EMAIL.equals(entry.email())).findFirst().orElseThrow().role());
    }

    @Test
    void staleSuperAdminRoleConfersNoEffectiveRoleName() {
        StaffRoleRepository roleRepository = mock(StaffRoleRepository.class);
        PermissionService permissionService =
            new PermissionService(roleRepository, mock(PunishmentTypeService.class), mock(StaffRepository.class));
        Server server = server();
        when(roleRepository.findById(RoleAuthorization.SUPER_ADMIN_ROLE_ID)).thenReturn(Optional.of(StaffRole.builder()
            .id(RoleAuthorization.SUPER_ADMIN_ROLE_ID)
            .name(RoleAuthorization.SUPER_ADMIN_ROLE_NAME)
            .build()));

        Staff stale = staleSuperAdminStaff();

        assertEquals(RoleAuthorization.SUPER_ADMIN_ROLE_NAME, permissionService.assignedRoleName(server, stale));
        assertEquals("", permissionService.effectiveRoleName(server, stale));
    }

    private StaffService staffService(InvitationRepository invitationRepository,
                                      StaffRepository staffRepository,
                                      PermissionService permissionService) {
        return new StaffService(
            invitationRepository,
            staffRepository,
            permissionService,
            mock(RoleAuthorization.class),
            mock(RankLocks.class),
            mock(AccountOffboarding.class),
            mock(GeneralSettingsService.class),
            mock(StaffChanges.class),
            new ImmediateUnitOfWork(),
            Clock.systemUTC()
        );
    }

    private Staff staleSuperAdminStaff() {
        return Staff.builder()
            .id("stale-staff-id")
            .email(STALE_EMAIL)
            .username("stale")
            .roleId(RoleAuthorization.SUPER_ADMIN_ROLE_ID)
            .build();
    }

    private Server server() {
        Server server = TestServers.server("Server", ADMIN_EMAIL);
        server.setId("server-id");
        return server;
    }
}
