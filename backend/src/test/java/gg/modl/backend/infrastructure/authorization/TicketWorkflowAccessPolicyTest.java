package gg.modl.backend.infrastructure.authorization;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TicketWorkflowAccessPolicyTest {

    private static final Server SERVER = TestServers.server("server", "admin@example.com");
    private static final String ROLE = "role";
    private static final String QUICK_RESPONSES = "/v1/panel/settings/quick-responses";
    private static final PanelAccessRequest READ = new PanelAccessRequest("GET", QUICK_RESPONSES);
    private static final PanelAccessRequest HEAD = new PanelAccessRequest("HEAD", QUICK_RESPONSES);
    private static final PanelAccessRequest WRITE = new PanelAccessRequest("PATCH", QUICK_RESPONSES);

    private final PermissionService permissionService = mock(PermissionService.class);
    private final PanelPrincipalPermissions principal = new PanelPrincipalPermissions(SERVER, ROLE, false, permissionService);

    @Test
    void neverPermitsWithoutARole() {
        assertFalse(TicketWorkflowAccessPolicy.INSTANCE.permitsWithoutRole(READ));
        assertFalse(TicketWorkflowAccessPolicy.INSTANCE.permitsWithoutRole(WRITE));
    }

    @ParameterizedTest
    @ValueSource(strings = {PermissionService.ADMIN_SETTINGS_VIEW, PermissionService.APPEAL_MODIFY})
    void settingsViewersAndAppealHandlersMayRead(String permission) {
        grant(permission);

        assertTrue(TicketWorkflowAccessPolicy.INSTANCE.permitsWithRole(READ, principal));
        assertTrue(TicketWorkflowAccessPolicy.INSTANCE.permitsWithRole(HEAD, principal));
    }

    @Test
    void anyTicketPermissionHolderMayRead() {
        when(permissionService.hasAnyPermissionWithPrefix(SERVER, ROLE, PermissionService.TICKET_PERMISSION_PREFIX)).thenReturn(true);

        assertTrue(TicketWorkflowAccessPolicy.INSTANCE.permitsWithRole(READ, principal));
    }

    @Test
    void staffWithoutTicketOrSettingsAccessMayNotRead() {
        grant(PermissionService.PUNISHMENT_MODIFY);

        assertFalse(TicketWorkflowAccessPolicy.INSTANCE.permitsWithRole(READ, principal));
    }

    @Test
    void ticketHandlersMayNotModify() {
        when(permissionService.hasAnyPermissionWithPrefix(eq(SERVER), eq(ROLE), anyString())).thenReturn(true);
        grant(PermissionService.ADMIN_SETTINGS_VIEW);
        grant(PermissionService.APPEAL_MODIFY);
        grant(PermissionService.TICKET_REPLY_ALL);

        assertFalse(TicketWorkflowAccessPolicy.INSTANCE.permitsWithRole(WRITE, principal));
    }

    @Test
    void settingsModifiersMayModify() {
        grant(PermissionService.ADMIN_SETTINGS_MODIFY);

        assertTrue(TicketWorkflowAccessPolicy.INSTANCE.permitsWithRole(WRITE, principal));
    }

    private void grant(String permission) {
        when(permissionService.hasPermission(SERVER, ROLE, permission)).thenReturn(true);
    }
}
