package gg.modl.backend.infrastructure.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReplaySharingPolicyTest {
    private static final Server SERVER = TestServers.server("server", "admin@example.com");
    private static final String ROLE = "role";
    private static final PanelAccessRequest CREATE_SHARE = new PanelAccessRequest("POST", "/v1/panel/replays/r1/shares");
    private static final PanelAccessRequest LIST_SHARES = new PanelAccessRequest("GET", "/v1/panel/replays/r1/shares");

    private final PermissionService permissions = mock(PermissionService.class);
    private final PanelPrincipalPermissions staff = new PanelPrincipalPermissions(SERVER, ROLE, false, permissions);

    @ParameterizedTest
    @ValueSource(strings = {PermissionService.PUNISHMENT_VIEW, PermissionService.PUNISHMENT_MODIFY})
    void viewingPlayersWithoutTheSharePermissionIsNotEnough(String permission) {
        when(permissions.hasPermission(SERVER, ROLE, permission)).thenReturn(true);

        assertThat(ReplaySharingPolicy.INSTANCE.permitsWithRole(CREATE_SHARE, staff)).isFalse();
        assertThat(ReplaySharingPolicy.INSTANCE.permitsWithRole(LIST_SHARES, staff)).isFalse();
    }

    @Test
    void anApplyPermissionWithoutTheSharePermissionIsNotEnough() {
        when(permissions.hasAnyPermissionWithPrefix(SERVER, ROLE, PermissionService.PUNISHMENT_APPLY_PREFIX)).thenReturn(true);

        assertThat(ReplaySharingPolicy.INSTANCE.permitsWithRole(CREATE_SHARE, staff)).isFalse();
    }

    @Test
    void theSharePermissionWithoutPlayerViewIsNotEnough() {
        when(permissions.hasPermission(SERVER, ROLE, PermissionService.REPLAY_SHARE)).thenReturn(true);

        assertThat(staff.canViewPlayers()).isFalse();
        assertThat(ReplaySharingPolicy.INSTANCE.permitsWithRole(CREATE_SHARE, staff)).isFalse();
        assertThat(ReplaySharingPolicy.INSTANCE.permitsWithRole(LIST_SHARES, staff)).isFalse();
    }

    @Test
    void playerViewTogetherWithTheSharePermissionShares() {
        when(permissions.hasPermission(SERVER, ROLE, PermissionService.PUNISHMENT_VIEW)).thenReturn(true);
        when(permissions.hasPermission(SERVER, ROLE, PermissionService.REPLAY_SHARE)).thenReturn(true);

        assertThat(ReplaySharingPolicy.INSTANCE.permitsWithRole(CREATE_SHARE, staff)).isTrue();
        assertThat(ReplaySharingPolicy.INSTANCE.permitsWithRole(LIST_SHARES, staff)).isTrue();
    }

    @Test
    void aSuperAdminShares() {
        PanelPrincipalPermissions superAdmin = new PanelPrincipalPermissions(SERVER, null, true, permissions);

        assertThat(ReplaySharingPolicy.INSTANCE.permitsWithRole(CREATE_SHARE, superAdmin)).isTrue();
        assertThat(ReplaySharingPolicy.INSTANCE.permitsWithRole(LIST_SHARES, superAdmin)).isTrue();
        verifyNoInteractions(permissions);
    }

    @Test
    void nobodySharesWithoutARole() {
        assertThat(ReplaySharingPolicy.INSTANCE.permitsWithoutRole(CREATE_SHARE)).isFalse();
        assertThat(ReplaySharingPolicy.INSTANCE.permitsWithoutRole(LIST_SHARES)).isFalse();
    }
}
