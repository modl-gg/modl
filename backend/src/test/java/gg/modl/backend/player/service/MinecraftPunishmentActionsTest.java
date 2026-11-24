package gg.modl.backend.player.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.player.service.PunishmentQueryService.PunishmentOperationResult;
import gg.modl.backend.player.service.PunishmentQueryService.PunishmentOperationStatus;
import gg.modl.backend.role.service.MinecraftIssuer;
import gg.modl.backend.role.service.MinecraftIssuerAuthorizer;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.role.service.RoleAuthorization.PerformerAuthority;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

class MinecraftPunishmentActionsTest {
    private static final String STAFF = "staff-1";
    private static final String ROLE = "role";
    private static final String PUNISHMENT = "PUNISH01";
    private static final PunishmentOperationResult DONE =
        new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "done", true, 1);

    private final Server server = TestServers.server("Demo", "owner@example.com");
    private final PunishmentLifecycleService lifecycle = mock(PunishmentLifecycleService.class);
    private final PunishmentMutationService mutations = mock(PunishmentMutationService.class);
    private final PunishmentEvidenceService evidence = mock(PunishmentEvidenceService.class);
    private final PunishmentQueryService queries = mock(PunishmentQueryService.class);
    private final RoleAuthorization roles = mock(RoleAuthorization.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final MinecraftIssuerAuthorizer issuerAuthorizer = new MinecraftIssuerAuthorizer(roles, permissions);
    private final MinecraftPunishmentActions actions = new MinecraftPunishmentActions(lifecycle, mutations, evidence, queries,
        issuerAuthorizer, new PunishmentModificationAuthorizer(roles, permissions, issuerAuthorizer));

    private interface ActionCall {
        void run(MinecraftPunishmentActions actions, Server server, MinecraftIssuer issuer);
    }

    private enum Action {
        PARDON(MinecraftModification.PARDON, (actions, server, issuer) ->
            actions.pardon(server, PUNISHMENT, "Mod", issuer, "appeal", "ban")),
        NOTE(MinecraftModification.NOTE, (actions, server, issuer) ->
            actions.addNote(server, PUNISHMENT, "note", "Mod", issuer)),
        EVIDENCE(MinecraftModification.EVIDENCE, (actions, server, issuer) ->
            actions.addEvidence(server, PUNISHMENT, "https://example.com/clip", "Mod", issuer)),
        UPLOAD_TOKEN(MinecraftModification.EVIDENCE, (actions, server, issuer) ->
            actions.createEvidenceUploadToken(server, PUNISHMENT, "Mod", issuer)),
        DURATION(MinecraftModification.DURATION, (actions, server, issuer) ->
            actions.changeDuration(server, PUNISHMENT, 60_000L, "Mod", issuer)),
        OPTIONS(MinecraftModification.OPTIONS, (actions, server, issuer) ->
            actions.toggleOption(server, PUNISHMENT, "ALT_BLOCKING", true, "Mod", issuer)),
        TICKETS(MinecraftModification.TICKETS, (actions, server, issuer) ->
            actions.modifyTickets(server, PUNISHMENT, List.of("T1"), List.of(), false, "Mod", issuer));

        private final MinecraftModification modification;
        private final ActionCall call;

        Action(MinecraftModification modification, ActionCall call) {
            this.modification = modification;
            this.call = call;
        }
    }

    @ParameterizedTest
    @EnumSource(Action.class)
    void aStaffMemberWithoutTheActionsPermissionChangesNothing(Action action) {
        staffHolding(PermissionService.PUNISHMENT_VIEW, PermissionService.PLAYER_NOTE);

        assertThatThrownBy(() -> run(action, staff())).isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(lifecycle, mutations, evidence, queries);
    }

    @ParameterizedTest
    @EnumSource(Action.class)
    void theActionsOwnPermissionIsEnoughAndTheStaffIdIsRecorded(Action action) {
        staffHolding(action.modification.permission());
        acceptEverything();

        assertThatCode(() -> run(action, staff())).doesNotThrowAnyException();

        verifyDelegated(action, STAFF);
    }

    @ParameterizedTest
    @EnumSource(Action.class)
    void theParentPermissionAllowsEveryAction(Action action) {
        staffHolding(PermissionService.PUNISHMENT_MODIFY);
        acceptEverything();

        assertThatCode(() -> run(action, staff())).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(Action.class)
    void theConsoleActsWithoutARoleLookup(Action action) {
        acceptEverything();

        run(action, MinecraftIssuer.of(null, true));

        verifyDelegated(action, null);
        verifyNoInteractions(roles, permissions);
    }

    @ParameterizedTest
    @EnumSource(Action.class)
    void anUnknownStaffIdIsRefused(Action action) {
        when(roles.minecraftPerformer(server, STAFF)).thenReturn(PerformerAuthority.unidentified());

        assertThatThrownBy(() -> run(action, staff())).isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(lifecycle, mutations, evidence, queries);
    }

    @Test
    void aDurationChangeShortensOnlyWithThePardonPermissionOfTheSameIssuer() {
        staffHolding(PermissionService.PUNISHMENT_MODIFY_DURATION);
        acceptEverything();

        actions.changeDuration(server, PUNISHMENT, 60_000L, "Mod", staff());

        ArgumentCaptor<PunishmentShortening> shortening = ArgumentCaptor.forClass(PunishmentShortening.class);
        verify(mutations).changeDuration(eq(server), eq(PUNISHMENT), eq(60_000L), eq("Mod"), eq(STAFF), shortening.capture());
        assertThatThrownBy(() -> shortening.getValue().require()).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aDurationChangeByAStaffMemberWhoMayPardonMayShorten() {
        staffHolding(PermissionService.PUNISHMENT_MODIFY_DURATION, PermissionService.PUNISHMENT_MODIFY_PARDON);
        acceptEverything();

        actions.changeDuration(server, PUNISHMENT, 60_000L, "Mod", staff());

        ArgumentCaptor<PunishmentShortening> shortening = ArgumentCaptor.forClass(PunishmentShortening.class);
        verify(mutations).changeDuration(eq(server), eq(PUNISHMENT), eq(60_000L), eq("Mod"), eq(STAFF), shortening.capture());
        assertThatCode(() -> shortening.getValue().require()).doesNotThrowAnyException();
    }

    private void run(Action action, MinecraftIssuer issuer) {
        action.call.run(actions, server, issuer);
    }

    private void verifyDelegated(Action action, @Nullable String staffId) {
        switch (action) {
            case PARDON -> verify(lifecycle).pardonPunishment(server, PUNISHMENT, "Mod", staffId, "appeal", "ban");
            case NOTE -> verify(evidence).addPunishmentNote(server, PUNISHMENT, "note", "Mod", staffId);
            case EVIDENCE -> verify(evidence).addEvidence(server, PUNISHMENT, "https://example.com/clip", "Mod", staffId);
            case UPLOAD_TOKEN -> verify(queries).createEvidenceUploadToken(server, PUNISHMENT, "Mod");
            case DURATION -> verify(mutations).changeDuration(eq(server), eq(PUNISHMENT), eq(60_000L), eq("Mod"),
                staffId == null ? isNull() : eq(staffId), any());
            case OPTIONS -> verify(mutations).toggleOption(eq(server), eq(PUNISHMENT), eq("ALT_BLOCKING"), eq(true), eq("Mod"),
                staffId == null ? isNull() : eq(staffId), any());
            case TICKETS -> verify(mutations).modifyPunishmentTickets(eq(server), eq(PUNISHMENT), argThat(sent ->
                sent.addTicketIds().equals(List.of("T1")) && sent.removeTicketIds().isEmpty()
                    && !sent.modifyAssociatedTickets() && "Mod".equals(sent.issuerName())
                    && Objects.equals(staffId, sent.issuerId())));
        }
    }

    private static MinecraftIssuer staff() {
        return MinecraftIssuer.of(STAFF, null);
    }

    private void acceptEverything() {
        when(lifecycle.pardonPunishment(any(), any(), any(), any(), any(), any())).thenReturn(DONE);
        when(evidence.addPunishmentNote(any(), anyString(), any(), any(), any())).thenReturn(DONE);
        when(evidence.addEvidence(any(), anyString(), any(), any(), any())).thenReturn(DONE);
        when(queries.createEvidenceUploadToken(any(), any(), any())).thenReturn(Optional.of("token"));
        when(mutations.changeDuration(any(), any(), any(), any(), any(), any())).thenReturn(DONE);
        when(mutations.toggleOption(any(), any(), any(), any(Boolean.class), any(), any(), any())).thenReturn(DONE);
        when(mutations.modifyPunishmentTickets(any(), anyString(), any())).thenReturn(DONE);
    }

    private void staffHolding(String... held) {
        List<String> granted = List.of(held);
        when(roles.minecraftPerformer(server, STAFF)).thenReturn(new PerformerAuthority("mod@example.com", ROLE, false, true));
        when(permissions.hasPermission(eq(server), eq(ROLE), anyString())).thenAnswer(call ->
            granted.stream().anyMatch(permission -> RoleAuthorization.permissionImplies(permission, call.getArgument(2))));
    }
}
