package gg.modl.backend.role.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.player.service.MinecraftModification;
import gg.modl.backend.role.service.RoleAuthorization.PerformerAuthority;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import org.junit.jupiter.api.Test;

class MinecraftIssuerAuthorizerTest {
    private final Server server = TestServers.server("Demo", "owner@example.com");
    private final RoleAuthorization roles = mock(RoleAuthorization.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final MinecraftIssuerAuthorizer authorizer = new MinecraftIssuerAuthorizer(roles, permissions);

    @Test
    void anActionWithoutAnIssuerIsRefusedInsteadOfTrustedAsConsole() {
        assertThatThrownBy(() -> MinecraftIssuer.of(null, false))
            .isInstanceOfSatisfying(ValidationException.class,
                refused -> assertThat(refused.getMachineCode()).isEqualTo(MinecraftIssuer.ISSUER_REQUIRED));
        assertThatThrownBy(() -> MinecraftIssuer.of(" ", false)).isInstanceOf(ValidationException.class);
        verifyNoInteractions(roles, permissions);
    }

    @Test
    void anActionCannotBothNameAStaffMemberAndClaimTheConsole() {
        assertThatThrownBy(() -> MinecraftIssuer.of("helper", true)).isInstanceOf(ValidationException.class);
    }

    @Test
    void onlyTheExplicitConsoleMarkerSkipsTheStaffChecks() {
        MinecraftIssuer console = MinecraftIssuer.of(null, true);

        assertThat(console.isConsole()).isTrue();
        assertThat(console.staffId()).isNull();
        assertThatCode(() -> authorizer.requirePermission(server, console, PermissionService.PUNISHMENT_MODIFY_PARDON))
            .doesNotThrowAnyException();
        verifyNoInteractions(roles, permissions);
    }

    @Test
    void eachActionNeedsItsOwnPermission() {
        when(roles.minecraftPerformer(server, "helper")).thenReturn(new PerformerAuthority("h@example.com", "helper-role", false, true));
        when(permissions.hasPermission(server, "helper-role", PermissionService.PUNISHMENT_MODIFY_NOTE)).thenReturn(true);
        MinecraftIssuer helper = MinecraftIssuer.of(" helper ", false);

        assertThat(helper.staffId()).isEqualTo("helper");
        assertThatCode(() -> authorizer.requirePermission(server, helper, MinecraftModification.NOTE.permission()))
            .doesNotThrowAnyException();
        for (MinecraftModification action : MinecraftModification.values()) {
            if (action != MinecraftModification.NOTE) {
                assertThatThrownBy(() -> authorizer.requirePermission(server, helper, action.permission()))
                    .isInstanceOf(ForbiddenException.class);
            }
        }
        assertThatThrownBy(() -> authorizer.requirePermission(server, helper, PermissionService.TICKET_CLOSE_ALL))
            .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void permitsAnswersWithoutThrowingForKnownStaffAndTheConsole() {
        when(roles.minecraftPerformer(server, "helper")).thenReturn(new PerformerAuthority("h@example.com", "helper-role", false, true));
        when(permissions.hasPermission(server, "helper-role", PermissionService.PUNISHMENT_MODIFY_DURATION)).thenReturn(true);
        MinecraftIssuer helper = MinecraftIssuer.of("helper", false);

        assertThat(authorizer.permits(server, helper, PermissionService.PUNISHMENT_MODIFY_DURATION)).isTrue();
        assertThat(authorizer.permits(server, helper, PermissionService.PUNISHMENT_MODIFY_PARDON)).isFalse();
        assertThat(authorizer.permits(server, MinecraftIssuer.of(null, true), PermissionService.PUNISHMENT_MODIFY_PARDON)).isTrue();
    }

    @Test
    void anUnknownStaffIdFailsClosed() {
        when(roles.minecraftPerformer(any(), any())).thenReturn(PerformerAuthority.unidentified());

        assertThatThrownBy(() -> authorizer.requirePermission(server, MinecraftIssuer.of("deleted-staff", false),
            MinecraftModification.PARDON.permission())).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void theOwnerMayDoEverything() {
        when(roles.minecraftPerformer(server, "owner")).thenReturn(new PerformerAuthority("owner@example.com", null, true, true));
        MinecraftIssuer owner = MinecraftIssuer.of("owner", false);

        for (MinecraftModification action : MinecraftModification.values()) {
            assertThatCode(() -> authorizer.requirePermission(server, owner, action.permission())).doesNotThrowAnyException();
        }
    }
}
