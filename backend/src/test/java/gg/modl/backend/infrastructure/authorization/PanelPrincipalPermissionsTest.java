package gg.modl.backend.infrastructure.authorization;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import org.junit.jupiter.api.Test;

class PanelPrincipalPermissionsTest {

    private static final Server SERVER = TestServers.server("server", "admin@example.com");
    private static final String ROLE = "role";

    private final PermissionService permissionService = mock(PermissionService.class);

    @Test
    void superAdminHoldsEveryPermissionWithoutARoleLookup() {
        PanelPrincipalPermissions superAdmin = new PanelPrincipalPermissions(SERVER, null, true, permissionService);

        assertTrue(superAdmin.has(PermissionService.ADMIN_AUDIT_ROLLBACK));
        assertTrue(superAdmin.hasAnyWithPrefix(PermissionService.PUNISHMENT_APPLY_PREFIX));
        verifyNoInteractions(permissionService);
    }

    @Test
    void staffPermissionsComeFromTheirRole() {
        PanelPrincipalPermissions staff = new PanelPrincipalPermissions(SERVER, ROLE, false, permissionService);
        when(permissionService.hasPermission(SERVER, ROLE, PermissionService.TICKET_VIEW_ALL)).thenReturn(true);
        when(permissionService.hasAnyPermissionWithPrefix(SERVER, ROLE, PermissionService.TICKET_PERMISSION_PREFIX)).thenReturn(true);

        assertTrue(staff.has(PermissionService.TICKET_VIEW_ALL));
        assertFalse(staff.has(PermissionService.ADMIN_SETTINGS_MODIFY));
        assertTrue(staff.hasAnyWithPrefix(PermissionService.TICKET_PERMISSION_PREFIX));
        assertFalse(staff.hasAnyWithPrefix(PermissionService.PUNISHMENT_APPLY_PREFIX));
    }
}
