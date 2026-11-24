package gg.modl.backend.staff.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.AccountOffboarding;
import gg.modl.backend.auth.ImmediateUnitOfWork;
import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.role.data.StaffRole;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.role.service.RankLocks;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.service.GeneralSettingsService;
import gg.modl.backend.staff.data.Invitation;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.repository.InvitationRepository;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.support.TestServers;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StaffServiceRoleSecurityTest {

    private final StaffRepository staffRepository = mock(StaffRepository.class);
    private final InvitationRepository invitationRepository = mock(InvitationRepository.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final RoleAuthorization roleAuthorization = new RoleAuthorization(permissionService, staffRepository, mock(StaffLookupCache.class));
    private final StaffService service = new StaffService(
        invitationRepository,
        staffRepository,
        permissionService,
        roleAuthorization,
        mock(RankLocks.class),
        mock(AccountOffboarding.class),
        mock(GeneralSettingsService.class),
        mock(StaffChanges.class),
        new ImmediateUnitOfWork(),
        Clock.systemUTC()
    );

    @Test
    void memberManagerCannotAssignRoleAboveOwnAuthority() {
        Server server = server();
        Staff target = staff("helper-staff-id", "target@example.com", "helper");
        when(staffRepository.findByIdForUpdate("helper-staff-id")).thenReturn(Optional.of(target));
        when(permissionService.getRoleById(server, "moderator")).thenReturn(Optional.of(role("moderator", "Moderator", 2)));
        when(permissionService.getRoleById(server, "helper")).thenReturn(Optional.of(role("helper", "Helper", 3)));
        when(permissionService.getRoleByName(server, "Admin")).thenReturn(Optional.of(role("admin", "Admin", 1)));
        when(permissionService.getRoleById(server, "admin")).thenReturn(Optional.of(role("admin", "Admin", 1)));

        RoleAuthorization.PerformerAuthority performer = moderatorPerformer();

        assertThrows(
            ForbiddenException.class,
            () -> service.updateStaffRole(server, "helper-staff-id", "Admin", performer)
        );
        verify(staffRepository, never()).updateRole(any(), any(), any());
    }

    @Test
    void subordinateCannotDemoteSuperior() {
        Server server = server();
        Staff superior = staff("admin-staff-id", "admin@example.com", "admin");
        when(staffRepository.findByIdForUpdate("admin-staff-id")).thenReturn(Optional.of(superior));
        when(permissionService.getRoleById(server, "moderator")).thenReturn(Optional.of(role("moderator", "Moderator", 2)));
        when(permissionService.getRoleById(server, "admin")).thenReturn(Optional.of(role("admin", "Admin", 1)));

        RoleAuthorization.PerformerAuthority performer = moderatorPerformer();

        assertThrows(
            ForbiddenException.class,
            () -> service.updateStaffRole(server, "admin-staff-id", "Helper", performer)
        );
        verify(staffRepository, never()).updateRole(any(), any(), any());
    }

    @Test
    void subordinateCannotDeleteSuperior() {
        Server server = server();
        Staff superior = staff("admin-staff-id", "admin@example.com", "admin");
        when(staffRepository.findByIdForUpdate("admin-staff-id")).thenReturn(Optional.of(superior));
        when(permissionService.getRoleById(server, "moderator")).thenReturn(Optional.of(role("moderator", "Moderator", 2)));
        when(permissionService.getRoleById(server, "admin")).thenReturn(Optional.of(role("admin", "Admin", 1)));

        RoleAuthorization.PerformerAuthority performer = moderatorPerformer();

        assertThrows(
            ForbiddenException.class,
            () -> service.deleteStaff(server, "admin-staff-id", performer)
        );
        verify(staffRepository, never()).deleteById("admin-staff-id");
    }

    @Test
    void memberManagerCannotRevokeAnInvitationForARoleTheyCannotGrant() {
        Server server = server();
        when(invitationRepository.findById("invitation-id")).thenReturn(Optional.of(invitation("admin")));
        when(permissionService.getRoleById(server, "moderator")).thenReturn(Optional.of(role("moderator", "Moderator", 2)));
        when(permissionService.getRoleById(server, "admin")).thenReturn(Optional.of(role("admin", "Admin", 1)));

        RoleAuthorization.PerformerAuthority performer = moderatorPerformer();

        assertThrows(ForbiddenException.class, () -> service.deleteStaff(server, "invitation-id", performer));
        verify(invitationRepository, never()).deleteById("invitation-id");
    }

    @Test
    void memberManagerRevokesAnInvitationForALowerRole() {
        Server server = server();
        when(invitationRepository.findById("invitation-id")).thenReturn(Optional.of(invitation("helper")));
        when(invitationRepository.deleteById("invitation-id")).thenReturn(true);
        when(permissionService.getRoleById(server, "moderator")).thenReturn(Optional.of(role("moderator", "Moderator", 2)));
        when(permissionService.getRoleById(server, "helper")).thenReturn(Optional.of(role("helper", "Helper", 3)));

        assertTrue(service.deleteStaff(server, "invitation-id", moderatorPerformer()));
        verify(staffRepository, never()).deleteById(any(String.class));
    }

    @Test
    void invitationsWhoseRoleWasDeletedCanStillBeRevoked() {
        Server server = server();
        when(invitationRepository.findById("invitation-id")).thenReturn(Optional.of(invitation("deleted-role")));
        when(invitationRepository.deleteById("invitation-id")).thenReturn(true);
        when(permissionService.getRoleById(server, "moderator")).thenReturn(Optional.of(role("moderator", "Moderator", 2)));

        assertTrue(service.deleteStaff(server, "invitation-id", moderatorPerformer()));
        assertTrue(service.deleteStaff(server, "invitation-id",
            new RoleAuthorization.PerformerAuthority("owner@example.com", null, true, true)));
    }

    private static RoleAuthorization.PerformerAuthority moderatorPerformer() {
        return new RoleAuthorization.PerformerAuthority("mod@example.com", "moderator", false, true);
    }

    private static Invitation invitation(String roleId) {
        return Invitation.builder()
            .id("invitation-id")
            .email("invitee@example.com")
            .roleId(roleId)
            .build();
    }

    private Staff staff(String id, String email, String roleId) {
        return Staff.builder()
            .id(id)
            .email(email)
            .username(id)
            .roleId(roleId)
            .build();
    }

    private StaffRole role(String id, String name, int order) {
        return StaffRole.builder()
            .id(id)
            .name(name)
            .order(order)
            .permissions(List.of("admin.staff.manage.members"))
            .build();
    }

    private Server server() {
        Server server = TestServers.server("Server", "owner@example.com");
        server.setId("server-id");
        return server;
    }
}
