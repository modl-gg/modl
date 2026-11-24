package gg.modl.backend.infrastructure.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PlayerAccessPolicyTest {
    private static final Server SERVER = TestServers.server("server", "admin@example.com");
    private static final String ROLE = "role";
    private static final PanelAccessRequest PLAYER_WRITE = new PanelAccessRequest("POST",
        "/v1/panel/players/11111111-2222-3333-4444-555555555555/usernames");
    private static final PanelAccessRequest PLAYER_READ = new PanelAccessRequest("GET",
        "/v1/panel/players/11111111-2222-3333-4444-555555555555");

    private final PermissionService permissions = mock(PermissionService.class);
    private final PanelPrincipalPermissions principal = new PanelPrincipalPermissions(SERVER, ROLE, false, permissions);

    @ParameterizedTest
    @ValueSource(strings = {PermissionService.PUNISHMENT_VIEW, "punishment.apply.manual-ban", "ticket.view.all",
        PermissionService.PUNISHMENT_MODIFY_PARDON, PermissionService.PUNISHMENT_MODIFY_DURATION,
        PermissionService.PUNISHMENT_MODIFY_NOTE, PermissionService.PUNISHMENT_MODIFY_EVIDENCE,
        PermissionService.PUNISHMENT_MODIFY_OPTIONS, PermissionService.PUNISHMENT_MODIFY_TICKETS,
        PermissionService.PLAYER_NOTE, PermissionService.PLAYER_WARN})
    void narrowPermissionsDoNotWidenTheOtherPlayerWrites(String permission) {
        when(permissions.hasPermission(SERVER, ROLE, permission)).thenReturn(true);
        when(permissions.hasAnyPermissionWithPrefix(SERVER, ROLE, PermissionService.PUNISHMENT_APPLY_PREFIX)).thenReturn(true);

        assertThat(PlayerAccessPolicy.INSTANCE.permitsWithRole(PLAYER_WRITE, principal)).isFalse();
    }

    @Test
    void staffWhoModifyPunishmentsCanWriteToPlayers() {
        when(permissions.hasPermission(SERVER, ROLE, PermissionService.PUNISHMENT_MODIFY)).thenReturn(true);

        assertThat(PlayerAccessPolicy.INSTANCE.permitsWithRole(PLAYER_WRITE, principal)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {PermissionService.PUNISHMENT_VIEW, PermissionService.PUNISHMENT_MODIFY})
    void viewOrModifyReadsPlayers(String permission) {
        when(permissions.hasPermission(SERVER, ROLE, permission)).thenReturn(true);

        assertThat(PlayerAccessPolicy.INSTANCE.permitsWithRole(PLAYER_READ, principal)).isTrue();
    }

    @Test
    void anyApplyPermissionReadsPlayers() {
        when(permissions.hasAnyPermissionWithPrefix(SERVER, ROLE, PermissionService.PUNISHMENT_APPLY_PREFIX)).thenReturn(true);

        assertThat(PlayerAccessPolicy.INSTANCE.permitsWithRole(PLAYER_READ, principal)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {PermissionService.PLAYER_NOTE, PermissionService.PLAYER_WARN, "ticket.view.all",
        PermissionService.PUNISHMENT_MODIFY_PARDON, PermissionService.PUNISHMENT_MODIFY_NOTE})
    void otherPermissionsDoNotReadPlayers(String permission) {
        when(permissions.hasPermission(SERVER, ROLE, permission)).thenReturn(true);

        assertThat(PlayerAccessPolicy.INSTANCE.permitsWithRole(PLAYER_READ, principal)).isFalse();
        assertThat(principal.canViewPlayers()).isFalse();
    }

    @Test
    void nobodyWritesToPlayersWithoutARole() {
        assertThat(PlayerAccessPolicy.INSTANCE.permitsWithoutRole(PLAYER_WRITE)).isFalse();
    }
}
