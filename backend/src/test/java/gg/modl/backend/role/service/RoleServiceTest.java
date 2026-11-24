package gg.modl.backend.role.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.ImmediateUnitOfWork;
import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.role.data.StaffRole;
import gg.modl.backend.role.repository.StaffRoleRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.service.ServerTimestampService;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.staff.service.StaffLookupCache;
import gg.modl.backend.support.TestServers;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RoleServiceTest {

    private static final RoleAuthorization.PerformerAuthority SUPER_ADMIN =
        new RoleAuthorization.PerformerAuthority("owner@example.com", null, true, true);

    @Test
    void defaultTicketRolesIncludeAppealModifyPermission() {
        StaffRoleRepository roleRepository = mock(StaffRoleRepository.class);
        PermissionService permissionService = mock(PermissionService.class);
        RoleService roleService = roleService(roleRepository, permissionService);
        Server server = TestServers.server("server", "admin@example.com");
        when(permissionService.getPunishmentPermissions(server)).thenReturn(List.of());
        when(permissionService.getGrantablePermissionIds(server)).thenReturn(List.of(
            "ticket.view.all",
            "ticket.reply.all",
            "appeal.modify"
        ));

        roleService.createDefaultRoles(server);

        ArgumentCaptor<StaffRole> captor = ArgumentCaptor.forClass(StaffRole.class);
        verify(roleRepository, times(4)).insertIfAbsent(captor.capture());
        for (StaffRole role : captor.getAllValues()) {
            assertFalse(role.getPermissions().stream().anyMatch(PermissionService::isSuperAdminOnly), role.getId());
            if (!"super-admin".equals(role.getId())) {
                assertTrue(role.getPermissions().contains("appeal.modify"), role.getId());
            }
        }
    }

    @Test
    void defaultRolesAreSeededWithoutDescriptions() {
        StaffRoleRepository roleRepository = mock(StaffRoleRepository.class);
        PermissionService permissionService = mock(PermissionService.class);
        RoleService roleService = roleService(roleRepository, permissionService);
        Server server = TestServers.server("server", "admin@example.com");
        when(permissionService.getPunishmentPermissions(server)).thenReturn(List.of());
        when(permissionService.getGrantablePermissionIds(server)).thenReturn(List.of());

        roleService.createDefaultRoles(server);

        ArgumentCaptor<StaffRole> captor = ArgumentCaptor.forClass(StaffRole.class);
        verify(roleRepository, times(4)).insertIfAbsent(captor.capture());
        assertTrue(captor.getAllValues().stream().allMatch(role -> role.getDescription() == null));
    }

    @Test
    void defaultAdminAndModeratorRolesKeepTaggingAndHidingTickets() {
        StaffRoleRepository roleRepository = mock(StaffRoleRepository.class);
        PermissionService permissionService = mock(PermissionService.class);
        RoleService roleService = roleService(roleRepository, permissionService);
        Server server = TestServers.server("server", "admin@example.com");
        when(permissionService.getPunishmentPermissions(server)).thenReturn(List.of());
        when(permissionService.getGrantablePermissionIds(server)).thenReturn(List.of());

        roleService.createDefaultRoles(server);

        ArgumentCaptor<StaffRole> captor = ArgumentCaptor.forClass(StaffRole.class);
        verify(roleRepository, times(4)).insertIfAbsent(captor.capture());
        for (StaffRole role : captor.getAllValues()) {
            boolean managesTickets = !"helper".equals(role.getId());
            assertEquals(managesTickets, RoleAuthorization.roleGrants(role, PermissionService.TICKET_MANAGE_TAGS), role.getId());
            assertEquals(managesTickets, RoleAuthorization.roleGrants(role, PermissionService.TICKET_MANAGE_HIDE), role.getId());
        }
    }

    @Test
    void everyDefaultStaffRoleSeesAndAddsTicketNotesExplicitly() {
        StaffRoleRepository roleRepository = mock(StaffRoleRepository.class);
        PermissionService permissionService = mock(PermissionService.class);
        RoleService roleService = roleService(roleRepository, permissionService);
        Server server = TestServers.server("server", "admin@example.com");
        when(permissionService.getPunishmentPermissions(server)).thenReturn(List.of());
        when(permissionService.getGrantablePermissionIds(server)).thenReturn(List.of());

        roleService.createDefaultRoles(server);

        ArgumentCaptor<StaffRole> captor = ArgumentCaptor.forClass(StaffRole.class);
        verify(roleRepository, times(4)).insertIfAbsent(captor.capture());
        for (StaffRole role : captor.getAllValues()) {
            if (!"super-admin".equals(role.getId())) {
                assertTrue(role.getPermissions().containsAll(
                    List.of(PermissionService.TICKET_VIEW_ALL_NOTES, PermissionService.TICKET_REPLY_ALL_NOTES)), role.getId());
            }
            assertFalse(role.getPermissions().stream().anyMatch(List.of("staff.maintenance", "ticket.delete.all",
                "ticket.manage.subscribe")::contains), role.getId());
        }
    }

    @Test
    void everyDefaultRoleAddsNotesWarnsAndViewsPlayers() {
        StaffRoleRepository roleRepository = mock(StaffRoleRepository.class);
        PermissionService permissionService = mock(PermissionService.class);
        RoleService roleService = roleService(roleRepository, permissionService);
        Server server = TestServers.server("server", "admin@example.com");
        when(permissionService.getPunishmentPermissions(server)).thenReturn(List.of());
        when(permissionService.getGrantablePermissionIds(server)).thenReturn(List.of(
            PermissionService.PUNISHMENT_VIEW, PermissionService.PLAYER_NOTE, PermissionService.PLAYER_WARN));

        roleService.createDefaultRoles(server);

        ArgumentCaptor<StaffRole> captor = ArgumentCaptor.forClass(StaffRole.class);
        verify(roleRepository, times(4)).insertIfAbsent(captor.capture());
        for (StaffRole role : captor.getAllValues()) {
            assertTrue(role.getPermissions().containsAll(
                List.of(PermissionService.PLAYER_NOTE, PermissionService.PLAYER_WARN, PermissionService.PUNISHMENT_VIEW)),
                role.getId());
        }
        StaffRole helper = captor.getAllValues().getLast();
        assertEquals(RoleService.HELPER_ROLE_ID, helper.getId());
        assertFalse(RoleAuthorization.roleGrants(helper, PermissionService.PUNISHMENT_MODIFY_NOTE));
        assertFalse(RoleAuthorization.roleGrants(helper, PermissionService.PUNISHMENT_MODIFY_PARDON));
        assertFalse(helper.getPermissions().stream().anyMatch(id -> id.startsWith(PermissionService.PUNISHMENT_APPLY_PREFIX)));
    }

    @Test
    void updateRolePermissionsRejectsSuperAdminRole() {
        StaffRoleRepository roleRepository = mock(StaffRoleRepository.class);
        PermissionService permissionService = mock(PermissionService.class);
        RoleService roleService = roleService(roleRepository, permissionService);
        Server server = server();

        assertThrows(ForbiddenException.class,
            () -> roleService.updateRolePermissions(server, "super-admin", List.of("ticket.reply.all"), SUPER_ADMIN));
        assertFalse(roleService.updateRolePermissions(server, "custom-super-admin-x", List.of("ticket.reply.all"), SUPER_ADMIN));
        verify(roleRepository, never()).save(any());
    }

    @Test
    void updateRolePermissionsReturnsFalseForMissingRole() {
        StaffRoleRepository roleRepository = mock(StaffRoleRepository.class);
        PermissionService permissionService = mock(PermissionService.class);
        RoleService roleService = roleService(roleRepository, permissionService);
        Server server = server();
        when(roleRepository.findByIdForUpdate("missing")).thenReturn(Optional.empty());

        assertFalse(roleService.updateRolePermissions(server, "missing", List.of("ticket.reply.all"), SUPER_ADMIN));
        verify(roleRepository, never()).save(any());
    }

    @Test
    void updateRolePermissionsFiltersInvalidPermissionIds() {
        StaffRoleRepository roleRepository = mock(StaffRoleRepository.class);
        PermissionService permissionService = mock(PermissionService.class);
        RoleService roleService = roleService(roleRepository, permissionService);
        Server server = server();
        StaffRole role = StaffRole.builder()
            .id("custom-1").name("Custom").order(5)
            .permissions(new ArrayList<>(List.of("ticket.reply.all", "ticket.close.all")))
            .build();
        when(roleRepository.findByIdForUpdate("custom-1")).thenReturn(Optional.of(role));
        when(permissionService.getGrantablePermissionIds(server)).thenReturn(List.of("ticket.reply.all", "ticket.close.all"));

        boolean result = roleService.updateRolePermissions(
            server, "custom-1", List.of("ticket.reply.all", "bogus.perm"), SUPER_ADMIN);

        assertTrue(result);
        ArgumentCaptor<StaffRole> captor = ArgumentCaptor.forClass(StaffRole.class);
        verify(roleRepository).save(captor.capture());
        assertEquals(List.of("ticket.reply.all"), captor.getValue().getPermissions());
    }

    @Test
    void updateRolePermissionsRejectsExpansionWithPerformerIdentity() {
        StaffRoleRepository roleRepository = mock(StaffRoleRepository.class);
        PermissionService permissionService = mock(PermissionService.class);
        RoleService roleService = roleService(roleRepository, permissionService);
        Server server = server();
        StaffRole targetRole = StaffRole.builder()
            .id("custom-target").name("Target").order(5)
            .permissions(new ArrayList<>(List.of("ticket.reply.all")))
            .build();
        StaffRole performerRole = StaffRole.builder()
            .id("custom-performer").name("Performer").order(2)
            .permissions(new ArrayList<>(List.of("ticket.reply.all")))
            .build();
        when(roleRepository.findByIdForUpdate("custom-target")).thenReturn(Optional.of(targetRole));
        when(permissionService.getGrantablePermissionIds(server)).thenReturn(List.of("ticket.reply.all", "punishment.modify"));
        when(permissionService.getRoleById(server, "custom-performer")).thenReturn(Optional.of(performerRole));
        when(permissionService.hasPermission(server, "custom-performer", PermissionService.ADMIN_STAFF_MANAGE_ROLES))
            .thenReturn(true);

        RoleAuthorization.PerformerAuthority performer =
            new RoleAuthorization.PerformerAuthority("performer@example.com", "custom-performer", false, true);
        boolean result = roleService.updateRolePermissions(
            server, "custom-target", List.of("ticket.reply.all", "punishment.modify"), performer);

        assertTrue(result);
        ArgumentCaptor<StaffRole> captor = ArgumentCaptor.forClass(StaffRole.class);
        verify(roleRepository).save(captor.capture());
        assertEquals(List.of("ticket.reply.all"), captor.getValue().getPermissions());
    }

    private RoleService roleService(StaffRoleRepository roleRepository, PermissionService permissionService) {
        StaffRepository staffRepository = mock(StaffRepository.class);
        return new RoleService(
            roleRepository,
            staffRepository,
            permissionService,
            new RoleAuthorization(permissionService, staffRepository, mock(StaffLookupCache.class)),
            mock(RankLocks.class),
            mock(ServerTimestampService.class),
            new ImmediateUnitOfWork(),
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    }

    private Server server() {
        Server server = TestServers.server("server", "admin@example.com");
        server.setId("server-id");
        return server;
    }
}
