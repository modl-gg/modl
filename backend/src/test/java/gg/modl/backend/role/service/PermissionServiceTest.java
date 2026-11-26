package gg.modl.backend.role.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.role.data.StaffRole;
import gg.modl.backend.role.repository.StaffRoleRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.support.TestServers;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PermissionServiceTest {

    @Test
    void hasPermissionResolvesRoleByIdNotName() {
        StaffRoleRepository roleRepository = mock(StaffRoleRepository.class);
        PermissionService service = newService(roleRepository);
        Server server = server();
        StaffRole role = role("custom-1", "Support", 5, List.of("ticket.view.all"));
        when(roleRepository.findById("custom-1")).thenReturn(Optional.of(role));

        assertTrue(service.hasPermission(server, "custom-1", "ticket.view.all"));
        assertFalse(service.hasPermission(server, "custom-1", "admin.staff.manage"));
        verify(roleRepository, never()).findByNameIgnoreCase(any());
    }

    @Test
    void permissionsWithoutAnEffectAreGoneAndTicketNotePermissionsStandAlone() {
        PermissionService service = newService(mock(StaffRoleRepository.class));
        List<String> ids = service.getBasePermissions().stream().map(permission -> permission.id()).toList();

        assertFalse(ids.contains("staff.maintenance"));
        assertFalse(ids.contains("ticket.delete.all"));
        assertFalse(ids.contains("ticket.manage.subscribe"));
        assertTrue(ids.contains("admin.settings.view.storage"));
        assertTrue(ids.contains("admin.settings.modify.storage"));
        assertTrue(service.getBasePermissions().stream()
            .filter(permission -> PermissionService.NOT_IMPLIED_BY_PARENT.contains(permission.id()))
            .allMatch(permission -> permission.parentId() == null));
        assertEquals(2, service.getBasePermissions().stream()
            .filter(permission -> PermissionService.NOT_IMPLIED_BY_PARENT.contains(permission.id())).count());
    }

    @Test
    void thePlayerCategoryHoldsThreeIndependentPermissions() {
        PermissionService service = newService(mock(StaffRoleRepository.class));

        assertEquals("Player Permissions", service.getPermissionCategories().get("player"));
        assertEquals(List.of("player.note", "player.warn", "replay.share"), service.getBasePermissions().stream()
            .filter(permission -> "player".equals(permission.category()))
            .map(permission -> permission.id())
            .toList());
        assertTrue(service.getBasePermissions().stream()
            .filter(permission -> "player".equals(permission.category()))
            .allMatch(permission -> permission.parentId() == null && !permission.superAdminOnly()));
        assertFalse(RoleAuthorization.roleGrants(role("mod", "Mod", 2, List.of("punishment.modify")), "player.note"));
        assertFalse(RoleAuthorization.roleGrants(role("noter", "Noter", 2, List.of("player.note")), "player.warn"));
    }

    @Test
    void hasPermissionSurvivesRoleRename() {
        StaffRoleRepository roleRepository = mock(StaffRoleRepository.class);
        PermissionService service = newService(roleRepository);
        Server server = server();
        StaffRole renamed = role("custom-1", "Helpers", 5, List.of("ticket.view.all"));
        when(roleRepository.findById("custom-1")).thenReturn(Optional.of(renamed));

        assertTrue(service.hasPermission(server, "custom-1", "ticket.view.all"));
    }

    @Test
    void hierarchicalPermissionStillMatches() {
        StaffRoleRepository roleRepository = mock(StaffRoleRepository.class);
        PermissionService service = newService(roleRepository);
        Server server = server();
        when(roleRepository.findById("admin"))
            .thenReturn(Optional.of(role("admin", "Admin", 1, List.of("admin.staff.manage"))));

        assertTrue(service.hasPermission(server, "admin", "admin.staff.manage.members"));
    }

    @Test
    void resolveRoleNameReturnsCurrentNameAndFallsBackToId() {
        StaffRoleRepository roleRepository = mock(StaffRoleRepository.class);
        PermissionService service = newService(roleRepository);
        Server server = server();
        when(roleRepository.findById("custom-1")).thenReturn(Optional.of(role("custom-1", "Support", 5, List.of())));
        when(roleRepository.findById("ghost")).thenReturn(Optional.empty());

        assertEquals("Support", service.resolveRoleName(server, "custom-1"));
        assertEquals("ghost", service.resolveRoleName(server, "ghost"));
        assertEquals("", service.resolveRoleName(server, null));
    }

    @Test
    void resolveRoleNamesBuildsIdToNameMap() {
        StaffRoleRepository roleRepository = mock(StaffRoleRepository.class);
        PermissionService service = newService(roleRepository);
        Server server = server();
        when(roleRepository.findByIds(Set.of("admin", "helper")))
            .thenReturn(List.of(role("admin", "Admin", 1, List.of()), role("helper", "Helper", 3, List.of())));

        Map<String, String> names = service.resolveRoleNames(server, List.of("admin", "helper", "admin"));
        assertEquals("Admin", names.get("admin"));
        assertEquals("Helper", names.get("helper"));
    }

    @Test
    void superAdminOnlyPermissionsAreExcludedFromTheGrantableCatalog() {
        PermissionService service = newService(mock(StaffRoleRepository.class));
        Server server = server();

        List<String> all = service.getAllPermissionIds(server);
        List<String> grantable = service.getGrantablePermissionIds(server);

        assertTrue(all.containsAll(PermissionService.superAdminOnlyPermissionIds()));
        assertTrue(PermissionService.superAdminOnlyPermissionIds().stream().noneMatch(grantable::contains));
        assertTrue(PermissionService.isSuperAdminOnly(PermissionService.ADMIN_AUDIT_ROLLBACK));
        assertFalse(PermissionService.isSuperAdminOnly(PermissionService.ADMIN_SETTINGS_VIEW));
    }

    private PermissionService newService(StaffRoleRepository roleRepository) {
        return new PermissionService(roleRepository, mock(PunishmentTypeService.class), mock(StaffRepository.class));
    }

    private StaffRole role(String id, String name, int order, List<String> permissions) {
        return StaffRole.builder().id(id).name(name).order(order).permissions(permissions).build();
    }

    private Server server() {
        Server server = TestServers.server("Server", "owner@example.com");
        server.setId("server-id");
        return server;
    }
}
