package gg.modl.backend.player.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.player.data.punishment.PunishmentModificationType;
import gg.modl.backend.player.dto.request.AddModificationRequest;
import gg.modl.backend.role.service.MinecraftIssuer;
import gg.modl.backend.role.service.MinecraftIssuerAuthorizer;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class PunishmentModificationAuthorizerTest {
    private static final Server SERVER = TestServers.server("server", "admin@example.com");
    private static final String EMAIL = "staff@example.com";
    private static final String ROLE = "role";
    private static final Map<String, Set<String>> ALLOWED = Map.of(
        "punishment.modify.pardon", Set.of("MANUAL_PARDON", "APPEAL_ACCEPT"),
        "punishment.modify.duration", Set.of("MANUAL_DURATION_CHANGE", "APPEAL_DURATION_CHANGE"),
        "punishment.modify.options",
        Set.of("SET_ALT_BLOCKING_TRUE", "SET_ALT_BLOCKING_FALSE", "SET_WIPING_TRUE", "SET_WIPING_FALSE"),
        "punishment.modify.note", Set.of(),
        "punishment.modify.evidence", Set.of(),
        "punishment.modify.tickets", Set.of(),
        "punishment.view", Set.of(),
        "punishment.modify", Set.of("MANUAL_PARDON", "APPEAL_ACCEPT", "APPEAL_REJECT", "MANUAL_DURATION_CHANGE",
            "APPEAL_DURATION_CHANGE", "SET_ALT_BLOCKING_TRUE", "SET_ALT_BLOCKING_FALSE", "SET_WIPING_TRUE", "SET_WIPING_FALSE"));
    private static final Set<String> NEVER_FROM_THE_PANEL = Set.of("SYSTEM_PARDON", "ROLLBACK");

    private final RoleAuthorization roles = mock(RoleAuthorization.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final MinecraftIssuerAuthorizer issuers = mock(MinecraftIssuerAuthorizer.class);
    private final PunishmentModificationAuthorizer authorizer = new PunishmentModificationAuthorizer(roles, permissions, issuers);

    static Stream<Arguments> everyPermissionAndType() {
        return ALLOWED.keySet().stream().flatMap(permission -> Arrays.stream(PunishmentModificationType.values())
            .map(type -> Arguments.of(permission, type.name())));
    }

    @ParameterizedTest
    @MethodSource("everyPermissionAndType")
    void eachPermissionAllowsOnlyItsOwnTypes(String held, String type) {
        staffHolding(held);

        if (NEVER_FROM_THE_PANEL.contains(type)) {
            assertThatThrownBy(() -> require(type, null)).isInstanceOf(ValidationException.class);
        } else if (ALLOWED.get(held).contains(type)) {
            assertThatCode(() -> require(type, null)).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> require(type, null)).isInstanceOf(ForbiddenException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_PARDON", "ROLLBACK", "manual_pardon", "SOMETHING_NEW", ""})
    void unknownAndSystemTypesAreRefusedEvenForTheOwner(String type) {
        when(roles.panelPerformer(SERVER, EMAIL)).thenReturn(new RoleAuthorization.PerformerAuthority(EMAIL, null, true, true));

        assertThatThrownBy(() -> require(type, null)).isInstanceOf(ValidationException.class);
    }

    @Test
    void aPardonPermissionAloneCannotCloseAnAppeal() {
        staffHolding("punishment.modify.pardon");

        assertThatThrownBy(() -> require("APPEAL_ACCEPT", "APPEAL-1")).isInstanceOf(ForbiddenException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {PermissionService.APPEAL_MODIFY, PermissionService.TICKET_REPLY_ALL})
    void appealHandlersWhoMayPardonCanAcceptAnAppeal(String appealPermission) {
        staffHolding("punishment.modify.pardon", appealPermission);

        assertThatCode(() -> require("APPEAL_ACCEPT", "APPEAL-1")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {PermissionService.APPEAL_MODIFY, PermissionService.TICKET_REPLY_ALL,
        PermissionService.PUNISHMENT_VIEW, PermissionService.PUNISHMENT_MODIFY_DURATION})
    void approvingAnAppealWithoutThePardonPermissionIsRefusedWithItsCode(String permission) {
        staffHolding(permission);

        assertThatThrownBy(() -> authorizer.requireAppealApproval(SERVER, EMAIL))
            .isInstanceOfSatisfying(ForbiddenException.class, refused -> assertThat(refused.getMachineCode())
                .isEqualTo(PunishmentModificationAuthorizer.APPROVAL_NEEDS_PARDON));
        assertThatThrownBy(() -> require("APPEAL_ACCEPT", "APPEAL-1"))
            .isInstanceOfSatisfying(ForbiddenException.class, refused -> assertThat(refused.getMachineCode())
                .isEqualTo(PunishmentModificationAuthorizer.APPROVAL_NEEDS_PARDON));
    }

    @ParameterizedTest
    @ValueSource(strings = {PermissionService.PUNISHMENT_MODIFY, PermissionService.PUNISHMENT_MODIFY_PARDON})
    void pardonOrFullModifyMayApprove(String permission) {
        staffHolding(permission);

        assertThatCode(() -> authorizer.requireAppealApproval(SERVER, EMAIL)).doesNotThrowAnyException();
    }

    @Test
    void theOwnerMayApprove() {
        when(roles.panelPerformer(SERVER, EMAIL)).thenReturn(new RoleAuthorization.PerformerAuthority(EMAIL, null, true, true));

        assertThatCode(() -> authorizer.requireAppealApproval(SERVER, EMAIL)).doesNotThrowAnyException();
    }

    @Test
    void anUnidentifiedCallerCannotApprove() {
        when(roles.panelPerformer(SERVER, EMAIL)).thenReturn(RoleAuthorization.PerformerAuthority.unidentified());

        assertThatThrownBy(() -> authorizer.requireAppealApproval(SERVER, EMAIL)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void fullModifyStillDecidesAppeals() {
        staffHolding("punishment.modify");

        assertThatCode(() -> require("APPEAL_REJECT", "APPEAL-1")).doesNotThrowAnyException();
    }

    @Test
    void anUnidentifiedCallerIsRefused() {
        when(roles.panelPerformer(SERVER, EMAIL)).thenReturn(RoleAuthorization.PerformerAuthority.unidentified());

        assertThatThrownBy(() -> require("MANUAL_PARDON", null)).isInstanceOf(ForbiddenException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {PermissionService.PUNISHMENT_MODIFY_DURATION, PermissionService.PUNISHMENT_MODIFY_PARDON,
        PermissionService.PUNISHMENT_VIEW})
    void shorteningFromThePanelNeedsBothDurationAndPardon(String permission) {
        staffHolding(permission);

        assertThatThrownBy(() -> authorizer.shorteningBy(SERVER, EMAIL).require())
            .isInstanceOfSatisfying(ForbiddenException.class, refused -> assertThat(refused.getMachineCode())
                .isEqualTo(PunishmentShortening.NEEDS_PARDON));
    }

    @ParameterizedTest
    @ValueSource(strings = {PermissionService.PUNISHMENT_MODIFY, "punishment.modify.duration|punishment.modify.pardon"})
    void durationWithPardonOrFullModifyMayShorten(String held) {
        staffHolding(held.split("\\|"));

        assertThatCode(() -> authorizer.shorteningBy(SERVER, EMAIL).require()).doesNotThrowAnyException();
    }

    @Test
    void theOwnerMayShorten() {
        when(roles.panelPerformer(SERVER, EMAIL)).thenReturn(new RoleAuthorization.PerformerAuthority(EMAIL, null, true, true));

        assertThatCode(() -> authorizer.shorteningBy(SERVER, EMAIL).require()).doesNotThrowAnyException();
    }

    @Test
    void shorteningInGameAsksTheIssuerForBothPermissions() {
        MinecraftIssuer moderator = MinecraftIssuer.of("staff-1", false);
        when(issuers.permissionsOf(SERVER, moderator)).thenReturn(Set.of(PermissionService.PUNISHMENT_MODIFY_DURATION)::contains);

        assertThatThrownBy(() -> authorizer.shorteningBy(SERVER, moderator).require())
            .isInstanceOfSatisfying(ForbiddenException.class, refused -> assertThat(refused.getMachineCode())
                .isEqualTo(PunishmentShortening.NEEDS_PARDON));

        when(issuers.permissionsOf(SERVER, moderator)).thenReturn(Set.of(PermissionService.PUNISHMENT_MODIFY_DURATION,
            PermissionService.PUNISHMENT_MODIFY_PARDON)::contains);
        assertThatCode(() -> authorizer.shorteningBy(SERVER, moderator).require()).doesNotThrowAnyException();
    }

    @Test
    void oneShorteningResolvesItsPerformerOnceHoweverManyBansItChecks() {
        staffHolding(PermissionService.PUNISHMENT_MODIFY);
        PunishmentShortening shortening = authorizer.shorteningBy(SERVER, EMAIL);

        shortening.require();
        shortening.require();
        shortening.require();

        verify(roles, times(1)).panelPerformer(SERVER, EMAIL);
    }

    @Test
    void aShorteningThatIsNeverNeededResolvesNoPerformer() {
        authorizer.shorteningBy(SERVER, EMAIL);

        verifyNoInteractions(roles);
    }

    private void staffHolding(String... held) {
        List<String> granted = List.of(held);
        when(roles.panelPerformer(SERVER, EMAIL)).thenReturn(new RoleAuthorization.PerformerAuthority(EMAIL, ROLE, false, true));
        when(permissions.hasPermission(eq(SERVER), eq(ROLE), anyString())).thenAnswer(call ->
            granted.stream().anyMatch(permission -> RoleAuthorization.permissionImplies(permission, call.getArgument(2))));
    }

    private void require(String type, @Nullable String appealTicketId) {
        authorizer.requirePermitted(SERVER, EMAIL, new AddModificationRequest(type, null, null, null, null, appealTicketId));
    }
}
